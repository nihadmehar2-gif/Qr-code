package com.qrcode.scanner.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qrcode.scanner.R
import com.qrcode.scanner.data.QrContent
import com.qrcode.scanner.data.QrTypeDetector
import com.qrcode.scanner.data.ScanHistoryRepository
import com.qrcode.scanner.databinding.ActivityScannerBinding
import com.qrcode.scanner.util.QrDecoder
import com.qrcode.scanner.util.ScanFeedback
import com.qrcode.scanner.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Full-screen camera scanner.
 *
 * - Uses CameraX preview + ImageAnalysis (back camera by default).
 * - Each frame is converted to a Bitmap and passed to [QrDecoder] (ML Kit).
 * - When a code is decoded, the result bottom sheet is shown and the analyser
 *   is paused until the sheet is dismissed (prevents duplicate scans).
 * - Torch and camera-switch buttons live in the bottom bar.
 * - If launched with [MODE_GALLERY], immediately opens the photo picker (after permission
 *   check) and finishes the activity when the result is handled.
 */
class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private val viewModel: ScannerViewModel by viewModels()
    private val historyRepository by lazy { ScanHistoryRepository(this) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundCamera: androidx.camera.core.Camera? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var torchEnabled = false
    private var isPausedForResult = false
    private val analysisExecutor by lazy { Executors.newSingleThreadExecutor() }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            bindCamera()
        } else {
            showCameraPermissionDenied()
        }
    }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            openGalleryPicker()
        } else {
            showGalleryPermissionDenied()
        }
    }

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri: Uri? = data?.data ?: data?.clipData?.getItemAt(0)?.uri
            if (uri != null) {
                decodeFromGallery(uri)
            } else {
                // No image selected — return to scanner.
            }
        }
        // If we entered as MODE_GALLERY, finish the activity once the user is done.
        if (intent.getStringExtra(EXTRA_LAUNCH_MODE) == MODE_GALLERY) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge look on the scanner
        window.statusBarColor = android.graphics.Color.BLACK
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }

        // Observe the latest parsed QR content from the ViewModel.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.lastResult.collect { content ->
                    content?.let { showResultSheet(it) }
                }
            }
        }

        // If launched directly into gallery mode (from home), go straight to picker.
        if (intent.getStringExtra(EXTRA_LAUNCH_MODE) == MODE_GALLERY) {
            binding.cameraPreview.visibility = View.GONE
            binding.scannerOverlay.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.scannerHint.visibility = View.GONE
            binding.galleryLoading.visibility = View.VISIBLE
            binding.galleryLoading.text = getString(R.string.scanner_please_wait)
            requestGalleryPermissionAndPick()
        } else {
            binding.galleryLoading.visibility = View.GONE
            // Request camera permission then bind.
            if (hasCameraPermission()) {
                bindCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        binding.cameraPreview.visibility = View.VISIBLE
        binding.scannerOverlay.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        binding.scannerHint.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }
                cameraProvider?.unbindAll()
                // Keep a reference to the bound Camera so we can toggle the torch later
                // without re-binding (re-binding throws IllegalStateException).
                boundCamera = null
                boundCamera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                applyTorchState()
            } catch (e: Exception) {
                binding.scannerHint.text = getString(R.string.scanner_init_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isPausedForResult) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        // Convert YUV_420_888 to Bitmap using a small helper.
        val bitmap = ImageConvertHelper.toBitmap(mediaImage)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()
        if (bitmap == null) return

        lifecycleScope.launch(Dispatchers.Default) {
            val rotated = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                android.graphics.Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
            } else bitmap

            val raw = QrDecoder.decodeBitmap(rotated, 0)
            if (raw != null) {
                val parsed = QrTypeDetector.detect(raw)
                // Drop duplicate consecutive scans of the same content.
                if (parsed.raw != viewModel.lastRaw) {
                    viewModel.lastRaw = parsed.raw
                    withContext(Dispatchers.Main) {
                        viewModel.publishResult(parsed)
                        runFeedback()
                    }
                }
            }
        }
    }

    private fun runFeedback() {
        lifecycleScope.launch {
            val s = SettingsManager.get(this@ScannerActivity)
            ScanFeedback.feedback(this@ScannerActivity, s)
        }
    }

    private fun toggleTorch() {
        torchEnabled = !torchEnabled
        applyTorchState()
        binding.btnTorch.iconTint = ContextCompat.getColorStateList(this,
            if (torchEnabled) R.color.warning else android.R.color.white)
    }

    /**
     * Apply the current [torchEnabled] state to the bound Camera.
     *
     * IMPORTANT: we must NOT call `cameraProvider.bindToLifecycle(...)` here again — doing so
     * throws `IllegalStateException: Use cases can only be bound to a single lifecycle owner`
     * because the camera is already bound to this Activity's lifecycle. We use the
     * [boundCamera] reference captured at bind time instead.
     */
    private fun applyTorchState() {
        val camera = boundCamera ?: return
        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(torchEnabled)
        }
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        torchEnabled = false
        binding.btnTorch.iconTint = ContextCompat.getColorStateList(this, android.R.color.white)
        bindCamera()
    }

    private fun requestGalleryPermissionAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED) {
                openGalleryPicker()
            } else {
                galleryPermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED) {
                openGalleryPicker()
            } else {
                galleryPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    private fun openGalleryPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        galleryPicker.launch(Intent.createChooser(intent, getString(R.string.action_scan_gallery)))
    }

    private fun decodeFromGallery(uri: Uri) {
        binding.galleryLoading.visibility = View.VISIBLE
        binding.galleryLoading.text = getString(R.string.scanner_please_wait)
        lifecycleScope.launch(Dispatchers.Default) {
            val raw = QrDecoder.decodeUri(this@ScannerActivity, uri)
            withContext(Dispatchers.Main) {
                binding.galleryLoading.visibility = View.GONE
                if (raw == null) {
                    showNoQrInImageDialog()
                } else {
                    val parsed = QrTypeDetector.detect(raw)
                    viewModel.lastRaw = parsed.raw
                    viewModel.publishResult(parsed)
                }
            }
        }
    }

    private fun showResultSheet(content: QrContent) {
        isPausedForResult = true
        lifecycleScope.launch {
            val s = SettingsManager.get(this@ScannerActivity)
            if (s.saveHistory) {
                historyRepository.insert(content)
            }
        }
        val sheet = ResultBottomSheet.newInstance(content)
        sheet.onDismiss = { isPausedForResult = false }
        sheet.show(supportFragmentManager, ResultBottomSheet.TAG)
    }

    private fun showNoQrInImageDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.result_title))
            .setMessage(getString(R.string.scanner_no_qr_in_image))
            .setPositiveButton(getString(R.string.ok)) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                if (intent.getStringExtra(EXTRA_LAUNCH_MODE) == MODE_GALLERY) finish()
            }
            .show()
    }

    private fun showCameraPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.permission_camera_denied))
            .setPositiveButton(getString(R.string.permission_open_settings)) { d, _ ->
                d.dismiss()
                openAppSettings()
            }
            .setNegativeButton(getString(R.string.action_cancel)) { d, _ -> d.dismiss(); finish() }
            .setOnDismissListener { /* user can retry */ }
            .show()
    }

    private fun showGalleryPermissionDenied() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.permission_gallery_denied))
            .setPositiveButton(getString(R.string.permission_open_settings)) { d, _ ->
                d.dismiss()
                openAppSettings()
            }
            .setNegativeButton(getString(R.string.action_cancel)) { d, _ ->
                d.dismiss(); if (intent.getStringExtra(EXTRA_LAUNCH_MODE) == MODE_GALLERY) finish()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        boundCamera = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "launch_mode"
        const val MODE_GALLERY = "gallery"
        const val MODE_CAMERA = "camera"
    }
}

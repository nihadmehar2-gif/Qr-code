package com.nihadmehar.qrscanner.ui.scanner

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
import com.nihadmehar.qrscanner.R
import com.nihadmehar.qrscanner.data.QrContent
import com.nihadmehar.qrscanner.data.QrTypeDetector
import com.nihadmehar.qrscanner.data.ScanHistoryRepository
import com.nihadmehar.qrscanner.databinding.ActivityScannerBinding
import com.nihadmehar.qrscanner.util.QrDecoder
import com.nihadmehar.qrscanner.util.ScanFeedback
import com.nihadmehar.qrscanner.util.SettingsManager
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

        // Observe scan-result events from the ViewModel.
        //
        // We deliberately use a Channel-backed Flow (not a StateFlow) so each event
        // is consumed exactly once — see ScannerViewModel for the rationale.
        //
        // repeatOnLifecycle(STARTED) ensures the collector is cancelled when the
        // activity stops (e.g. user navigates away) and re-started when it resumes.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.resultEvent.collect { event ->
                    showResultSheet(event.content)
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
            // Already showing a result sheet — drop the frame so we don't
            // save the same scan to history multiple times.
            imageProxy.close()
            return
        }
        // Snapshot rotation now, before ML Kit reads it — imageProxy.imageInfo
        // is owned by the analyzer thread.
        // We pass the imageProxy to QrDecoder.decodeImageProxy(), which TAKES
        // OWNERSHIP of closing it. ML Kit's InputImage.fromMediaImage holds a
        // reference to imageProxy.image until its async Task completes, so we
        // must NOT close imageProxy ourselves — decodeImageProxy does it in
        // a finally block.

        lifecycleScope.launch(Dispatchers.Default) {
            val raw = try {
                QrDecoder.decodeImageProxy(imageProxy)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine was cancelled (e.g. activity destroyed) — QrDecoder
                // still closed the imageProxy in its finally block. Just bail.
                throw e
            } catch (e: Exception) {
                // Any other unexpected error — log and bail. QrDecoder already
                // closed the imageProxy in its finally block.
                android.util.Log.w("ScannerActivity", "decodeImageProxy failed", e)
                null
            }
            if (raw != null) {
                val parsed = QrTypeDetector.detect(raw)
                withContext(Dispatchers.Main) {
                    // Triple-guard against duplicate sheet shows:
                    //   1. isPausedForResult (already set if a sheet is open)
                    //   2. findFragmentByTag(TAG) != null (defensive in case the
                    //      flag was reset but the sheet wasn't dismissed yet)
                    //   3. lifecycle.currentState.isAtLeast(STARTED) — only
                    //      publish if the activity is still alive
                    if (!isPausedForResult
                        && supportFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) == null
                        && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                    ) {
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
                    // Gallery scan: always publish — even if the same code was just scanned
                    // via the camera, the user explicitly asked us to decode this image.
                    viewModel.publishResult(parsed)
                }
            }
        }
    }

    private fun showResultSheet(content: QrContent) {
        // Guard against the (rare) race where two scan-result events land on the
        // main thread back-to-back before isPausedForResult is flipped to true.
        // Without this guard, the second sheet.show() call would throw
        // IllegalStateException: "Fragment already added".
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(ResultBottomSheet.TAG) != null) {
            // A sheet is already on screen — drop this duplicate event.
            return
        }
        isPausedForResult = true
        // Persist to history off the main thread so the sheet can appear immediately.
        // Use a separate launch so a slow DB write can't block the sheet's animation.
        lifecycleScope.launch {
            try {
                val s = SettingsManager.get(this@ScannerActivity)
                if (s.saveHistory) {
                    historyRepository.insert(content)
                }
            } catch (e: Exception) {
                // Database write failed — don't crash the UI. The user still gets
                // the result sheet; they just won't see it in history.
                android.util.Log.w("ScannerActivity", "history insert failed", e)
            }
        }
        val sheet = ResultBottomSheet.newInstance(content)
        sheet.onDismiss = { isPausedForResult = false }
        sheet.show(fm, ResultBottomSheet.TAG)
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
        // Stop accepting new image-analysis tasks FIRST, then unbind the camera.
        // Without shutdownNow(), a frame could be queued for analysis after
        // cameraProvider.unbindAll() runs and crash with RejectedExecutionException.
        boundCamera = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "launch_mode"
        const val MODE_GALLERY = "gallery"
        const val MODE_CAMERA = "camera"
    }
}

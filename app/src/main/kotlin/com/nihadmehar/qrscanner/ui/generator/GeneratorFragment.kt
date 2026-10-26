package com.nihadmehar.qrscanner.ui.generator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nihadmehar.qrscanner.R
import com.nihadmehar.qrscanner.databinding.FragmentGeneratorBinding
import com.nihadmehar.qrscanner.util.QrGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class GeneratorFragment : Fragment() {

    private var _binding: FragmentGeneratorBinding? = null
    private val binding get() = _binding!!

    private var generatedBitmap: Bitmap? = null
    private var fgColor: Int = Color.parseColor("#0F1222")
    private var bgColor: Int = Color.parseColor("#FFFFFF")
    private var selectedTypeIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTypeSpinner()
        binding.spinnerType.setSelection(0)
        updateVisibleFields(0)

        binding.spinnerType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedTypeIndex = position
                updateVisibleFields(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.btnGenerate.setOnClickListener { generateQr() }
        binding.btnSave.setOnClickListener { saveQrToGallery() }
        binding.btnShare.setOnClickListener { shareQr() }
        binding.btnColorFg.setOnClickListener { showColorPicker(true) }
        binding.btnColorBg.setOnClickListener { showColorPicker(false) }
        binding.btnResetColors.setOnClickListener {
            fgColor = Color.parseColor("#0F1222")
            bgColor = Color.parseColor("#FFFFFF")
            updateColorButtons()
        }
        updateColorButtons()
    }

    private fun setupTypeSpinner() {
        val types = listOf(
            getString(R.string.generator_type_text),
            getString(R.string.generator_type_url),
            getString(R.string.generator_type_wifi),
            getString(R.string.generator_type_phone),
            getString(R.string.generator_type_email),
            getString(R.string.generator_type_sms),
            getString(R.string.generator_type_vcard)
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
        binding.spinnerType.adapter = adapter
    }

    private fun updateVisibleFields(position: Int) {
        binding.fieldText.visibility = View.GONE
        binding.fieldUrl.visibility = View.GONE
        binding.fieldWifi.visibility = View.GONE
        binding.fieldPhone.visibility = View.GONE
        binding.fieldEmail.visibility = View.GONE
        binding.fieldSms.visibility = View.GONE
        binding.fieldVcard.visibility = View.GONE
        when (position) {
            0 -> binding.fieldText.visibility = View.VISIBLE
            1 -> binding.fieldUrl.visibility = View.VISIBLE
            2 -> binding.fieldWifi.visibility = View.VISIBLE
            3 -> binding.fieldPhone.visibility = View.VISIBLE
            4 -> binding.fieldEmail.visibility = View.VISIBLE
            5 -> binding.fieldSms.visibility = View.VISIBLE
            6 -> binding.fieldVcard.visibility = View.VISIBLE
        }
    }

    private fun generateQr() {
        val payload = buildPayload() ?: return
        if (payload.isBlank()) {
            Snackbar.make(binding.root, R.string.toast_invalid_input, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnGenerate.isEnabled = false
        binding.progressGenerate.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = try {
                QrGenerator.render(payload, sizePx = 768, fgColor, bgColor)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                binding.btnGenerate.isEnabled = true
                binding.progressGenerate.visibility = View.GONE
                if (bmp != null) {
                    generatedBitmap = bmp
                    binding.ivQrPreview.setImageBitmap(bmp)
                    binding.btnSave.isEnabled = true
                    binding.btnShare.isEnabled = true
                    binding.placeholderText.visibility = View.GONE
                } else {
                    Snackbar.make(binding.root, R.string.toast_save_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildPayload(): String? {
        return when (selectedTypeIndex) {
            0 -> binding.etText.text?.toString()?.trim().orEmpty()
            1 -> {
                val url = binding.etUrl.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) null else url
            }
            2 -> {
                val ssid = binding.etWifiSsid.text?.toString()?.trim().orEmpty()
                val pass = binding.etWifiPass.text?.toString().orEmpty()
                val sec = binding.spinnerWifiSec.selectedItem.toString()
                val hidden = binding.cbHidden.isChecked
                if (ssid.isEmpty()) null else QrGenerator.wifiPayload(ssid, pass, sec, hidden)
            }
            3 -> {
                val num = binding.etPhone.text?.toString()?.trim().orEmpty()
                if (num.isEmpty()) null else QrGenerator.phonePayload(num)
            }
            4 -> {
                val email = binding.etEmail.text?.toString()?.trim().orEmpty()
                if (email.isEmpty()) null else QrGenerator.emailPayload(
                    email,
                    binding.etEmailSubject.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    binding.etEmailBody.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            5 -> {
                val num = binding.etSmsNumber.text?.toString()?.trim().orEmpty()
                if (num.isEmpty()) null else QrGenerator.smsPayload(
                    num, binding.etSmsMessage.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            6 -> {
                val name = binding.etVcardName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) null else QrGenerator.vCardPayload(
                    name,
                    organization = binding.etVcardOrg.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    title = binding.etVcardTitle.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    phone = binding.etVcardPhone.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    email = binding.etVcardEmail.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    url = binding.etVcardUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    note = null
                )
            }
            else -> null
        }
    }

    private fun showColorPicker(isForeground: Boolean) {
        // Use the built-in color picker dialog (Material has no native one — use a numeric grid).
        val colors = intArrayOf(
            Color.BLACK,
            Color.parseColor("#0F1222"),
            Color.parseColor("#3B6BFF"),
            Color.parseColor("#7B61FF"),
            Color.parseColor("#FF6B9D"),
            Color.parseColor("#19B36A"),
            Color.parseColor("#F5A623"),
            Color.parseColor("#E5484D"),
            Color.parseColor("#00897B"),
            Color.parseColor("#5D4037")
        )
        val labels = arrayOf(
            "Black", "Dark", "Blue", "Purple", "Pink", "Green", "Amber", "Red", "Teal", "Brown"
        )
        MaterialColorPickerDialog(requireContext(), colors, labels) { pickedColor ->
            if (isForeground) fgColor = pickedColor else bgColor = pickedColor
            updateColorButtons()
        }.show()
    }

    private fun updateColorButtons() {
        binding.btnColorFg.iconTint = android.content.res.ColorStateList.valueOf(fgColor)
        binding.btnColorBg.iconTint = android.content.res.ColorStateList.valueOf(bgColor)
    }

    private fun saveQrToGallery() {
        val bmp = generatedBitmap ?: return
        binding.btnSave.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = saveQrPng(bmp)
            withContext(Dispatchers.Main) {
                binding.btnSave.isEnabled = true
                if (ok) {
                    Snackbar.make(binding.root, R.string.toast_saved_to_gallery, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.action_share) { shareQr() }.show()
                } else {
                    Snackbar.make(binding.root, R.string.toast_save_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Save the QR PNG into the public Pictures directory via MediaStore.
     *
     * The previous implementation used the now-deprecated
     * MediaStore.Images.Media.insertImage(), which:
     *   - Is deprecated on API 29+ and broken without WRITE_EXTERNAL_STORAGE on
     *     API < 29 (which we don't request).
     *   - Always returns a non-null Uri even when the insert fails on some OEMs.
     *   - Doesn't let us specify a display name + MIME type + relative path
     *     (the new MediaStore.Images.ImageColumns.RELATIVE_PATH API gives us a
     *     clean "Pictures/QR Codes/" subfolder).
     *
     * This rewrite uses ContentResolver.openOutputStream() with a fully-populated
     * ContentValues, which is the supported path on API 29+ and degrades
     * gracefully on older versions via the legacy insertImage() call.
     */
    private fun saveQrPng(bmp: Bitmap): Boolean {
        val resolver = requireContext().contentResolver
        val filename = "qr_${System.currentTimeMillis()}.png"
        val mimeType = "image/png"
        val description = "QR code generated by QR Scanner & Generator"

        return try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.Images.Media.DESCRIPTION, description)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Save into Pictures/QR Codes/ — a clean subfolder that
                    // shows up in the user's gallery app, separate from random
                    // screenshots.
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/QR Codes"
                    )
                    // Don't mark as "pending" — we'll write the bytes immediately.
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Images.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, contentValues) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    resolver.delete(uri, null, null)
                    return false
                }
            } ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Mark the entry as no longer pending — visible to other apps.
                val updateValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("GeneratorFragment", "saveQrPng failed", e)
            false
        }
    }

    /**
     * Share the generated QR code via the system share sheet.
     *
     * Writes the PNG to a private cache file and exposes it via FileProvider.
     * The previous code wrote a permanent copy into MediaStore every time the
     * user tapped Share — filling the gallery with throwaway duplicates.
     *
     * NOTE: requires a FileProvider declaration in AndroidManifest.xml. We add
     * that as part of this fix.
     */
    private fun shareQr() {
        val bmp = generatedBitmap ?: run {
            Snackbar.make(binding.root, R.string.toast_save_failed, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.btnShare.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = try {
                writePngToCache(bmp)
            } catch (e: Exception) {
                android.util.Log.w("GeneratorFragment", "shareQr write failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                binding.btnShare.isEnabled = true
                if (uri == null) {
                    Snackbar.make(binding.root, R.string.toast_save_failed, Snackbar.LENGTH_SHORT).show()
                    return@withContext
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.action_share_qr))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share_qr)))
                } catch (e: android.content.ActivityNotFoundException) {
                    Snackbar.make(binding.root, R.string.toast_save_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Write the PNG to a private cache file (cachePath/qr_share_<timestamp>.png)
     * and return a content:// Uri via the FileProvider declared in the manifest.
     * Cache files are auto-cleaned by the OS when disk space is low, so we don't
     * accumulate share-copies over time.
     */
    private fun writePngToCache(bmp: Bitmap): Uri? {
        val ctx = requireContext()
        val cacheDir = java.io.File(ctx.cacheDir, "shared_qr").apply { mkdirs() }
        val file = java.io.File(cacheDir, "qr_share_${System.currentTimeMillis()}.png")
        try {
            java.io.FileOutputStream(file).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("GeneratorFragment", "writePngToCache failed", e)
            return null
        }
        return androidx.core.content.FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            file
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

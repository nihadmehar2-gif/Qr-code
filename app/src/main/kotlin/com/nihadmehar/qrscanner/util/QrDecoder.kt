package com.nihadmehar.qrscanner.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wrapper around ML Kit Barcode Scanning for both gallery images (Bitmap) and
 * live camera frames (also Bitmaps after CameraX analysis is converted).
 *
 * All detection is **on-device** — no image data is ever sent to a server.
 */
object QrDecoder {

    private val options by lazy {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
    }

    /** Returns the raw string of the first detected barcode, or null if none. */
    suspend fun decodeBitmap(bitmap: Bitmap, rotation: Int = 0): String? {
        val image = InputImage.fromBitmap(bitmap, rotation)
        val scanner = BarcodeScanning.getClient(options)
        return try {
            val barcodes = scanner.processImageAwait(image)
            barcodes.firstOrNull()?.rawValue
        } catch (e: Exception) {
            null
        } finally {
            scanner.close()
        }
    }

    /** Returns the raw string of the first detected barcode in a content Uri image. */
    suspend fun decodeUri(context: Context, uri: Uri): String? {
        val bitmap = loadBitmapFromUri(context, uri) ?: return null
        return decodeBitmap(bitmap, 0)
    }

    private suspend fun com.google.mlkit.vision.barcode.BarcodeScanner.processImageAwait(
        image: InputImage
    ): List<Barcode> = suspendCancellableCoroutine { cont ->
        process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }
}

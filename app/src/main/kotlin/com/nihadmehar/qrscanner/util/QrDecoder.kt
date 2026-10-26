package com.nihadmehar.qrscanner.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.net.Uri
import android.content.Context
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wrapper around ML Kit Barcode Scanning for camera frames ([ImageProxy] from
 * CameraX's ImageAnalysis) and gallery images (Bitmap).
 *
 * All detection is **on-device** — no image data is ever sent to a server.
 *
 * ## Image pipeline (correct as of v1.0.3)
 *
 * For live camera frames we use [InputImage.fromMediaImage], which is the
 * official ML Kit API for CameraX. ML Kit reads the YUV_420_888 planes itself
 * and handles stride padding + rotation correctly — we never touch the byte
 * layout ourselves.
 *
 * The ImageProxy must remain alive until ML Kit's async Task completes, so
 * [decodeImageProxy] takes ownership of closing the proxy. It closes the proxy
 * in a `finally` block so the close happens on success, failure, AND coroutine
 * cancellation.
 *
 * ### Why NOT to use InputImage.fromByteArray with IMAGE_FORMAT_NV21
 *
 * The previous implementation tried to skip ML Kit's plane extraction by
 * copying the Y plane only and passing it as NV21. This was incorrect:
 *
 *  - NV21 format requires width × height × 1.5 bytes (Y plane + interleaved
 *    V/U chroma plane, both V and U downsampled 2× horizontally and vertically).
 *  - We passed only width × height bytes (Y only). ML Kit read past the buffer
 *    end looking for chroma data → either IndexOutOfBoundsException, silent
 *    detection failure, or garbage UV interpreted as edge artifacts that confused
 *    the detector.
 *  - There is no "greyscale NV21" — the format contract requires the chroma
 *    bytes to be present, even if they're zeros.
 *
 * Using [InputImage.fromMediaImage] sidesteps all of this: ML Kit handles
 * the YUV_420_888 → internal format conversion itself, with full access to
 * the U and V planes via the Image's planes[] array.
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

    /**
     * Lazily-constructed, cached BarcodeScanner.
     *
     * ML Kit's BarcodeScanner is thread-safe and intended to be reused — the
     * docs explicitly say "an instance of a scanner can be used across multiple
     * invocations of process". Creating one per frame was wasteful (50-100ms
     * of object construction + JNI handshaking per call).
     */
    private val scanner by lazy { BarcodeScanning.getClient(options) }

    /**
     * Decode a CameraX [ImageProxy] using ML Kit's documented
     * `InputImage.fromMediaImage` API.
     *
     * OWNERSHIP: this function takes ownership of [imageProxy] and guarantees
     * it is closed via a `finally` block. Callers MUST NOT close the proxy
     * themselves — passing a closed proxy will throw on the ML Kit call.
     *
     * THREADING: can be called from any thread. ML Kit runs its scan on its own
     * executor; the suspend function returns when ML Kit's Task completes.
     *
     * LIFECYCLE: if the calling coroutine is cancelled while ML Kit is still
     * processing, the proxy is still closed (via the finally block) and the
     * caller's continuation is resumed with null.
     *
     * @return the raw value of the first detected barcode, or null if no code
     *         was found OR if any error occurred.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    suspend fun decodeImageProxy(imageProxy: ImageProxy): String? {
        val mediaImage: Image = imageProxy.image ?: run {
            imageProxy.close()
            return null
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        // ML Kit's InputImage.fromMediaImage holds a REFERENCE to the Image —
        // it does NOT copy the bytes. The Image (and its parent ImageProxy)
        // must remain alive until ML Kit's process() Task completes.
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        return try {
            val barcodes = scanner.processImageAwait(inputImage)
            barcodes.firstOrNull()?.rawValue
        } catch (e: Exception) {
            // CancellationException is re-thrown by suspendCancellableCoroutine
            // (we don't swallow it) — the finally block still runs and closes
            // the proxy. Any other exception is treated as a decode failure.
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            // ALWAYS close the proxy — success, failure, or cancellation.
            // Without this, CameraX's ImageAnalysis backpressure strategy
            // would block forever waiting for the next frame slot.
            imageProxy.close()
        }
    }

    /** Returns the raw string of the first detected barcode in a Bitmap, or null. */
    suspend fun decodeBitmap(bitmap: Bitmap, rotation: Int = 0): String? {
        val image = InputImage.fromBitmap(bitmap, rotation)
        return try {
            val barcodes = scanner.processImageAwait(image)
            barcodes.firstOrNull()?.rawValue
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
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

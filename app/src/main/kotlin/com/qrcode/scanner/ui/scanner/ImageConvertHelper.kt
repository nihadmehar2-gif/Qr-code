package com.qrcode.scanner.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import java.nio.ByteBuffer

/**
 * Converts a CameraX YUV_420_888 [Image] to an ARGB_8888 [Bitmap].
 *
 * CameraX delivers frames as YUV_420_888 on the back pressure strategy.
 * ML Kit's [InputImage.fromBitmap] takes a Bitmap, so we need this conversion.
 *
 * Implementation: render the Y plane to grey-scale for fast scanning — ML Kit
 * also accepts full colour but a Y-only bitmap is sufficient for QR detection
 * and roughly 4× cheaper to allocate / copy.
 */
object ImageConvertHelper {

    @OptIn(ExperimentalGetImage::class)
    fun toBitmap(image: Image): Bitmap? {
        return try {
            if (image.format != ImageFormat.YUV_420_888) return null
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            // Build a greyscale bitmap. ML Kit detects QR codes equally well on greyscale.
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            var pos = 0
            for (row in 0 until height) {
                val rowOffset = row * yRowStride
                for (col in 0 until width) {
                    val yv = yBuffer.get(rowOffset + col * yPixelStride).toInt() and 0xFF
                    // Pack as opaque grey
                    pixels[pos++] = (0xFF shl 24) or (yv shl 16) or (yv shl 8) or yv
                }
            }
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Faster allocator that avoids a per-pixel loop for the most common case
     * (Y plane is contiguous). Kept here in case we ever want to swap in.
     */
    @Suppress("unused")
    private fun directCopy(buffer: ByteBuffer, size: Int): ByteArray {
        val bytes = ByteArray(size)
        buffer.rewind()
        buffer.get(bytes)
        return bytes
    }
}

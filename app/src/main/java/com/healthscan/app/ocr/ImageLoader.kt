package com.healthscan.app.ocr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException

data class LoadedImage(val bitmap: Bitmap, val sourceLabel: String, val originalWidth: Int, val originalHeight: Int)

object ImageLoader {

    private const val TARGET_SHORT_SIDE = 1000
    private const val MAX_PIXELS = 14_000_000

    @Throws(IOException::class)
    fun load(resolver: ContentResolver, uri: Uri): LoadedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IOException("Unsupported or corrupt image")

        val sample = sampleSizeFor(srcW, srcH)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap: Bitmap? = null
        var currentSample = sample
        while (bitmap == null && currentSample <= 32) {
            bitmap = try {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bitmap == null) {
                currentSample *= 2
                options.inSampleSize = currentSample
            }
        }
        val decoded = bitmap ?: throw IOException("Not enough memory to decode image")
        val rotated = applyExifRotation(resolver, uri, decoded)
        val scaled = upscaleIfSmall(rotated)
        return LoadedImage(
            bitmap = scaled,
            sourceLabel = uri.lastPathSegment ?: uri.toString(),
            originalWidth = srcW,
            originalHeight = srcH,
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > MAX_PIXELS && sample < 128) {
            sample *= 2
        }
        return sample
    }

    private fun upscaleIfSmall(bitmap: Bitmap): Bitmap {
        val shortSide = minOf(bitmap.width, bitmap.height)
        if (shortSide >= TARGET_SHORT_SIDE) return bitmap
        val scale = (TARGET_SHORT_SIDE.toFloat() / shortSide).coerceAtMost(2.5f)
        if (scale <= 1.02f) return bitmap
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyExifRotation(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}

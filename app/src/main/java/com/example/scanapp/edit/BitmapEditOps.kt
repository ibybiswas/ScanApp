package com.example.scanapp.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PageFilter { NONE, GRAYSCALE, BLACK_AND_WHITE }

object BitmapEditOps {

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun applyFilter(bitmap: Bitmap, filter: PageFilter): Bitmap {
        if (filter == PageFilter.NONE) return bitmap

        val colorMatrix = when (filter) {
            PageFilter.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
            PageFilter.BLACK_AND_WHITE -> blackAndWhiteMatrix()
            PageFilter.NONE -> return bitmap
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    fun autoEnhance(bitmap: Bitmap): Bitmap {
        val (low, high) = analyzeLuminanceRange(bitmap)
        if (high <= low) return bitmap

        val range = (high - low).coerceAtLeast(1f)
        val scale = 255f / range
        val translate = -low * scale

        val stretchMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(stretchMatrix) }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun analyzeLuminanceRange(bitmap: Bitmap): Pair<Float, Float> {
        val sampleWidth = min(200, bitmap.width)
        val sampleHeight = max(1, (bitmap.height.toFloat() * sampleWidth / bitmap.width).roundToInt())
        val sample = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)

        val histogram = IntArray(256)
        val pixels = IntArray(sampleWidth * sampleHeight)
        sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        if (sample !== bitmap) sample.recycle()

        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            histogram[luminance]++
        }

        val totalPixels = pixels.size
        val lowCutoff = (totalPixels * 0.01f)
        val highCutoff = (totalPixels * 0.99f)

        var cumulative = 0
        var low = 0
        var high = 255
        for (level in 0..255) {
            cumulative += histogram[level]
            if (cumulative >= lowCutoff) { low = level; break }
        }
        cumulative = 0
        for (level in 255 downTo 0) {
            cumulative += histogram[level]
            if (cumulative >= (totalPixels - highCutoff)) { high = level; break }
        }

        return low.toFloat() to high.toFloat()
    }

    private fun blackAndWhiteMatrix(): ColorMatrix {
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 2.2f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val combined = ColorMatrix()
        combined.postConcat(grayscale)
        combined.postConcat(contrastMatrix)
        return combined
    }
}

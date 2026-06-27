package com.example.scanapp.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

enum class OutputFormat { JPEG, PNG, PDF }

data class ExportOptions(
    val format: OutputFormat,
    val targetSizeBytes: Long? = null,   // null = no size constraint, use quality directly
    val quality: Int = 90,               // used when no target size given (0-100)
    val maxDimension: Int? = null        // optional manual downscale cap (longest side, px)
)

data class ExportResult(
    val file: File,
    val finalSizeBytes: Long,
    val finalQuality: Int,
    val finalWidth: Int,
    val finalHeight: Int
)

/**
 * Handles converting scanned page URIs into a size-constrained PDF or image(s).
 *
 * Strategy for hitting a target size:
 *  1. Try quality-only reduction first (cheap, preserves resolution) via binary search
 *     on JPEG quality 2..95.
 *  2. If even quality=2 is still over budget, progressively downscale resolution
 *     (90% steps) and repeat the quality search at the new resolution.
 *  3. Stop when under budget or after a safety cap on iterations, returning the
 *     closest-under-budget result found.
 *
 * This mirrors what most "compress to X MB" tools do, since file size is a
 * non-linear function of quality and there's no closed-form inverse.
 */
class ExportEngine(private val context: Context) {

    /** Compress a single bitmap to JPEG/PNG bytes, optionally hitting a target size. */
    fun compressImage(
        original: Bitmap,
        options: ExportOptions
    ): Pair<ByteArrayOutputStream, ExportMeta> {
        require(options.format != OutputFormat.PDF) { "Use exportAsPdf for PDF output" }

        var bitmap = original
        options.maxDimension?.let { cap ->
            bitmap = downscaleTo(bitmap, cap)
        }

        if (options.targetSizeBytes == null) {
            val out = encode(bitmap, options.format, options.quality)
            return out to ExportMeta(options.quality, bitmap.width, bitmap.height)
        }

        return compressToTarget(bitmap, options.format, options.targetSizeBytes)
    }

    /**
     * Binary search on quality at current resolution; if minimum quality still
     * exceeds target, downscale by 10% and retry, up to 6 downscale passes.
     */
    private fun compressToTarget(
        startBitmap: Bitmap,
        format: OutputFormat,
        targetBytes: Long
    ): Pair<ByteArrayOutputStream, ExportMeta> {
        var bitmap = startBitmap
        var bestOut: ByteArrayOutputStream? = null
        var bestQuality = 2
        var downscalePasses = 0

        while (downscalePasses <= 6) {
            var lo = 2
            var hi = 95
            var foundUnderBudget: ByteArrayOutputStream? = null
            var foundQuality = lo

            // Binary search for highest quality that fits under target at this resolution
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val attempt = encode(bitmap, format, mid)
                if (attempt.size() <= targetBytes) {
                    foundUnderBudget = attempt
                    foundQuality = mid
                    lo = mid + 1 // try higher quality
                } else {
                    hi = mid - 1 // need lower quality
                }
            }

            if (foundUnderBudget != null) {
                return foundUnderBudget to ExportMeta(foundQuality, bitmap.width, bitmap.height)
            }

            // Even quality=2 didn't fit at this resolution — downscale by 10% and retry
            bestOut = encode(bitmap, format, 2)
            bestQuality = 2
            val newMaxDim = (maxOf(bitmap.width, bitmap.height) * 0.9).toInt().coerceAtLeast(50)
            bitmap = downscaleTo(bitmap, newMaxDim)
            downscalePasses++
        }

        // Couldn't hit target even at minimum quality/resolution; return closest attempt
        return (bestOut ?: encode(bitmap, format, 2)) to ExportMeta(bestQuality, bitmap.width, bitmap.height)
    }

    private fun encode(bitmap: Bitmap, format: OutputFormat, quality: Int): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        val compressFormat = if (format == OutputFormat.PNG) Bitmap.CompressFormat.PNG
        else Bitmap.CompressFormat.JPEG
        bitmap.compress(compressFormat, quality, out)
        return out
    }

    private fun downscaleTo(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Build a PDF from multiple scanned pages, hitting an OVERALL target size by
     * distributing the budget evenly across pages, then compressing each page's
     * embedded JPEG to fit its share. PDF page bitmaps are embedded as JPEG streams
     * regardless of requested "format" since PDF always needs a raster encode.
     */
    fun exportAsPdf(
        pageUris: List<Uri>,
        targetSizeBytes: Long?,
        outputFile: File
    ): ExportResult {
        val pdf = PdfDocument()
        val perPageBudget = targetSizeBytes?.let {
            // Reserve ~5% for PDF structural overhead, split rest evenly
            (it * 0.95 / pageUris.size).toLong()
        }

        var lastW = 0
        var lastH = 0
        var lastQuality = 90

        pageUris.forEachIndexed { index, uri ->
            val bitmap = loadBitmap(uri)
            val options = ExportOptions(
                format = OutputFormat.JPEG,
                targetSizeBytes = perPageBudget,
                quality = 90
            )
            val (compressedOut, meta) = compressImage(bitmap, options)
            val compressedBitmap = BitmapFactory.decodeByteArray(
                compressedOut.toByteArray(), 0, compressedOut.size()
            )

            val pageInfo = PdfDocument.PageInfo.Builder(
                compressedBitmap.width, compressedBitmap.height, index
            ).create()
            val page = pdf.startPage(pageInfo)
            page.canvas.drawBitmap(compressedBitmap, Matrix(), null)
            pdf.finishPage(page)

            lastW = compressedBitmap.width
            lastH = compressedBitmap.height
            lastQuality = meta.quality
        }

        FileOutputStream(outputFile).use { pdf.writeTo(it) }
        pdf.close()

        return ExportResult(
            file = outputFile,
            finalSizeBytes = outputFile.length(),
            finalQuality = lastQuality,
            finalWidth = lastW,
            finalHeight = lastH
        )
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        return input.use { BitmapFactory.decodeStream(it) }
    }

    data class ExportMeta(val quality: Int, val width: Int, val height: Int)
}

package com.example.scanapp.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

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
     * JPEG to fit its share. The JPEG bytes are embedded directly into the PDF
     * via JpegPdfWriter — NOT drawn through android.graphics.pdf.PdfDocument,
     * which silently re-rasterizes images and discards JPEG compression (this
     * was the cause of exported PDFs being much larger than the requested limit).
     */
    fun exportAsPdf(
        pageUris: List<Uri>,
        targetSizeBytes: Long?,
        outputFile: File
    ): ExportResult {
        // Reserve a little headroom for PDF structural overhead (object headers, xref table,
        // content streams) so the sum of JPEG sizes doesn't itself exceed the target.
        val structuralOverheadPerPage = 200L // bytes; comfortably covers this writer's per-page overhead
        val perPageBudget = targetSizeBytes?.let {
            ((it - totalStructuralOverhead(pageUris.size, structuralOverheadPerPage)) / pageUris.size)
                .coerceAtLeast(1024L) // never ask for less than 1KB; that's not achievable for a real scan
        }

        var lastW = 0
        var lastH = 0
        var lastQuality = 90
        val jpegPages = mutableListOf<JpegPdfWriter.JpegPage>()

        pageUris.forEach { uri ->
            val bitmap = loadBitmap(uri)
            val options = ExportOptions(
                format = OutputFormat.JPEG,
                targetSizeBytes = perPageBudget,
                quality = 90
            )
            val (compressedOut, meta) = compressImage(bitmap, options)
            val jpegBytes = compressedOut.toByteArray()

            jpegPages.add(JpegPdfWriter.JpegPage(jpegBytes, meta.width, meta.height))

            lastW = meta.width
            lastH = meta.height
            lastQuality = meta.quality
        }

        JpegPdfWriter().write(jpegPages, outputFile)

        return ExportResult(
            file = outputFile,
            finalSizeBytes = outputFile.length(),
            finalQuality = lastQuality,
            finalWidth = lastW,
            finalHeight = lastH
        )
    }

    private fun totalStructuralOverhead(pageCount: Int, perPage: Long): Long = pageCount * perPage

    private fun loadBitmap(uri: Uri): Bitmap {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")
        return input.use { BitmapFactory.decodeStream(it) }
    }

    data class ExportMeta(val quality: Int, val width: Int, val height: Int)
}

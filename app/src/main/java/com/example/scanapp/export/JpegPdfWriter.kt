package com.example.scanapp.export

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal PDF writer that embeds pre-compressed JPEG bytes directly as DCTDecode
 * image streams.
 *
 * WHY THIS EXISTS: android.graphics.pdf.PdfDocument draws bitmaps onto a Canvas
 * and rasterizes them internally — it does NOT preserve JPEG compression. A
 * 150KB JPEG can balloon to 800KB+ once drawn via PdfDocument.Canvas.drawBitmap(),
 * which silently defeats any size-targeting done beforehand. Embedding the JPEG
 * bytes directly as a PDF image XObject (the same technique real PDF libraries
 * use) keeps the file size equal to the sum of the JPEG byte sizes plus a few
 * hundred bytes of PDF structure overhead — predictable and controllable.
 *
 * PDF object layout per page:
 *   - one "Page" object (references a content stream + an image XObject)
 *   - one "Contents" stream object (just draws the image XObject full-page)
 *   - one "Image" XObject stream (the raw JPEG bytes, /Filter /DCTDecode)
 *
 * Output is a flat, valid PDF 1.4 file with a correct xref table, so it opens
 * normally in any PDF viewer.
 */
class JpegPdfWriter {

    data class JpegPage(val jpegBytes: ByteArray, val widthPx: Int, val heightPx: Int)

    fun write(pages: List<JpegPage>, outputFile: File) {
        require(pages.isNotEmpty()) { "Need at least one page" }

        val buffer = ByteArrayOutputStream()
        val offsetByObjNum = HashMap<Int, Long>()

        fun writeBytes(bytes: ByteArray) {
            buffer.write(bytes)
        }

        fun writeStr(s: String) {
            buffer.write(s.toByteArray(Charsets.ISO_8859_1))
        }

        fun recordOffset(objNum: Int) {
            offsetByObjNum[objNum] = buffer.size().toLong()
        }

        // Object numbering: 1=Catalog, 2=Pages.
        // Then per page i: contentObjNum, imageObjNum, pageObjNum (3 objects per page).
        val n = pages.size
        val contentObjNums = IntArray(n)
        val imageObjNums = IntArray(n)
        val pageObjNums = IntArray(n)
        var next = 3
        for (i in 0 until n) {
            contentObjNums[i] = next++
            imageObjNums[i] = next++
            pageObjNums[i] = next++
        }
        val totalObjects = next - 1 // highest object number used

        writeStr("%PDF-1.4\n")
        // A binary comment line is conventional after the header to mark the file as binary-safe;
        // harmless to omit, so we skip it to keep this writer simple.

        // 1: Catalog
        recordOffset(1)
        writeStr("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        // 2: Pages
        recordOffset(2)
        val kidsRefs = pageObjNums.joinToString(" ") { "$it 0 R" }
        writeStr("2 0 obj\n<< /Type /Pages /Kids [ $kidsRefs ] /Count $n >>\nendobj\n")

        for (i in 0 until n) {
            val page = pages[i]
            val w = page.widthPx.coerceAtLeast(1)
            val h = page.heightPx.coerceAtLeast(1)
            val contentNum = contentObjNums[i]
            val imageNum = imageObjNums[i]
            val pageNum = pageObjNums[i]

            // Content stream: scale the 1x1 unit image XObject up to the full page size and draw it.
            val contentOps = "q $w 0 0 $h 0 0 cm /Im$i Do Q"
            val contentBytes = contentOps.toByteArray(Charsets.ISO_8859_1)

            recordOffset(contentNum)
            writeStr("$contentNum 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
            writeBytes(contentBytes)
            writeStr("\nendstream\nendobj\n")

            recordOffset(imageNum)
            writeStr(
                "$imageNum 0 obj\n" +
                    "<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${page.jpegBytes.size} >>\nstream\n"
            )
            writeBytes(page.jpegBytes)
            writeStr("\nendstream\nendobj\n")

            recordOffset(pageNum)
            writeStr(
                "$pageNum 0 obj\n" +
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] " +
                    "/Resources << /XObject << /Im$i $imageNum 0 R >> >> " +
                    "/Contents $contentNum 0 R >>\nendobj\n"
            )
        }

        // xref table
        val xrefOffset = buffer.size().toLong()
        writeStr("xref\n0 ${totalObjects + 1}\n")
        writeStr("0000000000 65535 f \n")
        for (objNum in 1..totalObjects) {
            val offset = offsetByObjNum[objNum]
                ?: error("Missing offset for object $objNum — writer bug")
            writeStr(formatXrefOffset(offset) + " 00000 n \n")
        }

        // trailer
        writeStr("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\n")
        writeStr("startxref\n$xrefOffset\n%%EOF")

        outputFile.writeBytes(buffer.toByteArray())
    }

    /** PDF xref offsets must be exactly 10 digits, zero-padded. */
    private fun formatXrefOffset(offset: Long): String {
        val s = offset.toString()
        return "0".repeat((10 - s.length).coerceAtLeast(0)) + s
    }
}

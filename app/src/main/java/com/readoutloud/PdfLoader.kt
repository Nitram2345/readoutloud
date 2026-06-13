package com.readoutloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class TextLine(
    val page: Int,
    val x: Float,
    val y: Float,
    val pageWidth: Float,
    val pageHeight: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val style: String = ""
)

sealed class DocumentData {
    abstract val textLines: List<TextLine>
    data class Pdf(val pages: List<Bitmap>, override val textLines: List<TextLine>) : DocumentData()
    data class Word(override val textLines: List<TextLine>) : DocumentData()
}

suspend fun loadDocument(context: Context, uri: Uri): DocumentData = withContext(Dispatchers.IO) {
    val mimeType = context.contentResolver.getType(uri)
        ?: uri.lastPathSegment?.let { seg ->
            when {
                seg.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                seg.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                seg.endsWith(".epub", ignoreCase = true) -> "application/epub+zip"
                seg.endsWith(".odt", ignoreCase = true) -> "application/vnd.oasis.opendocument.text"
                seg.endsWith(".html", ignoreCase = true) || seg.endsWith(".htm", ignoreCase = true) -> "text/html"
                seg.endsWith(".xhtml", ignoreCase = true) -> "application/xhtml+xml"
                else -> null
            }
        }
    when {
        mimeType?.contains("pdf") == true -> loadPdfData(context, uri)
        mimeType?.contains("wordprocessingml") == true -> DocumentData.Word(extractDocxLines(context, uri))
        mimeType?.contains("epub") == true -> DocumentData.Word(extractEpubLines(context, uri))
        mimeType?.contains("opendocument.text") == true -> DocumentData.Word(extractOdtLines(context, uri))
        mimeType?.contains("html") == true -> DocumentData.Word(extractHtmlLines(context, uri))
        else -> throw IllegalArgumentException("Nicht unterstützt: $mimeType")
    }
}

private suspend fun loadPdfData(context: Context, uri: Uri): DocumentData.Pdf = withContext(Dispatchers.IO) {
    val file = copyToCache(context, uri)
    ensureActive()
    val textLines = extractTextLines(context, uri)
    ensureActive()

    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(fd)
    val screenWidth = context.resources.displayMetrics.widthPixels

    val bitmaps = (0 until renderer.pageCount).map { i ->
        ensureActive()
        val page = renderer.openPage(i)
        val scale = screenWidth.toFloat() / page.width
        val bm = Bitmap.createBitmap(screenWidth, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bm.eraseColor(Color.WHITE)
        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        bm
    }
    renderer.close()
    fd.close()

    DocumentData.Pdf(bitmaps, textLines)
}

private fun copyToCache(context: Context, uri: Uri): File {
    val file = File(context.cacheDir, "current.pdf")
    context.contentResolver.openInputStream(uri)!!.use { it.copyTo(file.outputStream()) }
    return file
}

private suspend fun extractTextLines(context: Context, uri: Uri): List<TextLine> {
    val stream = context.contentResolver.openInputStream(uri)!!
    val doc = PDDocument.load(stream)
    stream.close()
    return try {
        val extractor = TextPositionExtractor()
        for (pageNum in 1..doc.numberOfPages) {
            coroutineContext.ensureActive()
            extractor.setStartPage(pageNum)
            extractor.setEndPage(pageNum)
            extractor.getText(doc)
        }
        extractor.lines
    } finally {
        doc.close()
    }
}

fun buildSpeechFromTap(page: Int, tapXFraction: Float, tapYFraction: Float, textLines: List<TextLine>): List<TextLine> {
    val pageLines = textLines.filter { it.page == page }

    if (pageLines.isEmpty()) {
        return sortByColumnsThenY(textLines.filter { it.page > page }, false)
    }

    val pageWidth = pageLines.first().pageWidth
    val pageHeight = pageLines.first().pageHeight
    val isBottomOrigin = pageLines.first().y > pageLines.last().y

    val tapPdfX = tapXFraction * pageWidth
    val tapPdfY = if (isBottomOrigin) (1f - tapYFraction) * pageHeight else tapYFraction * pageHeight

    // Use only lines in a ±20% y-band around tap for column detection.
    // This excludes spanning headers above and differently-layouted sections below,
    // which would otherwise fill the gaps between narrow columns.
    val yRange = pageHeight * 0.10f
    val localLines = pageLines.filter { Math.abs(it.y - tapPdfY) <= yRange }
    val colBoundaries = buildColumnBoundaries(if (localLines.size >= 4) localLines else pageLines)
    fun column(x: Float) = colBoundaries.indexOfLast { it <= x }
    val tapColumn = column(tapPdfX)

    val colLines = pageLines.filter { column(it.x) == tapColumn }
    val startY = colLines.minByOrNull { Math.abs(it.y - tapPdfY) }?.y ?: tapPdfY

    // Current page: sort with local boundaries for consistency
    val currentPageSorted = pageLines.sortedWith(compareBy(
        { column(it.x) },
        { if (isBottomOrigin) -it.y else it.y }
    ))
    val currentPageFiltered = currentPageSorted.filter { line ->
        when {
            column(line.x) > tapColumn -> true
            column(line.x) == tapColumn ->
                if (isBottomOrigin) line.y <= startY else line.y >= startY
            else -> false
        }
    }

    return currentPageFiltered + sortByColumnsThenY(textLines.filter { it.page > page }, isBottomOrigin)
}

private fun buildColumnBoundaries(lines: List<TextLine>): List<Float> {
    if (lines.isEmpty()) return listOf(-1f)
    val binSize = 20f
    val pageWidth = lines.first().pageWidth
    // 35%-Schwelle: schließt breite Überschriften aus, die Spaltengaps füllen würden
    val narrowLines = lines.filter { it.width < pageWidth * 0.35f }
    val analysisLines = narrowLines.ifEmpty { lines }

    // Mark x to x+binSize only (never more than 2 adjacent bins per segment).
    // Prevents spanning headers from bridging column gaps while still tolerating
    // within-column x-start variation up to ~binSize px.
    val maxBin = (analysisLines.maxOf { it.x } / binSize).toInt() + 2
    val occupied = BooleanArray(maxBin + 1)
    for (line in analysisLines) {
        val from = (line.x / binSize).toInt()
        val to = ((line.x + minOf(line.width, binSize)) / binSize).toInt().coerceAtMost(maxBin)
        for (i in from..to) occupied[i] = true
    }

    // Nur zwischen erstem und letztem besetzten Bin scannen → kein falscher Gap am linken Rand
    val firstOccupied = occupied.indexOfFirst { it }.takeIf { it >= 0 } ?: return listOf(-1f)
    val lastOccupied  = occupied.indexOfLast  { it }

    val boundaries = mutableListOf(-1f)
    var inGap = false
    var gapStart = 0f
    for (i in firstOccupied..lastOccupied) {
        if (!occupied[i] && !inGap) { inGap = true; gapStart = i * binSize }
        else if (occupied[i] && inGap) { boundaries.add((gapStart + i * binSize) / 2f); inGap = false }
    }
    return boundaries
}

private fun sortByColumnsThenY(lines: List<TextLine>, isBottomOrigin: Boolean): List<TextLine> {
    if (lines.isEmpty()) return lines
    // Grenzen pro Seite berechnen – nicht seitenübergreifend mischen
    return lines.groupBy { it.page }
        .entries.sortedBy { it.key }
        .flatMap { (_, pageLines) ->
            val boundaries = buildColumnBoundaries(pageLines)
            fun column(x: Float) = boundaries.indexOfLast { it <= x }
            pageLines.sortedWith(compareBy(
                { column(it.x) },
                { if (isBottomOrigin) -it.y else it.y }
            ))
        }
}

private class TextPositionExtractor : PDFTextStripper() {
    val lines = mutableListOf<TextLine>()
    private var pageHeight = 0f
    private var pageWidth = 0f

    init {
        sortByPosition = true
    }

    override fun startPage(page: PDPage) {
        pageHeight = page.mediaBox.height
        pageWidth = page.mediaBox.width
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty() || text.isBlank()) return

        // Split by large x-gaps to separate merged column text
        var segStart = 0
        var segX = textPositions[0].x
        var segY = textPositions[0].y

        for (i in 1 until textPositions.size) {
            val prev = textPositions[i - 1]
            val curr = textPositions[i]
            val gap = curr.x - (prev.x + prev.width)
            // Column boundary: gap > 5x average char width
            val avgWidth = (prev.width + curr.width) / 2f
            if (gap > avgWidth * 5f) {
                addSegment(textPositions, segStart, i, segX, segY)
                segStart = i
                segX = curr.x
                segY = curr.y
            }
        }
        addSegment(textPositions, segStart, textPositions.size, segX, segY)
    }

    private fun addSegment(positions: List<TextPosition>, from: Int, to: Int, x: Float, y: Float) {
        val segText = positions.subList(from, to).joinToString("") { it.unicode }.trim()
        if (segText.isNotBlank()) {
            val sub = positions.subList(from, to)
            val last = sub.last()
            val segWidth = (last.x + last.width) - x
            val segHeight = sub.maxOf { it.height }
            lines.add(TextLine(currentPageNo - 1, x, y, pageWidth, pageHeight, segWidth, segHeight, segText))
        }
    }
}

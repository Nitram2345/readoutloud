package de.readoutloud

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

fun extractDocxLines(context: Context, uri: Uri): List<TextLine> {
    val paragraphs = mutableListOf<Pair<String, String>>() // text, styleId
    val stream = context.contentResolver.openInputStream(uri)!!
    ZipInputStream(stream).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") {
                parseParagraphs(zis, paragraphs)
                break
            }
            entry = zis.nextEntry
        }
    }
    val total = paragraphs.size.toFloat().coerceAtLeast(1f)
    return paragraphs.mapIndexed { idx, (text, styleId) ->
        TextLine(
            page = 0, x = 0f, y = idx.toFloat(),
            pageWidth = 1f, pageHeight = total,
            width = 1f, height = 1f,
            text = text, style = styleId
        )
    }
}

private fun parseParagraphs(stream: java.io.InputStream, out: MutableList<Pair<String, String>>) {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(stream, "UTF-8")
    val sb = StringBuilder()
    var inParagraph = false
    var inText = false
    var currentStyle = ""
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "w:p"      -> { inParagraph = true; sb.clear(); currentStyle = "" }
                "w:pStyle" -> currentStyle = parser.getAttributeValue(null, "w:val") ?: ""
                "w:t"      -> inText = true
                "w:br"     -> if (inParagraph) sb.append(" ")
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "w:p" -> {
                    val text = sb.toString().trim()
                    if (text.isNotBlank()) out.add(text to currentStyle)
                    inParagraph = false
                    inText = false
                }
                "w:t" -> inText = false
            }
            XmlPullParser.TEXT -> if (inParagraph && inText) sb.append(parser.text)
        }
        event = parser.next()
    }
}

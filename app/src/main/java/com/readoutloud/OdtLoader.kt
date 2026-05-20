package com.readoutloud

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

fun extractOdtLines(context: Context, uri: Uri): List<TextLine> {
    val paragraphs = mutableListOf<Pair<String, String>>()
    val stream = context.contentResolver.openInputStream(uri)!!
    ZipInputStream(stream).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == "content.xml") {
                parseOdtParagraphs(zis, paragraphs)
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

private fun parseOdtParagraphs(stream: java.io.InputStream, out: MutableList<Pair<String, String>>) {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(stream, "UTF-8")
    val sb = StringBuilder()
    var inParagraph = false
    var currentStyle = ""
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "text:p" -> {
                    inParagraph = true
                    sb.clear()
                    currentStyle = ""
                }
                "text:h" -> {
                    inParagraph = true
                    sb.clear()
                    val level = parser.getAttributeValue(null, "text:outline-level")?.toIntOrNull() ?: 1
                    currentStyle = "h${level.coerceIn(1, 4)}"
                }
                "text:line-break", "text:tab" -> if (inParagraph) sb.append(" ")
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "text:p", "text:h" -> {
                    val text = sb.toString().trim()
                    if (text.isNotBlank()) out.add(text to currentStyle)
                    inParagraph = false
                }
            }
            XmlPullParser.TEXT -> if (inParagraph) sb.append(parser.text)
        }
        event = parser.next()
    }
}

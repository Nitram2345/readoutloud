package com.readoutloud

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

fun extractEpubLines(context: Context, uri: Uri): List<TextLine> {
    val entries = mutableMapOf<String, ByteArray>()
    val stream = context.contentResolver.openInputStream(uri)!!
    ZipInputStream(stream).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            entries[entry.name] = zis.readBytes()
            entry = zis.nextEntry
        }
    }

    val containerXml = entries["META-INF/container.xml"]
        ?: return emptyList()
    val opfPath = parseOpfPath(containerXml)
        ?: return emptyList()

    val opfBytes = entries[opfPath] ?: return emptyList()
    val opfDir = opfPath.substringBeforeLast("/", "")
    val (manifest, spine) = parseOpf(opfBytes)

    val paragraphs = mutableListOf<Pair<String, String>>()
    for (idref in spine) {
        val href = manifest[idref] ?: continue
        val entryKey = if (opfDir.isEmpty()) href else "$opfDir/$href"
        val bytes = entries[entryKey] ?: entries[href] ?: continue
        extractXhtmlParagraphs(bytes, paragraphs)
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

private fun parseOpfPath(bytes: ByteArray): String? {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(bytes.inputStream(), "UTF-8")
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
            return parser.getAttributeValue(null, "full-path")
        }
        event = parser.next()
    }
    return null
}

private fun parseOpf(bytes: ByteArray): Pair<Map<String, String>, List<String>> {
    val manifest = mutableMapOf<String, String>() // id → href
    val spine = mutableListOf<String>()            // list of idrefs in order

    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(bytes.inputStream(), "UTF-8")
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name) {
                "item" -> {
                    val id = parser.getAttributeValue(null, "id") ?: ""
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    val mt = parser.getAttributeValue(null, "media-type") ?: ""
                    if (href.isNotEmpty() && (mt.contains("xhtml") || mt.contains("html"))) {
                        manifest[id] = href
                    }
                }
                "itemref" -> {
                    val idref = parser.getAttributeValue(null, "idref") ?: ""
                    if (idref.isNotEmpty()) spine.add(idref)
                }
            }
        }
        event = parser.next()
    }
    return manifest to spine
}

private fun extractXhtmlParagraphs(bytes: ByteArray, out: MutableList<Pair<String, String>>) {
    val doc = Jsoup.parse(bytes.inputStream(), "UTF-8", "")
    for (el in doc.body().select("h1,h2,h3,h4,h5,h6,p,li")) {
        val text = el.text().trim()
        if (text.isBlank()) continue
        val styleId = when (el.tagName()) {
            "h1" -> "h1"
            "h2" -> "h2"
            "h3" -> "h3"
            "h4", "h5", "h6" -> "h4"
            else -> ""
        }
        out.add(text to styleId)
    }
}

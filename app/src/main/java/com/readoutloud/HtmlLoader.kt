package com.readoutloud

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup

fun extractHtmlLines(context: Context, uri: Uri): List<TextLine> {
    val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
    val doc = stream.use { Jsoup.parse(it, null, "") }
    val paragraphs = mutableListOf<Pair<String, String>>()
    for (el in doc.body().select("h1,h2,h3,h4,h5,h6,p,li,td,th,dt,dd")) {
        val text = el.text().trim()
        if (text.isBlank()) continue
        val style = when (el.tagName()) {
            "h1" -> "h1"
            "h2" -> "h2"
            "h3" -> "h3"
            "h4", "h5", "h6" -> "h4"
            else -> ""
        }
        paragraphs.add(text to style)
    }
    val total = paragraphs.size.toFloat().coerceAtLeast(1f)
    return paragraphs.mapIndexed { idx, (text, style) ->
        TextLine(
            page = 0, x = 0f, y = idx.toFloat(),
            pageWidth = 1f, pageHeight = total,
            width = 1f, height = 1f,
            text = text, style = style
        )
    }
}

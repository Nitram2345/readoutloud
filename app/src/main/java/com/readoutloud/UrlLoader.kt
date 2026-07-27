package com.readoutloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.net.URI
import java.util.concurrent.TimeUnit

private const val USER_AGENT = "Mozilla/5.0 (Android) ReadOutLoud"

// OkHttp follows HTTP 3xx by default; explicit here as intent. JS redirects are out of scope.
private val httpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

/** First http(s) URL inside shared text (browsers may share "Title\nURL"). */
fun extractUrl(text: String): String? =
    Regex("""https?://\S+""").find(text)?.value

private fun fetch(url: String): Pair<String, String> {
    val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    httpClient.newCall(req).execute().use { resp ->
        val body = resp.body?.string() ?: ""
        return resp.request.url.toString() to body   // final URL after redirects
    }
}

suspend fun loadUrl(url: String): DocumentData.Word = withContext(Dispatchers.IO) {
    var (finalUrl, html) = fetch(url)

    // One level of meta-refresh (common on consent/Google interstitials).
    val refresh = Jsoup.parse(html, finalUrl)
        .selectFirst("meta[http-equiv=refresh]")?.attr("content")
    val metaUrl = refresh?.let { Regex("(?i)url=(.+)").find(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotBlank() }
    if (metaUrl != null) {
        val resolved = runCatching { URI(finalUrl).resolve(metaUrl).toString() }.getOrNull()
        if (resolved != null && resolved != finalUrl) {
            val r = fetch(resolved); finalUrl = r.first; html = r.second
        }
    }

    val article = Readability4J(finalUrl, html).parse()
    val content = article.articleContent
    val title = article.title?.trim()
    if (content != null && !title.isNullOrBlank()) {
        content.prepend("<h1>${Entities.escape(title)}</h1>")
    }
    var lines = content?.let { extractLinesFromElement(it) } ?: emptyList()
    // Fallback: readability found nothing usable → extract the whole page.
    if (lines.isEmpty()) lines = extractLinesFromElement(Jsoup.parse(html, finalUrl).body())
    if (lines.isEmpty()) throw IllegalStateException("no readable content")

    DocumentData.Word(lines)
}

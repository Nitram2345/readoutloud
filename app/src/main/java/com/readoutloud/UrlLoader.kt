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

// An article page is never this big. Caps what we buffer so a link to a large file can't OOM us.
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

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

/**
 * Cleartext is blocked by default from targetSdk 28 on, so an http:// URL can never load —
 * it fails with "CLEARTEXT communication not permitted" no matter what. Try https instead;
 * nearly every site that still publishes http links redirects there anyway.
 */
private fun httpsUpgrade(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

private fun fetch(url: String): Pair<String, String> {
    val req = Request.Builder().url(httpsUpgrade(url)).header("User-Agent", USER_AGENT).build()
    httpClient.newCall(req).execute().use { resp ->
        val body = resp.body ?: return resp.request.url.toString() to ""

        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
        if (mime != null && !mime.startsWith("text/") && mime != "application/xhtml+xml") {
            throw IllegalStateException("Keine Webseite: $mime")
        }
        // contentLength() is -1 for chunked responses, so also bound what we actually buffer.
        if (body.contentLength() > MAX_BODY_BYTES) throw IllegalStateException("Seite zu groß")
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) throw IllegalStateException("Seite zu groß")

        return resp.request.url.toString() to body.string()   // final URL after redirects
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

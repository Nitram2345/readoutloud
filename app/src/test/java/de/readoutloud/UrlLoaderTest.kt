package de.readoutloud

import net.dankito.readability4j.Readability4J
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlLoaderTest {

    @Test
    fun extractsUrlFromSharedText() {
        assertEquals("https://example.com/a", extractUrl("Cooler Artikel\nhttps://example.com/a"))
        assertEquals("http://x.io", extractUrl("http://x.io"))
        assertNull(extractUrl("kein link hier"))
    }

    @Test
    fun readabilityKeepsArticleDropsChrome() {
        val html = """
            <html><body>
              <nav><a href="#">Home junk</a><a href="#">Menu junk</a></nav>
              <header>Site Header junk</header>
              <article>
                <h1>Titel</h1>
                <p>Erster echter Absatz mit genug Text, um als Inhalt zu zaehlen und nicht weggefiltert zu werden.</p>
                <p>Zweiter echter Absatz, ebenfalls mit ausreichender Textlaenge fuer die Extraktion durch Readability.</p>
              </article>
              <footer>Footer junk copyright 2026</footer>
            </body></html>
        """.trimIndent()
        val article = Readability4J("https://example.com", html).parse()
        val lines = extractLinesFromElement(article.articleContent!!)
        val joined = lines.joinToString(" ") { it.text }
        assertTrue(joined.contains("Erster echter Absatz"))
        assertTrue(joined.contains("Zweiter echter Absatz"))
        assertFalse(joined.contains("Footer junk"))
        assertFalse(joined.contains("Menu junk"))
    }
}

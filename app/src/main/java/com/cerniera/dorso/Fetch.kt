package com.cerniera.dorso

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Turns a shared link into an [Article]: fetch the page, find its article with Readability (the
 * same rules as Firefox's Reader View), and keep only paragraphs, headings and quotations.
 *
 * Signed-in pages (The Athletic, Medium member stories) come through as their public teaser for
 * now; [Result.teaser] says so. Reading them through your own signed-in session is a later step.
 */
object Fetch {
    class Result(val article: Article?, val problem: String?, val teaser: Boolean = false)

    private const val UA = "Mozilla/5.0 (Linux; Android 16; SM-F976B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Mobile Safari/537.36"

    /** The first web link in shared text ("Title https://…" from most apps). */
    fun linkIn(text: String?): String? = text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

    fun article(url: String): Result {
        val (finalUrl, html) = runCatching { download(url) }.getOrElse { return Result(null, "Couldn't reach the page (${it.javaClass.simpleName}: ${it.message ?: "no detail"}).") }
        return parse(finalUrl, html)
    }

    fun parse(url: String, html: String): Result {
        val parsed = runCatching { Readability4J(url, html).parse() }.getOrElse { return Result(null, "Couldn't find an article on that page.") }
        val content: Element = parsed.articleContent ?: return Result(null, "Couldn't find an article on that page.")
        val blocks = blocksOf(content)
        if (blocks.isEmpty()) return Result(null, "Couldn't find any text on that page.")
        val host = runCatching { URL(url).host.removePrefix("www.").removePrefix("m.") }.getOrDefault("")
        val title = parsed.title?.trim().orEmpty().ifBlank { Jsoup.parse(html).title() }.ifBlank { host }
        val a = Article(UUID.randomUUID().toString().take(12), url, title, parsed.byline?.trim().orEmpty(), siteName(host), blocks, System.currentTimeMillis())
        // A paywall's teaser: a few hundred words at most
        return Result(a, null, teaser = a.words < 350)
    }

    private fun download(start: String): Pair<String, String> {
        var url = start
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000; readTimeout = 20_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("redirect with no address")
                url = URL(URL(url), loc).toString(); c.disconnect(); return@repeat
            }
            if (code >= 400) error("the site answered $code")
            val charset = c.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.ifBlank { null } ?: "UTF-8"
            val body = c.inputStream.use { it.readBytes() }.toString(charset(charset))
            c.disconnect()
            return url to body
        }
        error("too many redirects")
    }

    private val KEEP = setOf("p", "h1", "h2", "h3", "h4", "blockquote", "li", "pre")

    private fun blocksOf(root: Element): List<Block> {
        val out = ArrayList<Block>()
        for (e in root.select(KEEP.joinToString(","))) {
            // Text inside a kept parent (a paragraph in a quotation) is read with its parent
            if (e.parents().any { it.tagName() in KEEP }) continue
            val t = e.text().replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) continue
            out += when (e.tagName()) {
                "h1", "h2", "h3", "h4" -> Block(Block.HEAD, t)
                "blockquote" -> Block(Block.QUOTE, t)
                "li" -> Block(Block.PARA, "• $t")
                else -> Block(Block.PARA, t)
            }
        }
        return out
    }

    private fun siteName(host: String) = when {
        host.endsWith("medium.com") -> "Medium"
        host.endsWith("theathletic.com") || host.contains("nytimes.com/athletic") -> "The Athletic"
        host.endsWith("reddit.com") -> "Reddit"
        else -> host
    }
}

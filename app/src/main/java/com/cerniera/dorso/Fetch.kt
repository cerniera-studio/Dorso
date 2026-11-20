package com.cerniera.dorso

import android.content.Context
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

    /** A site that turned the download away (Cloudflare's 403, a rate limit), as opposed to one that isn't there. */
    class Refused(val code: Int) : Exception("the site answered $code")

    /**
     * A plain download first (quick, no JavaScript); if the site refuses it, as Medium does, the
     * same page through [Browser], which passes the check the way Chrome does. Call off the main thread.
     */
    fun article(context: Context, url: String): Result {
        // Signed in to this site: read it as you, through the browser that holds your session
        if (Accounts.signedInFor(context, url)) {
            val viaBrowser = runCatching { Browser.page(context, url) }.map { parse(it.url, it.html) }
            viaBrowser.getOrNull()?.takeIf { it.article != null }?.let { return it }
        }
        val page = runCatching { download(url) }.recoverCatching { e ->
            if (e is Refused && e.code in REFUSALS) Browser.page(context, url).let { it.url to it.html } else throw e
        }
        val (finalUrl, html) = page.getOrElse { return Result(null, problem(it)) }
        return parse(finalUrl, html)
    }

    private val REFUSALS = setOf(401, 403, 429, 503)

    private fun problem(e: Throwable): String = when {
        e is Refused && e.code == 404 -> "That page isn't there (the site answered 404)."
        e is Refused -> "The site turned Dorso away (it answered ${e.code})."
        e is java.net.UnknownHostException -> "Couldn't reach the site: check the connection."
        e is java.net.SocketTimeoutException -> "The site took too long to answer."
        else -> "Couldn't reach the page (${e.message ?: e.javaClass.simpleName})."
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
            if (code >= 400) { c.disconnect(); throw Refused(code) }
            val cs = c.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.ifBlank { null } ?: "UTF-8"
            val body = c.inputStream.use { it.readBytes() }.toString(runCatching { charset(cs) }.getOrDefault(Charsets.UTF_8))
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

package com.cerniera.dorso

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager

/**
 * The sites you've signed into inside Dorso. Signing in happens in [SignInActivity], a browser
 * page of Dorso's own; its cookies live in the app's WebView store, which [Browser] shares, so a page
 * fetched through the hidden browser arrives as your subscription sees it. Nothing about the
 * account is stored by Dorso itself beyond which sites you chose to sign into.
 */
object Accounts {
    class Site(val key: String, val name: String, val note: String, val login: String, val home: String, val hosts: List<String>, val session: List<String>)

    /** The New York Times account also opens The Athletic, which lives at nytimes.com/athletic. */
    val sites = listOf(
        Site("nyt", "The New York Times · The Athletic", "One account for both",
            "https://myaccount.nytimes.com/auth/login", "https://www.nytimes.com/", listOf("nytimes.com", "theathletic.com"),
            listOf("NYT-S")),
        Site("medium", "Medium", "Member-only stories",
            "https://medium.com/m/signin", "https://medium.com/", listOf("medium.com"),
            listOf("sid")),
    )

    fun byKey(k: String) = sites.firstOrNull { it.key == k }

    private fun host(url: String) = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    fun siteFor(url: String): Site? { val h = host(url); return sites.firstOrNull { s -> s.hosts.any { h == it || h.endsWith(".$it") } } }

    private fun prefs(c: Context) = c.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    /** Sites signed into by hand ("Another site"), by host. */
    fun others(c: Context): Set<String> = prefs(c).getStringSet("others", emptySet()).orEmpty()

    fun addOther(c: Context, url: String) {
        val h = host(url).ifBlank { return }
        if (siteFor(url) != null) return
        prefs(c).edit().putStringSet("others", others(c) + h).apply()
    }

    private fun cookieNames(url: String): List<String> =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }

    /**
     * Whether a session cookie for [s] is present. The cookie names are each site's own and could
     * change; if one does, the status here goes wrong but reading still uses whatever is signed in.
     */
    fun signedIn(s: Site): Boolean = cookieNames(s.home).let { names -> s.session.any { it in names } }

    /** Whether to read [url] through the signed-in browser: a known site you're signed into, or one you added. */
    fun signedInFor(c: Context, url: String): Boolean {
        siteFor(url)?.let { return signedIn(it) }
        val h = host(url)
        return others(c).any { h == it || h.endsWith(".$it") }
    }

    /** Signs out of [s] in Dorso: its cookies are expired. */
    fun signOut(s: Site) {
        val cm = CookieManager.getInstance()
        for (h in s.hosts) {
            val url = "https://$h/"
            for (n in cookieNames(url)) {
                cm.setCookie(url, "$n=; Max-Age=0; Path=/")
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=.$h")
            }
        }
        cm.flush()
    }

    fun signOutOther(c: Context, host: String) {
        val cm = CookieManager.getInstance(); val url = "https://$host/"
        for (n in cookieNames(url)) { cm.setCookie(url, "$n=; Max-Age=0; Path=/"); cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=.$host") }
        cm.flush()
        prefs(c).edit().putStringSet("others", others(c) - host).apply()
    }
}

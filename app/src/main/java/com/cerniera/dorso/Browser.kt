package com.cerniera.dorso

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/**
 * A hidden browser, for sites that turn away a plain download. Medium (and others behind
 * Cloudflare) answer 403 to anything that can't run their JavaScript check, whatever it calls
 * itself; a WebView runs it the way Chrome does, then its finished page goes to Readability as usual.
 *
 * Never shown and never attached to a window. Its cookies stay in the app's WebView store, so a
 * site that has let Dorso through once usually lets it straight through next time. This is also
 * the ground for reading signed-in pages later.
 */
object Browser {
    private const val WAIT_MS = 30_000L
    private const val POLL_MS = 700L
    private const val SETTLE_MS = 1400L

    // Ready when the page has real text and isn't a "Just a moment…" check
    private const val READY_JS = """(function(){
        var t=(document.title||'').toLowerCase();
        if(t.indexOf('just a moment')>=0||t.indexOf('attention required')>=0) return '';
        if(document.querySelector('#challenge-form,#cf-challenge-running,.cf-browser-verification')) return '';
        var b=document.body; if(!b||(b.innerText||'').length<600) return '';
        return location.href+'\n'+document.documentElement.outerHTML;
    })()"""

    class Result(val url: String, val html: String)

    /** Loads [url] in a hidden WebView and returns the page once it has settled. Call off the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun page(context: Context, url: String): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Browser.page blocks; call it off the main thread" }
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var out: Result? = null
        var failed: String? = null
        var view: WebView? = null

        main.post {
            val w = WebView(context.applicationContext)
            view = w
            CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(w, true) }
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                // Look like Chrome, not an app's WebView: some sites serve their app-install wall to "wv"
                userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
            }
            fun poll() {
                if (done.count == 0L) return
                w.evaluateJavascript(READY_JS) { raw ->
                    val s = runCatching { JSONArray("[$raw]").optString(0, "") }.getOrDefault("")
                    if (s.isNotEmpty()) {
                        val first = Result(s.substringBefore('\n'), s.substringAfter('\n'))
                        // Give the page a moment more: a subscriber's text and late scripts can still be arriving
                        main.postDelayed({
                            if (done.count == 0L) return@postDelayed
                            w.evaluateJavascript(READY_JS) { again ->
                                val t = runCatching { JSONArray("[$again]").optString(0, "") }.getOrDefault("")
                                // The fuller reading, but only of the same page: a site may move on to another story
                                val same = t.substringBefore('\n') == first.url
                                out = if (same && t.length > s.length) Result(t.substringBefore('\n'), t.substringAfter('\n')) else first
                                done.countDown()
                            }
                        }, SETTLE_MS)
                    } else main.postDelayed({ poll() }, POLL_MS)
                }
            }
            w.webViewClient = object : WebViewClient() {
                private var polling = false
                override fun onPageFinished(v: WebView, u: String) {
                    if (!polling) { polling = true; poll() }
                }
                override fun onReceivedHttpError(v: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                    // A challenge page arrives as 403 and then clears itself, so only note it
                    if (req.isForMainFrame) failed = "the site answered ${res.statusCode}"
                }
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest) = false
            }
            w.loadUrl(url)
        }

        val settled = done.await(WAIT_MS, TimeUnit.MILLISECONDS)
        main.post { view?.run { stopLoading(); destroy() } }
        CookieManager.getInstance().flush()
        return out ?: error(if (settled) "no page" else "the page never finished loading" + (failed?.let { " ($it)" } ?: ""))
    }
}

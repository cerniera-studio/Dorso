package com.cerniera.dorso

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.text

/**
 * Signing in, inside Dorso: a plain browser page on the site's own sign-in, with a slim bar above it
 * (the site's name, an address line, Done). Whatever you sign into here, Dorso's hidden browser then
 * reads with, so a subscriber's article comes through whole.
 *
 * Google and Apple buttons refuse to work inside an app's browser, so use the site's email sign-in.
 * Where a site emails a sign-in link (Medium does), long-press the link in the email, copy it, and
 * paste it into the address line here: opened in Chrome, it would sign Chrome in, not Dorso.
 */
class SignInActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var progress: View

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Dorso")) return
        Kit.follow(this)
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        val site = intent.getStringExtra(SITE)?.let { Accounts.byKey(it) }
        val start = site?.login ?: intent.getStringExtra(URL) ?: "about:blank"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.GROUND) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(10), px(8), px(4))
        }
        bar.addView(text(site?.name ?: "Sign in", 15f, Kit.INK, 500).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(text("Done", 15f, Kit.CHAMPAGNE, 600).apply {
            setPadding(px(14), px(10), px(14), px(10))
            setOnClickListener { finishSigningIn() }
        })
        root.addView(bar)
        address = EditText(this).apply {
            setSingleLine(); textSize = 13f; setTextColor(Kit.MUTED); setHintTextColor(Kit.MUTED)
            hint = "Paste a sign-in link, or an address"
            background = null; setPadding(px(18), px(2), px(18), px(8))
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
        }
        root.addView(address)
        root.addView(text(if (site != null) "Use the email sign-in: Google and Apple buttons don't work inside an app. An emailed sign-in link can be pasted above."
                          else "Sign in as you would in a browser. Dorso will then read this site as you.", 12f, Kit.MUTED).apply {
            setPadding(px(18), 0, px(18), px(8)); setLineSpacing(0f, 1.2f)
        })
        progress = View(this).apply { setBackgroundColor(Kit.CHAMPAGNE); pivotX = 0f; scaleX = 0f }
        root.addView(progress, LinearLayout.LayoutParams(-1, px(2)))

        web = WebView(this)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+ """), "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.scheme.orEmpty().startsWith("http")
            override fun onPageStarted(v: WebView, url: String, icon: Bitmap?) { if (!address.hasFocus() && url.startsWith("http")) address.setText(url) }
            override fun onPageFinished(v: WebView, url: String) { CookieManager.getInstance().flush() }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                progress.animate().scaleX(p / 100f).alpha(if (p >= 100) 0f else 1f).setDuration(Kit.QUICK).start()
            }
        }
        root.addView(FrameLayout(this).apply { addView(web) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (saved == null) web.loadUrl(start) else web.restoreState(saved)
        if (start == "about:blank") { address.setText(""); address.requestFocus() }
    }

    private fun go(s: String) {
        val t = s.trim()
        val url = Fetch.linkIn(t) ?: if (t.contains('.') && !t.contains(' ')) "https://$t" else return
        address.clearFocus()
        web.loadUrl(url)
    }

    private fun finishSigningIn() {
        CookieManager.getInstance().flush()
        if (intent.getStringExtra(SITE) == null) web.url?.let { Accounts.addOther(this, it) }
        finish()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Back walks back through the site first")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finishSigningIn() }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }

    companion object {
        const val SITE = "site"; const val URL = "url"
        fun open(c: Context, site: String? = null, url: String? = null) =
            c.startActivity(Intent(c, SignInActivity::class.java).putExtra(SITE, site).putExtra(URL, url))
    }
}

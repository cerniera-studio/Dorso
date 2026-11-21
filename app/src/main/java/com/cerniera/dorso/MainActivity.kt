package com.cerniera.dorso

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Screen
import com.cerniera.kit.Sheet
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.field
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.text

/**
 * The shelf: the reads you have shared to Dorso, newest or most recently opened first, each with
 * where you are in it. Share a link from any app to add one, or paste it here.
 *
 * One column in every shape (the Kit's Screen keeps it to a comfortable measure on the main
 * screen); the reading itself happens in [ReaderActivity].
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var screen: Screen
    private lateinit var shelf: LinearLayout
    private lateinit var note: TextView
    private lateinit var accounts: LinearLayout
    private var busy = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Dorso")) return
        Kit.follow(this)
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        window.decorView.setBackgroundColor(Kit.GROUND)

        screen = Screen(this, "Dorso", "Long reads, set as a book")
        screen.add(text("Share a long read to Dorso from Medium, The Athletic, Reddit or your browser, then open the phone: the crease is the spine.", 14.5f, Kit.MUTED).apply {
            setPadding(px(4), px(8), 0, px(14))
        })

        shelf = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.section("On the shelf")
        screen.add(shelf)

        note = text("", 13.5f, Kit.MUTED).apply { setPadding(px(4), px(10), 0, 0); visibility = View.GONE }
        screen.section("Add a link")
        val link = field("Paste a link")
        screen.add(link)
        screen.add(primary("Save to the shelf") { save(link.text.toString()); link.setText("") }, px(12))
        screen.add(note)

        accounts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.section("Signed in")
        screen.add(accounts)
        screen.add(text("Sign in to a site you subscribe to and Dorso reads its full articles as you. Your sign-in stays in Dorso on this phone.", 12.5f, Kit.MUTED).apply {
            setPadding(px(4), px(10), 0, 0)
        })

        screen.section("Doctrine")
        screen.add(doctrineCard(DOCTRINE_NOTES))

        setContentView(screen.root)
        seedOnce()
        intent?.let { handle(it) }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handle(i) }

    override fun onResume() {
        super.onResume()
        if (!::shelf.isInitialized) return
        fill()
        fillAccounts()
    }

    private fun handle(i: Intent) {
        if (i.action == Intent.ACTION_SEND) {
            save(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            return
        }
        Nightstand.seatFrom(this, i)
        // For the emulator walk: draw the nightstand at each of a matrix of sizes (dp) in several
        // finishes over a wallpaper-like ground, into files/nightstand-<finish>-<w>x<h>.png
        i.getStringExtra("nightstand")?.let { spec ->
            val wall = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).also { b ->
                val g = Canvas(b)
                g.drawRect(0f, 0f, 240f, 240f, Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 240f, 240f,
                    intArrayOf(0xFF3A4B57.toInt(), 0xFF7A6A52.toInt(), 0xFF1E2A26.toInt()), null, android.graphics.Shader.TileMode.CLAMP) })
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; color = 0x33FFFFFF }
                var x = -240f; while (x < 240f) { g.drawLine(x, 240f, x + 240f, 0f, band); x += 28f }
            }
            val ground = com.cerniera.kit.Ground.of(wall, 0, true)
            val a = Nightstand.current(this)
            for (f in listOf(com.cerniera.kit.Finish.VETRO, com.cerniera.kit.Finish.CARBONIO, com.cerniera.kit.Finish.AVORIO)) {
                for (sz in spec.split("+")) {
                    val (w, h) = sz.split("x").map { it.toFloat() }
                    val b = Nightstand.paint(this, w, h, resources.displayMetrics.density, WidgetLook(f, 24f), ground, a)
                    java.io.File(filesDir, "nightstand-${f.name.lowercase()}-${w.toInt()}x${h.toInt()}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            Nightstand.refresh(this)
            i.removeExtra("nightstand")
        }
        // For the emulator walk: a sign-in page, by site
        i.getStringExtra("signin")?.let { SignInActivity.open(this, site = it); i.removeExtra("signin"); return }
        // For the emulator walk: open the sample straight into the reader
        if (i.getStringExtra("open") == "sample") Store.all(this).firstOrNull { it.url == SAMPLE_URL }?.let { a ->
            i.getStringExtra("at")?.toIntOrNull()?.let { a.offset = it; Store.put(this, a) }
            ReaderActivity.open(this, a.id)
        }
    }

    /** The sample read, so the shelf is never empty on first open. */
    private fun seedOnce() {
        val p = getSharedPreferences("dorso", MODE_PRIVATE)
        if (p.getBoolean("seeded", false)) return
        Store.put(this, sample())
        p.edit().putBoolean("seeded", true).apply()
    }

    private fun sample(): Article {
        val raw = assets.open("sample.txt").bufferedReader().readText()
        var title = "The spine"; var byline = ""
        val blocks = ArrayList<Block>()
        raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, l ->
            when {
                l.startsWith("# ") -> title = l.removePrefix("# ")
                i == 1 && l.startsWith("## ") -> byline = l.removePrefix("## ")
                l.startsWith("## ") -> blocks += Block(Block.HEAD, l.removePrefix("## "))
                else -> blocks += Block(Block.PARA, l)
            }
        }
        return Article("sample", SAMPLE_URL, title, byline, "Dorso", blocks, 0L)
    }

    private fun save(shared: String) {
        val url = Fetch.linkIn(shared) ?: run { say("That doesn't look like a link."); return }
        val had = Store.byUrl(this, url)
        // Already on the shelf whole: open it. Only a preview: fetch again (perhaps you've signed in since)
        if (had != null && (had.words >= 350 || !url.startsWith("http"))) { ReaderActivity.open(this, had.id); return }
        fetch(url, had)
    }

    private fun fetch(url: String, had: Article?) {
        if (busy) return
        busy = true
        say("Fetching the page…")
        Thread {
            val r = Fetch.article(this, url)
            main.post {
                busy = false
                val a = r.article
                if (a == null) { say(r.problem ?: "Couldn't read that page."); return@post }
                // A second fetch replaces the preview, keeping its place on the shelf
                val kept = if (had != null) a.copy(id = had.id, savedAt = had.savedAt) else a
                if (had != null && r.teaser && had.words >= a.words) { say(teaserNote(url, had.words)); return@post }
                Store.put(this, kept)
                Nightstand.refresh(this)
                say(if (r.teaser) teaserNote(url, a.words) else "Saved: ${a.title}.")
                fill()
                if (!r.teaser) ReaderActivity.open(this, kept.id)
            }
        }.start()
    }

    private fun teaserNote(url: String, words: Int): String {
        val site = Accounts.siteFor(url)
        return when {
            site != null && !Accounts.signedIn(site) -> "Saved, but only $words words came through: ${site.name.substringBefore(" ·")} shows the rest to subscribers. Sign in below, then tap the read on the shelf."
            Accounts.signedInFor(this, url) -> "Saved, but only $words words came through even signed in: this one may need a subscription the account doesn't have."
            else -> "Saved, but only $words words came through: the site shows the rest to subscribers. If you have one, sign in below (Another site), then tap the read on the shelf."
        }
    }

    private fun fillAccounts() {
        accounts.removeAllViews()
        val card = Card(this)
        for (s in Accounts.sites) {
            val on = Accounts.signedIn(s)
            card.row(Row(this, s.name, if (on) "Signed in · hold to sign out" else "${s.note} · tap to sign in", null) {
                SignInActivity.open(this, site = s.key)
            }.apply {
                setOnLongClickListener {
                    if (on) Sheet.show(this@MainActivity, s.name, action = "Sign out" to { sh: Sheet -> Accounts.signOut(s); sh.dismiss(); fillAccounts() }) { sh ->
                        sh.body.addView(text("Sign out of ${s.name.substringBefore(" ·")} in Dorso? Reads already on the shelf stay.", 14.5f, Kit.MUTED))
                    }
                    true
                }
            })
        }
        for (h in Accounts.others(this).sorted()) {
            card.row(Row(this, h, "Signed in · hold to sign out", null) { SignInActivity.open(this, url = "https://$h/") }.apply {
                setOnLongClickListener {
                    Sheet.show(this@MainActivity, h, action = "Sign out" to { sh: Sheet -> Accounts.signOutOther(this@MainActivity, h); sh.dismiss(); fillAccounts() }) { sh ->
                        sh.body.addView(text("Sign out of $h in Dorso?", 14.5f, Kit.MUTED))
                    }
                    true
                }
            })
        }
        card.row(Row(this, "Another site", "Any site you subscribe to", null) { SignInActivity.open(this) })
        accounts.addView(card)
    }

    private fun say(s: String) { note.text = s; note.visibility = View.VISIBLE }

    private fun fill() {
        shelf.removeAllViews()
        val reads = Store.all(this)
        if (reads.isEmpty()) {
            shelf.addView(text("Nothing here yet.", 14f, Kit.MUTED).apply { setPadding(px(4), px(6), 0, px(6)) })
            return
        }
        val card = Card(this)
        for (a in reads) {
            val preview = a.url.startsWith("http") && a.words < 350
            val where = when {
                preview -> if (Accounts.signedInFor(this, a.url)) "preview · tap to fetch in full" else "preview only"
                a.progress >= .99f -> "finished"
                a.progress > 0f -> "${(a.progress * 100).toInt()}% read"
                else -> "not started"
            }
            val sub = listOf(a.site, "${a.minutes} min", where).filter { it.isNotBlank() }.joinToString(" · ")
            card.row(Row(this, a.title, sub, null) {
                if (preview && Accounts.signedInFor(this, a.url)) fetch(a.url, a) else ReaderActivity.open(this, a.id)
            }.apply {
                setOnLongClickListener { remove(a); true }
            })
        }
        shelf.addView(card)
    }

    private fun remove(a: Article) {
        Sheet.show(this, a.title, action = "Remove" to { s: Sheet -> Store.remove(this, a.id); s.dismiss(); fill() }) { s ->
            s.body.addView(text("Take this read off the shelf? Your place in it goes too.", 14.5f, Kit.MUTED))
        }
    }

    companion object {
        const val SAMPLE_URL = "dorso://sample"
        val DOCTRINE_NOTES = listOf(
            "The monolithic membrane" to "The spread is one sheet folded at the spine: a turn carries both pages together, and the arriving page follows the leaving one a little behind.",
            "Concentrato" to "Turn quickly and the running heads and page numbers fall away, leaving only the text; they return when you stop.",
            "Giulietta" to "No toolbar. Touch the top edge for type size and the shelf; touch the spine to close the book.",
            "Every shape" to "One page on the upright cover; two short pages on the turned cover; one page each side of the crease when open, the gutter wider than the crease and the camera hole kept clear.",
        )
    }
}

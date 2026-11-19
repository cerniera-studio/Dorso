package com.cerniera.dorso

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
    }

    private fun handle(i: Intent) {
        if (i.action == Intent.ACTION_SEND) {
            save(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            return
        }
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
        Store.byUrl(this, url)?.let { ReaderActivity.open(this, it.id); return }
        if (busy) return
        busy = true
        say("Fetching the page…")
        Thread {
            val r = Fetch.article(url)
            main.post {
                busy = false
                val a = r.article
                if (a == null) { say(r.problem ?: "Couldn't read that page."); return@post }
                Store.put(this, a)
                say(if (r.teaser) "Saved, but only ${a.words} words came through: the page probably needs you signed in. Reading signed-in pages comes in a later build." else "Saved: ${a.title}.")
                fill()
                if (!r.teaser) ReaderActivity.open(this, a.id)
            }
        }.start()
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
            val where = when {
                a.progress >= .99f -> "finished"
                a.progress > 0f -> "${(a.progress * 100).toInt()}% read"
                else -> "not started"
            }
            val sub = listOf(a.site, "${a.minutes} min", where).filter { it.isNotBlank() }.joinToString(" · ")
            card.row(Row(this, a.title, sub, null) { ReaderActivity.open(this, a.id) }.apply {
                setOnLongClickListener { remove(a); true }
            })
        }
        shelf.addView(card)
    }

    private fun remove(a: Article) {
        Sheet.show(this, a.title, action = "Remove" to { s -> Store.remove(this, a.id); s.dismiss(); fill() }) { s ->
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

package com.cerniera.dorso

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.cerniera.kit.Card
import com.cerniera.kit.ChoiceList
import com.cerniera.kit.eyebrow
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.pressable
import com.cerniera.kit.px
import com.cerniera.kit.Row
import com.cerniera.kit.Sheet
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import com.cerniera.kit.withAlpha

/**
 * The reader: the spread full screen, the bars out of the way (a swipe from the edge brings them
 * back), the crease read from Jetpack WindowManager and the camera hole from the display cut-out.
 *
 * Giulietta's trim: touch the top edge and a slim bar rises with smaller, larger, sound and the shelf,
 * then sinks after 3.2 s untouched.
 */
class ReaderActivity : Activity() {
    private lateinit var reader: ReaderView
    private lateinit var trim: android.widget.HorizontalScrollView
    private lateinit var trimRow: LinearLayout
    /** The selection's bar: look it up, search it, mark it, copy it. */
    private lateinit var bar: LinearLayout
    private lateinit var root: FrameLayout
    private var article: Article? = null
    private var tracker: WindowInfoTrackerCallbackAdapter? = null
    private val onLayout = androidx.core.util.Consumer<WindowLayoutInfo> { info -> posture(info) }
    private val hideTrim = Runnable { showTrim(false) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Dorso")) return
        Stock.load(this); Stock.apply(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0; window.navigationBarColor = 0
        val id = intent.getStringExtra(EXTRA_ID)
        val a = id?.let { Store.get(this, it) } ?: run { finish(); return }
        article = a
        a.openedAt = System.currentTimeMillis(); Store.put(this, a)
        Nightstand.seatFrom(this, intent)

        paper = Paper(this).apply { on = prefs().getBoolean("sound", true) }
        reader = ReaderView(this).apply {
            typeStep = prefs().getInt("type", 2)
            paper = this@ReaderActivity.paper
            tapTurns = prefs().getBoolean("tapTurns", false)
            onMoved = { off, p -> article?.let { it.offset = off; it.progress = p; Store.put(this@ReaderActivity, it) } }
            onTopEdge = { showTrim(true) }
            onSpine = { shelf() }
            onMiddle = { showTrim(trim.visibility != View.VISIBLE) }
            onMarks = { article?.let { Store.put(this@ReaderActivity, it) } }
            onNotes = { article?.let { Store.put(this@ReaderActivity, it) } }
            onSelection = { sel -> showBar(sel) }
            onCover = { article?.let { Store.put(this@ReaderActivity, it) } }
            onShutTap = { hint() }
        }
        trimRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Kit.RAISED, px(22).toFloat(), Kit.HAIRLINE, px(1))
            setPadding(px(6), px(4), px(6), px(4))
            addView(button("A−") { size(-1) })
            addView(button("A+") { size(1) })
            addView(button("Paper") { showPaper() })
            addView(button(if (paper.on) "Sound" else "Silent") { tapped ->
                paper.on = !paper.on
                prefs().edit().putBoolean("sound", paper.on).apply()
                (tapped as android.widget.TextView).text = if (paper.on) "Sound" else "Silent"
                if (paper.on) paper.turn(false)
            })
            addView(button("Marks") { showMarks() })
            addView(button(if (reader.tapTurns) "Tap to turn: on" else "Tap to turn: off") { tapped ->
                reader.tapTurns = !reader.tapTurns
                prefs().edit().putBoolean("tapTurns", reader.tapTurns).apply()
                (tapped as TextView).text = if (reader.tapTurns) "Tap to turn: on" else "Tap to turn: off"
            })
            addView(button("Shelf") { shelf() })
        }
        // In a narrow window the trim scrolls sideways rather than losing its ends
        trim = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false; setPadding(px(10), px(8), px(10), px(12))
            visibility = View.GONE
            addView(trimRow)
        }
        trimRow.elevation = px(6).toFloat()
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(4), px(2), px(4), px(2))
            elevation = px(8).toFloat()
            visibility = View.GONE
        }
        val root = FrameLayout(this)
        root.addView(reader, FrameLayout.LayoutParams(-1, -1))
        root.addView(trim, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(6) })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        this.root = root
        shutHint = text("UNFOLD TO READ  ·  SWIPE FOR ANOTHER COVER", 10.5f, Kit.Fixed.IVORY, 500).apply {
            letterSpacing = .2f; gravity = Gravity.CENTER
            setPadding(px(16), px(9), px(16), px(9))
            background = rounded(withAlpha(0x000000, .55f), px(18).toFloat())
            alpha = 0f
        }
        root.addView(shutHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(28) })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.displayCutout?.boundingRects.orEmpty()
            reader.setCutouts(cut.map { Rect(it) })
            (trim.layoutParams as FrameLayout.LayoutParams).topMargin = px(6) + (insets.displayCutout?.safeInsetTop ?: 0)
            insets
        }
        setContentView(root)
        // For the emulator walk: photograph the back swing (debug builds only)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            frames = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { reader.swingFrames(filesDir) }
            }
            registerReceiver(frames, android.content.IntentFilter("com.cerniera.dorso.SWING_FRAMES"), Context.RECEIVER_EXPORTED)
        }
        // Only once the window has its content: before that the decor view doesn't exist yet
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        reader.show(a)
    }

    private lateinit var paper: Paper
    private lateinit var shutHint: TextView

    /** On the shut book: unfold to read, swipe for another cover. Rises, waits, sinks. */
    private fun hint() {
        shutHint.animate().cancel()
        shutHint.translationY = px(10).toFloat()
        shutHint.animate().alpha(1f).translationY(0f).setDuration(Kit.SETTLE).setInterpolator(Kit.ease).withEndAction {
            shutHint.animate().alpha(0f).setStartDelay(2400).setDuration(Kit.GLIDE).start()
        }.start()
    }

    private var frames: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        frames?.let { runCatching { unregisterReceiver(it) } }
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    /** The folded pages, each with its opening words; tap one to turn to it. */
    private fun showMarks() {
        val a = article ?: return
        Sheet.show(this, "Marks") { sh ->
            if (a.marks.isEmpty() && a.notes.isEmpty()) {
                sh.body.addView(text("Nothing marked yet. Take a page's top corner and fold it down to mark the page; tap the fold to smooth it out. Press and hold a word to select it, then highlight or underline the passage.", 14.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.3f) })
                return@show
            }
            if (a.marks.isNotEmpty()) {
                sh.body.addView(eyebrow("Folded pages"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
                val card = Card(this)
                for (m in a.marks) {
                    val words = reader.wordsAt(m.offset)
                    val pct = (100 * reader.fractionAt(m.offset)).toInt()
                    card.row(Row(this, words, "$pct% of the way in", null) { sh.dismiss(); reader.goTo(m.offset) })
                }
                sh.body.addView(card)
            }
            if (a.notes.isNotEmpty()) {
                sh.body.addView(eyebrow("Highlights and underlines"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8); topMargin = if (a.marks.isEmpty()) 0 else px(22) })
                val card = Card(this)
                for (n in a.notes) {
                    val pct = (100 * reader.fractionAt(n.start)).toInt()
                    val kind = if (n.kind == Note.HIGHLIGHT) "Highlighted" else "Underlined"
                    card.row(Row(this, "“" + reader.passage(n.start, n.end, 140) + "”", "$kind · $pct% of the way in", null) { sh.dismiss(); reader.goTo(n.start) })
                }
                sh.body.addView(card)
            }
        }
    }

    private fun prefs() = getSharedPreferences("dorso", Context.MODE_PRIVATE)

    // ---- Paper

    /** The paper the book is printed on: night, sepia, white, or the phone's light. */
    private fun showPaper() {
        val tones = Stock.Tone.values()
        val phoneDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        Sheet.show(this, "Paper") { sh ->
            sh.body.addView(ChoiceList(this, tones.map { t ->
                Triple(t.title, t.detail, { c: android.graphics.Canvas, r: android.graphics.RectF -> samplePage(c, r, Stock.sample(t, phoneDark)) })
            }, tones.indexOf(Stock.tone)) { i ->
                Stock.save(this, tones[i])
                setStock()
                reader.postDelayed({ sh.dismiss() }, 320)
            })
        }
    }

    /** A swatch: a scrap of the page in that stock, a few lines of type and a capital. */
    private fun samplePage(c: android.graphics.Canvas, r: android.graphics.RectF, colours: IntArray) {
        c.drawColor(colours[0])
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colours[1]; typeface = Kit.display(this@ReaderActivity); textSize = r.height() * .52f
        }
        c.drawText("Aa", r.left + r.width() * .14f, r.top + r.height() * .62f, p)
        p.strokeWidth = px(1.5f).toFloat(); p.color = withAlpha(colours[1] and 0xFFFFFF, .42f)
        val x0 = r.left + r.width() * .58f; val x1 = r.right - r.width() * .1f
        for (k in 0..3) {
            val y = r.top + r.height() * (.3f + k * .14f)
            c.drawLine(x0, y, if (k == 3) x0 + (x1 - x0) * .55f else x1, y, p)
        }
    }

    /** Sets the page in the chosen stock, keeping your place, and repaints the trim to match. */
    private fun setStock() {
        val wasDark = Kit.dark
        Stock.apply(this)
        article?.let { it.offset = reader.currentOffset(); reader.show(it) }
        if (wasDark != Kit.dark) paintTrim()
    }

    private fun paintTrim() {
        trimRow.background = rounded(Kit.RAISED, px(22).toFloat(), Kit.HAIRLINE, px(1))
        for (i in 0 until trimRow.childCount) (trimRow.getChildAt(i) as? TextView)?.setTextColor(Kit.INK)
    }

    // ---- The selection

    private var selection: ReaderView.Selection? = null

    /** The bar over a selection: Define (one word), Search, Highlight, Underline, Copy; on a marked passage, Remove. */
    private fun showBar(sel: ReaderView.Selection?) {
        selection = sel
        if (sel == null) {
            if (bar.visibility == View.VISIBLE) bar.animate().alpha(0f).setDuration(Kit.QUICK).withEndAction { bar.visibility = View.GONE }.start()
            return
        }
        showTrim(false)
        bar.removeAllViews()
        bar.background = rounded(Kit.RAISED, px(22).toFloat(), Kit.HAIRLINE, px(1))
        fun add(label: String, onTap: () -> Unit) = bar.addView(text(label, 14.5f, Kit.INK, 500).apply {
            setPadding(px(14), px(11), px(14), px(11))
            pressable()
            setOnClickListener { onTap() }
        })
        if (Lookup.isWord(sel.text)) add("Define") { define(sel.text); reader.clearSelection() }
        add("Search") { search(sel.text); reader.clearSelection() }
        val note = sel.note
        if (note == null) {
            add("Highlight") { reader.markSelection(Note.HIGHLIGHT) }
            add("Underline") { reader.markSelection(Note.UNDERLINE) }
        } else {
            add(if (note.kind == Note.HIGHLIGHT) "Underline" else "Highlight") { reader.markSelection(if (note.kind == Note.HIGHLIGHT) Note.UNDERLINE else Note.HIGHLIGHT) }
            add("Remove") { reader.removeNote(note) }
        }
        add("Copy") {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Dorso", sel.text))
            reader.clearSelection()
        }
        // Above the selection if there's room, else beneath its handles; never off the window
        bar.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val bw = bar.measuredWidth; val bh = bar.measuredHeight
        val m = px(12).toFloat()
        val x = (sel.box.centerX() - bw / 2f).coerceIn(m, (root.width - bw - m).coerceAtLeast(m))
        var y = sel.box.top - bh - px(12)
        if (y < m + px(24)) y = sel.box.bottom + px(30)
        bar.translationX = x; bar.translationY = y.coerceAtMost(root.height - bh - m)
        if (bar.visibility != View.VISIBLE) { bar.visibility = View.VISIBLE; bar.alpha = 0f; bar.scaleX = .96f; bar.scaleY = .96f }
        bar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Kit.QUICK).setInterpolator(Kit.ease).start()
    }

    private fun search(q: String) {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)
        runCatching { startActivity(web) }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(q)))) }
        }
    }

    /** What a word means, on a sheet over the page, so you never leave the book; the web is one tap further. */
    private fun define(selected: String) {
        val word = Lookup.headword(selected)
        Sheet.show(this, word, action = "Search" to { sh -> sh.dismiss(); search("define $word") }) { sh ->
            val wait = text("Looking it up…", 14.5f, Kit.MUTED)
            sh.body.addView(wait)
            Thread {
                val got = runCatching { Lookup.define(selected) }
                runOnUiThread {
                    sh.body.removeView(wait)
                    val entry = got.getOrNull()
                    if (entry == null) {
                        val why = if (got.isFailure) "The dictionary couldn't be reached. Search the web for it instead?" else "No entry for “$word” in the dictionary. It may be a name, or too new; Search looks further."
                        sh.body.addView(text(why, 14.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.3f) })
                        return@runOnUiThread
                    }
                    entry.phonetic?.let { sh.body.addView(text(it, 15f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14) }) }
                    for ((k, sense) in entry.senses.withIndex()) {
                        sh.body.addView(eyebrow(sense.partOfSpeech), LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (k == 0) 0 else px(18); bottomMargin = px(6) })
                        for ((i, d) in sense.definitions.withIndex()) {
                            val line = android.text.SpannableStringBuilder("${i + 1}.  ").append(d.first)
                            d.second?.let { ex ->
                                val s0 = line.length
                                line.append("\n“").append(ex).append("”")
                                line.setSpan(android.text.style.ForegroundColorSpan(Kit.MUTED), s0, line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            sh.body.addView(text(line, 15.5f, Kit.INK).apply { setLineSpacing(0f, 1.3f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
                        }
                    }
                    sh.body.addView(text("Wiktionary, via the Free Dictionary API", 11.5f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
                }
            }.start()
        }
    }

    private fun button(label: String, onTap: (View) -> Unit) = text(label, 15f, Kit.INK, 500).apply {
        setPadding(px(18), px(10), px(18), px(10))
        pressable()
        setOnClickListener { onTap(it); poke() }
    }

    private fun size(d: Int) {
        reader.typeStep = reader.typeStep + d
        prefs().edit().putInt("type", reader.typeStep).apply()
        article?.let { it.offset = reader.currentOffset(); Store.put(this, it) }
    }

    private fun showTrim(on: Boolean) {
        trim.removeCallbacks(hideTrim)
        if (on && ::bar.isInitialized && reader.hasSelection) reader.clearSelection()
        if (on) {
            if (trim.visibility != View.VISIBLE) {
                trim.visibility = View.VISIBLE
                trim.alpha = 0f; trim.translationY = -px(30).toFloat()
                trim.animate().alpha(1f).translationY(0f).setDuration(Kit.SETTLE).setInterpolator(Kit.ease).start()
            }
            poke()
        } else if (trim.visibility == View.VISIBLE) {
            trim.animate().alpha(0f).translationY(-px(30).toFloat()).setDuration(Kit.QUICK).setInterpolator(Kit.ease)
                .withEndAction { trim.visibility = View.GONE }.start()
        }
    }

    private fun poke() { trim.removeCallbacks(hideTrim); trim.postDelayed(hideTrim, Kit.RECEDE_MS) }

    override fun onStart() {
        super.onStart()
        if (!::reader.isInitialized) return
        runCatching {
            tracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also {
                it.addWindowLayoutInfoListener(this, mainExecutor, onLayout)
            }
        }
    }

    override fun onStop() {
        tracker?.removeWindowLayoutInfoListener(onLayout)
        tracker = null
        super.onStop()
    }

    private fun posture(info: WindowLayoutInfo) {
        val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        // The crease only divides the page when the phone is open flat or half open across it
        if (f == null) { reader.setFold(null, true); return }
        val r = Rect(f.bounds)
        // Bounds are in window coordinates; the reader fills the window
        reader.setFold(r, f.orientation == FoldingFeature.Orientation.VERTICAL)
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (!::reader.isInitialized) return
        val wasDark = Kit.dark
        Stock.apply(this)
        if (wasDark != Kit.dark) { article?.let { it.offset = reader.currentOffset(); reader.show(it) }; paintTrim() }
    }

    override fun onResume() {
        super.onResume()
        if (!::reader.isInitialized) return
        // Another screen may have set the phone's light meanwhile: put the stock's back
        val wasDark = Kit.dark
        Stock.apply(this)
        if (wasDark != Kit.dark) { article?.let { it.offset = reader.currentOffset(); reader.show(it) }; paintTrim() }
    }

    override fun onPause() {
        super.onPause()
        if (!::reader.isInitialized) return
        article?.let { it.offset = reader.currentOffset(); Store.put(this, it) }
        Nightstand.refresh(this)
        ReadingProvider.changed(this)
    }

    /**
     * Back to the shelf. A book opened from the widget (or from Fold Widgets) starts a task of its
     * own with nothing beneath it, so finishing alone dropped you on the home screen: bring the
     * shelf up in this task (or return to the one already beneath), then close the book.
     */
    private fun shelf() {
        Kit.follow(this)       // the shelf is in the phone's light, whatever the paper
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    @Deprecated("Back closes the book onto the shelf")
    override fun onBackPressed() { shelf() }

    companion object {
        const val EXTRA_ID = "id"
        fun open(c: Context, id: String) = c.startActivity(Intent(c, ReaderActivity::class.java).putExtra(EXTRA_ID, id))
    }
}

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
    private lateinit var trim: LinearLayout
    private var article: Article? = null
    private var tracker: WindowInfoTrackerCallbackAdapter? = null
    private val onLayout = androidx.core.util.Consumer<WindowLayoutInfo> { info -> posture(info) }
    private val hideTrim = Runnable { showTrim(false) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Dorso")) return
        Kit.follow(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = 0; window.navigationBarColor = 0
        val id = intent.getStringExtra(EXTRA_ID)
        val a = id?.let { Store.get(this, it) } ?: run { finish(); return }
        article = a
        a.openedAt = System.currentTimeMillis(); Store.put(this, a)

        paper = Paper(this).apply { on = prefs().getBoolean("sound", true) }
        reader = ReaderView(this).apply {
            typeStep = prefs().getInt("type", 2)
            paper = this@ReaderActivity.paper
            onMoved = { off, p -> article?.let { it.offset = off; it.progress = p; Store.put(this@ReaderActivity, it) } }
            onTopEdge = { showTrim(true) }
            onSpine = { finish() }
            onMiddle = { showTrim(trim.visibility != View.VISIBLE) }
            onMarks = { article?.let { Store.put(this@ReaderActivity, it) } }
            onCover = { article?.let { Store.put(this@ReaderActivity, it) } }
            onShutTap = { hint() }
        }
        trim = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Kit.RAISED, px(22).toFloat(), Kit.HAIRLINE, px(1))
            setPadding(px(6), px(4), px(6), px(4))
            visibility = View.GONE
            elevation = px(6).toFloat()
            addView(button("A−") { size(-1) })
            addView(button("A+") { size(1) })
            addView(button(if (paper.on) "Sound" else "Silent") { tapped ->
                paper.on = !paper.on
                prefs().edit().putBoolean("sound", paper.on).apply()
                (tapped as android.widget.TextView).text = if (paper.on) "Sound" else "Silent"
                if (paper.on) paper.turn(false)
            })
            addView(button("Marks") { showMarks() })
            addView(button("Shelf") { finish() })
        }
        val root = FrameLayout(this)
        root.addView(reader, FrameLayout.LayoutParams(-1, -1))
        root.addView(trim, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(14) })
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
            (trim.layoutParams as FrameLayout.LayoutParams).topMargin = px(14) + (insets.displayCutout?.safeInsetTop ?: 0)
            insets
        }
        setContentView(root)
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

    override fun onDestroy() {
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    /** The folded pages, each with its opening words; tap one to turn to it. */
    private fun showMarks() {
        val a = article ?: return
        Sheet.show(this, "Marks") { sh ->
            if (a.marks.isEmpty()) {
                sh.body.addView(text("No folded pages yet. Take a page's top corner and fold it down to mark it; tap the fold to smooth it out.", 14.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.3f) })
                return@show
            }
            val card = Card(this)
            for (m in a.marks) {
                val words = reader.wordsAt(m.offset)
                val pct = (100 * reader.fractionAt(m.offset)).toInt()
                card.row(Row(this, words, "$pct% of the way in", null) { sh.dismiss(); reader.goTo(m.offset) })
            }
            sh.body.addView(card)
        }
    }

    private fun prefs() = getSharedPreferences("dorso", Context.MODE_PRIVATE)

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
        Kit.follow(this)
        if (wasDark != Kit.dark) article?.let { it.offset = reader.currentOffset(); reader.show(it) }
    }

    override fun onPause() {
        super.onPause()
        if (!::reader.isInitialized) return
        article?.let { it.offset = reader.currentOffset(); Store.put(this, it) }
    }

    companion object {
        const val EXTRA_ID = "id"
        fun open(c: Context, id: String) = c.startActivity(Intent(c, ReaderActivity::class.java).putExtra(EXTRA_ID, id))
    }
}

package com.cerniera.dorso

import android.animation.ValueAnimator
import android.content.Context
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.cerniera.kit.Hinge
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * The spread. An article set as a printed book: justified and hyphenated, paragraphs indented
 * rather than spaced, a running head and a folio on each page, and the crease as the spine.
 *
 * Shapes:
 * - Upright cover: one page.
 * - Turned cover (short and wide): two short pages side by side, so no line runs the full width.
 * - Main screen: one page each side of the crease, the gutter wider than the crease, so no text
 *   ever sits under the hinge. If the crease runs across (main screen turned, or the phone half
 *   folded that way), one page above it and one below.
 * - Text never sits under the camera hole: a page it would touch starts below it.
 * - Dog-ears: take a page's top outer corner and draw it in and down, and the corner folds over
 *   along a crease set by your finger (up to a quarter of the page down), marking the page. A tap
 *   on the fold smooths it out. Marks are kept by character, so they survive a change of type.
 * - The jacket: on the cover screen the book is shut (when it opens there, or when the phone folds
 *   shut under it), showing its printed jacket ([Jacket]). Tap it or take its edge and it opens on
 *   the same leaf as the pages, onto the endpaper and then your page. Unfolded, it lies open.
 *
 * Where you are is a character: the one that starts the line you were reading. After any fold, turn
 * or type size, the pages are cut afresh so that line starts the left page ([Pager]).
 *
 * The doctrine:
 * - Turning is by hand: take the page and turn it. Tapping the page's edges to turn is an option,
 *   off unless chosen on the trim ("Tap to turn").
 * - Membrane: a page is one sheet hinged at the spine (on the main screen, the crease itself). Take
 *   it anywhere and it rolls over a soft cylinder square to your pull, so lifting the lower corner
 *   folds it diagonally, as paper does; the next page shows beneath, the roll casts a shadow, and the
 *   print shows faintly through. Let go past halfway, or flick, and it goes over with the sound of
 *   paper; otherwise it settles back. A tap lifts it from the lower corner the same way.
 * - Concentrato: turn quickly and the running heads and folios fall away, leaving only text; they
 *   return when you stop.
 * - Giulietta: no toolbar. Touch the top edge for type and the shelf; touch the spine for the shelf.
 */
class ReaderView(c: Context) : View(c) {

    enum class Mode { SINGLE, SIDE, STACK }

    var onMoved: ((offset: Int, progress: Float) -> Unit)? = null
    var onTopEdge: (() -> Unit)? = null
    var onSpine: (() -> Unit)? = null
    var onMiddle: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var article: Article? = null
    private var text: CharSequence = ""
    private var offset = 0

    /** Index into [SIZES]. */
    var typeStep = 2
        set(v) { field = v.coerceIn(0, SIZES.size - 1); relayout() }

    /** The crease in this view's coordinates (null: none reported), and whether it runs top to bottom. */
    private var fold: Rect? = null
    private var foldVertical = true
    private var cutouts: List<Rect> = emptyList()

    fun setFold(r: Rect?, vertical: Boolean) { if (r == fold && vertical == foldVertical) return; fold = r; foldVertical = vertical; relayout() }
    fun setCutouts(r: List<Rect>) { if (r == cutouts) return; cutouts = r; relayout() }

    // ---- Layout state
    var mode = Mode.SINGLE; private set
    private var layout: StaticLayout? = null
    private val textRects = arrayOf(RectF(), RectF())
    private var split = 0f
    private var gutterW = 0f
    private var starts = IntArray(1)
    private var spreads: List<IntArray> = listOf(intArrayOf(0, -1))
    private var spread = 0

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val chrome = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Turning: the leaf between spread [leafA] and the one after it, held by the hand
    private val leaf = Leaf().apply { dpScale = c.resources.displayMetrics.density }
    private var leafA = -1
    /** One page, going back: the page before, sliding out from behind the fold (-1 when not). */
    private var slipA = -1
    /** How far its fore-edge has come onto the page, px from the left edge. */
    private var slipX = 0f
    private var slipAnim: ValueAnimator? = null
    private var slipBmp: Bitmap? = null
    private val swing = Swing(c.resources.displayMetrics.density)
    /** Which side the leaf started on: 1 the recto (turning forward), -1 the verso (turning back). */
    private var sigma = 1
    private var leafAnim: ValueAnimator? = null
    private var rectoBmp: Bitmap? = null
    private var versoBmp: Bitmap? = null

    /**
     * Whether a tap at a page's edge turns it (21 Nov 2026, Mechack: off by default; pages turn by
     * taking the page and turning it). A tap anywhere then brings the trim.
     */
    var tapTurns = false

    /** The sound of the paper, played as a page goes over. */
    var paper: Paper? = null

    // ---- Dog-ears: a page's top outer corner folded down marks it
    /** Called when a page is marked or unmarked, to save the read. */
    var onMarks: (() -> Unit)? = null
    private var cornerSide = -1          // the page whose corner the finger came down on
    private var folding = false
    private var foldU = 0f; private var foldW = 0f
    private var fadeMark: Mark? = null; private var fadePage = -1; private var fadeK = 0f

    // ---- The jacket: on the cover screen the book is shut, until you open it
    /** True while the jacket is showing. Shut when the book opens on the cover screen or the phone folds shut. */
    var closed = false; private set
    private var wasOpen: Boolean? = null
    private var fromJacket = false
    /** Jacket drawings by design, for the size on screen. */
    private val jackets = HashMap<Int, Bitmap>()
    /** The design the cover is turning to, while it turns. */
    private var jacketTo = -1
    /** Called when the cover's design changes, to save the read. */
    var onCover: (() -> Unit)? = null
    /** Called when the shut book is tapped: unfold to read. */
    var onShutTap: (() -> Unit)? = null
    private var lastTurnAt = 0L

    // ---- Selecting, and marking passages as you would a printed copy
    /** What is selected, for the activity's bar: the range, its words, where it sits on screen, and the note it is, if one. */
    class Selection(val start: Int, val end: Int, val text: String, val box: RectF, val note: Note?)
    /** Called with the selection when it settles, and with null when it goes (or while a handle is held). */
    var onSelection: ((Selection?) -> Unit)? = null
    /** Called when a passage is marked or unmarked, to save the read. */
    var onNotes: (() -> Unit)? = null
    private var plain = ""
    private var selStart = -1; private var selEnd = -1
    private var selNote: Note? = null
    /** What the finger holds: 0 nothing, 1 the start handle, 2 the end handle, 3 a long press growing word by word. */
    private var grip = 0
    private var anchorS = 0; private var anchorE = 0
    private val markPath = android.graphics.Path()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    val hasSelection get() = selStart in 0 until selEnd
    private var chromeAlpha = 1f
    private var chromeAnim: ValueAnimator? = null
    private val restoreChrome = Runnable { fadeChrome(1f, Kit.SETTLE) }

    fun show(a: Article) {
        article = a
        offset = a.offset.coerceAtLeast(0)
        text = build(a)
        relayout()
    }

    /** Where the reader is now: the start of the left page's first line. */
    fun currentOffset() = offset

    // ---- Setting the text

    private fun build(a: Article): CharSequence {
        val sb = SpannableStringBuilder()
        fun span(o: Any, from: Int) = sb.setSpan(o, from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Sizes are set relative to the body (RelativeSizeSpan-like, via AbsoluteSizeSpan on dip), applied at layout
        var s = sb.length
        sb.append(a.title).append('\n')
        span(TypefaceSpan(Kit.display(context)), s); span(Rel(1.85f), s); span(ForegroundColorSpan(Stock.INK), s)
        val by = listOf(a.site, a.byline).filter { it.isNotBlank() }.joinToString("  ·  ").uppercase()
        if (by.isNotEmpty()) {
            s = sb.length; sb.append(by).append('\n')
            span(TypefaceSpan(Kit.sans(context, 500)), s); span(Rel(.62f), s); span(ForegroundColorSpan(Stock.ACCENT), s)
        }
        s = sb.length; sb.append('\n'); span(Rel(.9f), s)
        var indent = false
        var opened = false
        for (b in a.blocks) {
            when (b.kind) {
                Block.HEAD -> {
                    s = sb.length; sb.append('\n'); span(Rel(.6f), s)
                    s = sb.length; sb.append(b.text).append('\n')
                    span(TypefaceSpan(Kit.sans(context, 560)), s); span(Rel(1.1f), s)
                    indent = false
                }
                Block.QUOTE -> {
                    s = sb.length; sb.append(b.text).append('\n')
                    span(Margin(1.4f, 1.4f), s); span(ForegroundColorSpan(Stock.MUTED), s)
                    indent = false
                }
                else -> if (isCaption(b.text)) {
                    // A photograph's caption, its picture left behind: set small, slanted and quiet
                    s = sb.length; sb.append(b.text).append('\n')
                    span(Rel(.8f), s); span(Slant(), s); span(ForegroundColorSpan(Stock.MUTED), s)
                    indent = false
                } else if (!opened && b.text.firstOrNull()?.isLetterOrDigit() == true && b.text.length > 120) {
                    // The opening: a drop cap three lines deep, the rest of its line in small capitals
                    opened = true
                    s = sb.length
                    val rest = b.text.substring(1)
                    val lead = Regex("""^\S*(\s+\S+){0,3}""").find(rest)?.value.orEmpty()
                    sb.append(lead.uppercase())
                    span(Rel(.84f), s); span(Spacing(.07f), s)
                    sb.append(rest.substring(lead.length)).append('\n')
                    span(DropCap(b.text.substring(0, 1)), s)
                    indent = true
                } else {
                    s = sb.length; sb.append(b.text).append('\n')
                    if (indent) span(Margin(1.4f, 0f), s)
                    indent = true
                }
            }
        }
        return sb
    }

    /** A caption left behind by its photograph: short, ending in a credit in brackets. */
    private fun isCaption(t: String) = t.length < 260 &&
        Regex("""\([^()]*\b(Getty|Images?|Photo(graph)?s?|Reuters|AFP|AP|Illustration|Credit|Shutterstock|PA)\b[^()]*\)\s*$""").containsMatchIn(t)

    /** Slanted type for captions (Jost has no italic): the face sheared, as a sign painter would. */
    private class Slant : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.textSkewX = -.18f }
        override fun updateMeasureState(tp: TextPaint) { tp.textSkewX = -.18f }
    }

    /** Letter-spacing, for the small capitals of the opening line. */
    private class Spacing(val em: Float) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.letterSpacing = em }
        override fun updateMeasureState(tp: TextPaint) { tp.letterSpacing = em }
    }

    /** The opening's drop cap, resolved when the type size is known. */
    private class DropCap(val letter: String)

    /**
     * A drop cap three lines deep: the letter in the display face, its cap height reaching from the
     * first line's cap height to the third line's baseline, the lines beside it indented clear of it.
     */
    private inner class DropCapSpan(val letter: String, body: Float) : LeadingMarginSpan.LeadingMarginSpan2 {
        private val lines = 3
        private val lh = body * 1.42f
        private val cp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.display(context); color = Stock.INK
            // Italiana's capitals stand about 0.7 of the size: span two line-heights and a cap
            textSize = (2 * lh + .7f * body) / .7f
        }
        private val gap = body * .35f
        private val w = (cp.measureText(letter) + gap).toInt()
        override fun getLeadingMarginLineCount() = lines
        override fun getLeadingMargin(first: Boolean) = if (first) w else 0
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
                                       text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            val sp = text as? Spanned ?: return
            if (sp.getSpanStart(this) != start) return       // only on the paragraph's first line
            cp.color = Stock.INK
            c.drawText(letter, x.toFloat(), baseline + 2 * lh, cp)
        }
    }

    /** A size relative to the body, resolved when the type size is known. */
    private class Rel(val k: Float)
    /** A first-line and following-line indent in ems, resolved when the type size is known. */
    private class Margin(val first: Float, val rest: Float)

    /** The text with sizes and margins resolved for [px]. */
    private fun resolved(px: Float): CharSequence {
        val src = text as? Spanned ?: return text
        val out = SpannableStringBuilder(src.toString())
        for (o in src.getSpans(0, src.length, Any::class.java)) {
            val st = src.getSpanStart(o); val en = src.getSpanEnd(o)
            val real: Any = when (o) {
                is Rel -> AbsoluteSizeSpan((px * o.k).toInt())
                is Margin -> LeadingMarginSpan.Standard((px * o.first).toInt(), (px * o.rest).toInt())
                is DropCap -> DropCapSpan(o.letter, px)
                else -> o
            }
            out.setSpan(real, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // ---- Shapes

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    private fun relayout() {
        if (article == null || width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val wDp = w / dp
        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SIZES[typeStep], resources.displayMetrics)
        paint.textSize = sizePx
        paint.typeface = Kit.sans(context, 400)
        paint.color = Stock.INK

        // A crease running down the middle is the spine. One running across (the main screen turned)
        // is not: the book still lies open left and right, and pages turn left and right, as any
        // book held in front of you does (Mechack, 20 Nov 2026). Mode.STACK is no longer chosen.
        val f = fold?.takeIf { it.right <= width + 1 && it.bottom <= height + 1 && foldVertical && it.exactCenterX() in w * .3f..w * .7f }
        mode = when {
            f != null -> Mode.SIDE
            wDp >= 600f -> Mode.SIDE
            else -> Mode.SINGLE
        }
        val outer = (if (wDp < 520) 24f else 40f) * dp
        val inner = 30f * dp
        val head = 48f * dp
        val foot = 42f * dp
        val maxCol = sizePx * 33f
        when (mode) {
            Mode.SINGLE -> {
                split = 0f; gutterW = 0f
                val cw = min(w - 2 * outer, maxCol)
                textRects[0].set((w - cw) / 2, head, (w + cw) / 2, h - foot)
            }
            Mode.SIDE -> {
                split = f?.exactCenterX() ?: (w / 2)
                gutterW = (f?.width() ?: 0).toFloat()
                val leftAvail = split - gutterW / 2 - inner - outer
                val rightAvail = w - (split + gutterW / 2) - inner - outer
                val cw = min(min(leftAvail, rightAvail), maxCol)
                // Each page's column sits towards the spine, as a book's inner margin is the narrower
                val lRight = split - gutterW / 2 - inner
                val rLeft = split + gutterW / 2 + inner
                val lPad = (leftAvail - cw) / 2; val rPad = (rightAvail - cw) / 2
                textRects[0].set(lRight - cw - lPad, head, lRight - lPad, h - foot)
                textRects[1].set(rLeft + rPad, head, rLeft + rPad + cw, h - foot)
            }
            Mode.STACK -> {
                split = f?.exactCenterY() ?: (h / 2)
                gutterW = (f?.height() ?: 0).toFloat()
                val cw = min(w - 2 * outer, maxCol)
                val l = (w - cw) / 2
                val topBottom = split - gutterW / 2 - inner
                val botTop = split + gutterW / 2 + inner + head * .5f
                val ph = min(topBottom - head, h - foot - botTop)
                textRects[0].set(l, topBottom - ph, l + cw, topBottom)
                textRects[1].set(l, botTop, l + cw, botTop + ph)
            }
        }
        // The camera hole: a page it touches starts below it (both pages, so they stay the same height)
        val pad = 10f * dp
        for (cut in cutouts) {
            val r = RectF(cut).apply { inset(-pad, -pad) }
            for (i in 0..1) {
                if (mode == Mode.SINGLE && i == 1) continue
                val t = textRects[i]
                if (!RectF.intersects(t, r)) continue
                if (r.centerY() < t.centerY()) {
                    val drop = r.bottom - t.top
                    if (mode == Mode.STACK) t.top += drop else for (k in 0..1) textRects[k].top += drop
                } else {
                    val lift = t.bottom - r.top
                    if (mode == Mode.STACK) t.bottom -= lift else for (k in 0..1) textRects[k].bottom -= lift
                }
            }
        }
        if (mode == Mode.STACK) {
            val ph = min(textRects[0].height(), textRects[1].height())
            textRects[0].top = textRects[0].bottom - ph; textRects[1].bottom = textRects[1].top + ph
        }

        val colW = textRects[0].width().toInt().coerceAtLeast(40)
        val body = resolved(sizePx)
        val lay = StaticLayout.Builder.obtain(body, 0, body.length, paint, colW)
            .setLineSpacing(0f, 1.42f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            .build()
        layout = lay
        plain = body.toString()
        // A new cut of the pages moves every word: let any selection go
        if (hasSelection) { selStart = -1; selEnd = -1; selNote = null; grip = 0; onSelection?.invoke(null) }
        val n = lay.lineCount
        val tops = FloatArray(n) { lay.getLineTop(it).toFloat() }
        val bottoms = FloatArray(n) { lay.getLineBottom(it).toFloat() }
        val anchorLine = lay.getLineForOffset(offset.coerceIn(0, body.length))
        starts = Pager.pages(tops, bottoms, textRects[0].height(), anchorLine)
        val anchorPage = Pager.pageOf(starts, anchorLine)
        spreads = Pager.spreads(starts.size, anchorPage, if (mode == Mode.SINGLE) 1 else 2)
        spread = Pager.spreadOf(spreads, anchorPage)
        offset = lay.getLineStart(starts[anchorPage])
        settleNow()
        // The book shuts when it opens on the cover screen, or when the phone folds shut under it;
        // unfolded (or the cover turned sideways) it lies open. Folded or open is read from the
        // whole screen, never from Dorso's window: a narrow window in split screen or a pop-up
        // window on the main screen is not the phone folding (adaptive quality, 20 Nov 2026)
        // On the cover screen the book is shut, always: the cover screen is the book's cover, and
        // reading is for the open phone (Mechack, 20 Nov 2026: one page on the cover never read well)
        val open = Hinge.unfolded(context)
        closed = !open
        wasOpen = open
        jackets.clear()
        record(colW, sizePx)
        invalidate()
    }

    // ---- Drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(Stock.GROUND)
        if (!closed) { c.drawPaint(grain()); drawEdges(c) }
        drawSpine(c)
        when {
            slipA >= 0 -> drawSlip(c)
            leafA >= 0 -> drawTurn(c)
            closed -> drawJacket(c, style())
            else -> { drawSpread(c, spread, 0f); drawHandles(c) }
        }
        drawProgress(c)
    }

    // ---- Paper

    private var grainPaint: Paint? = null
    private var grainFor = 0

    /**
     * The paper's grain: a tile of fine flecks, a shade darker by day and lighter by night, laid
     * over the page so it reads as a surface rather than a screen. Barely there by design.
     */
    private fun grain(): Paint {
        grainPaint?.takeIf { grainFor == Stock.INK }?.let { return it }
        val n = (160 * dp / 2.75f).toInt().coerceAtLeast(96)
        val px = IntArray(n * n)
        val r = kotlin.random.Random(7)
        val ink = Stock.INK and 0xFFFFFF
        for (i in px.indices) {
            val x = r.nextFloat()
            px[i] = when {
                x < .045f -> withAlpha(ink, if (Kit.dark) .05f else .06f)
                x < .055f -> withAlpha(if (Kit.dark) 0x000000 else 0xFFFFFF, .06f)
                else -> 0
            }
        }
        // A few fibres: short faint strokes, as in uncoated stock
        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val fc = Canvas(bmp); val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = .7f; color = withAlpha(ink, .035f) }
        repeat(18) {
            val x = r.nextFloat() * n; val y = r.nextFloat() * n; val a = r.nextFloat() * 6.28f; val l = 3f + 6f * r.nextFloat()
            fc.drawLine(x, y, x + l * kotlin.math.cos(a), y + l * kotlin.math.sin(a), fp)
        }
        grainFor = Stock.INK
        return Paint().apply { shader = android.graphics.BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }.also { grainPaint = it }
    }

    /**
     * The book's thickness: at the outer edges of an open spread, the edges of the pages already read
     * (left) and still to come (right), a stack of hairlines that thins on one side as it grows on
     * the other. You can see how far in you are without a number.
     */
    private fun drawEdges(c: Canvas) {
        if (mode != Mode.SIDE || starts.isEmpty()) return
        val sp = spreads.getOrNull(spread) ?: return
        val shown = max(sp[0], sp[1]) + 1
        val total = starts.size.coerceAtLeast(1)
        val most = 7f * dp
        fun stack(x0: Float, dir: Float, pages: Int) {
            val t = (most * pages / max(total, 12)).coerceIn(if (pages > 0) 1.2f * dp else 0f, most)
            if (t <= 0f) return
            val n = (t / (1.15f * dp)).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val x = x0 + dir * (i * 1.15f * dp + .5f * dp)
                line.strokeWidth = .8f * dp
                line.color = withAlpha(if (Kit.dark) 0xF3ECDD else 0x000000, (if (Kit.dark) .07f else .07f) * (1f - i / (n + 2f)))
                c.drawLine(x, 0f, x, height.toFloat(), line)
            }
        }
        stack(0f, 1f, shown - (if (sp[0] >= 0) 2 else 1).coerceAtLeast(0))
        stack(width.toFloat(), -1f, total - shown)
    }

    private fun drawSpine(c: Canvas) {
        if (mode == Mode.SINGLE) return
        val reach = 26f * dp
        val dark = withAlpha(0x000000, if (Kit.dark) .45f else .12f)
        val clear = withAlpha(0x000000, 0f)
        if (mode == Mode.SIDE) {
            val l = split - gutterW / 2 - reach; val r = split + gutterW / 2 + reach
            shade.shader = LinearGradient(l, 0f, r, 0f, intArrayOf(clear, dark, dark, clear),
                floatArrayOf(0f, .5f - .001f, .5f + .001f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(l, 0f, r, height.toFloat(), shade)
        } else {
            val t = split - gutterW / 2 - reach; val b = split + gutterW / 2 + reach
            shade.shader = LinearGradient(0f, t, 0f, b, intArrayOf(clear, dark, dark, clear),
                floatArrayOf(0f, .5f - .001f, .5f + .001f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, t, width.toFloat(), b, shade)
        }
    }

    private fun drawSpread(c: Canvas, s: Int, dx: Float) {
        val sp = spreads.getOrNull(s) ?: return
        c.save()
        if (mode == Mode.STACK) c.translate(dx, 0f) else c.translate(dx, 0f)
        for (side in 0..1) {
            val page = sp[side]
            if (page < 0 || (mode == Mode.SINGLE && side == 1)) continue
            drawPage(c, page, textRects[side], side)
        }
        c.restore()
    }

    private fun drawPage(c: Canvas, page: Int, r: RectF, side: Int) {
        val lay = layout ?: return
        val first = starts[page]
        val last = (if (page + 1 < starts.size) starts[page + 1] else lay.lineCount) - 1
        val top = lay.getLineTop(first).toFloat()
        val bottom = lay.getLineBottom(last).toFloat()
        c.save()
        c.clipRect(r.left - 2 * dp, r.top, r.right + 2 * dp, r.top + (bottom - top))
        c.translate(r.left, r.top - top)
        drawNotes(c, lay, first, last)
        lay.draw(c)
        c.restore()
        drawEar(c, page, side)
        if (chromeAlpha <= 0.01f) return
        // Running head: the site on the left page, the title on the right (or the title alone)
        val a = article ?: return
        chrome.typeface = Kit.sans(context, 500)
        chrome.textSize = 10.5f * dp * resources.configuration.fontScale
        chrome.letterSpacing = .16f
        chrome.color = withAlpha(Stock.MUTED and 0xFFFFFF, chromeAlpha * .9f)
        val headText = (if (mode != Mode.SINGLE && side == 0) a.site.ifBlank { a.title } else a.title).uppercase()
        val fitted = TextUtils.ellipsize(headText, chrome, r.width(), TextUtils.TruncateAt.END).toString()
        chrome.textAlign = Paint.Align.CENTER
        c.drawText(fitted, r.centerX(), r.top - 20f * dp, chrome)
        // Folio
        chrome.letterSpacing = .04f
        chrome.textSize = 11.5f * dp * resources.configuration.fontScale
        c.drawText("${page + 1}", r.centerX(), r.bottom + 26f * dp, chrome)
    }

    // ---- Dog-ears

    /** The top outer corner of the page on [side]: x, y, and which way is inward along the top. */
    private fun corner(side: Int): FloatArray = when (mode) {
        Mode.SINGLE -> floatArrayOf(width.toFloat(), 0f, -1f)
        Mode.SIDE -> if (side == 0) floatArrayOf(0f, 0f, 1f) else floatArrayOf(width.toFloat(), 0f, -1f)
        Mode.STACK -> if (side == 0) floatArrayOf(width.toFloat(), 0f, -1f) else floatArrayOf(width.toFloat(), split + gutterW / 2, -1f)
    }

    private fun pageW(side: Int) = when (mode) {
        Mode.SIDE -> if (side == 0) split - gutterW / 2 else width - split - gutterW / 2
        else -> width.toFloat()
    }
    private fun pageH(side: Int) = when (mode) {
        Mode.STACK -> if (side == 0) split - gutterW / 2 else height - split - gutterW / 2
        else -> height.toFloat()
    }
    /** As far as a fold may go: a quarter of the page down, nearly half across. */
    private fun maxAlong(side: Int) = pageW(side) * .45f
    private fun maxDown(side: Int) = pageH(side) * .25f

    private fun pageStart(page: Int) = layout?.getLineStart(starts[page]) ?: 0
    private fun pageEnd(page: Int) = if (page + 1 < starts.size) pageStart(page + 1) else Int.MAX_VALUE
    private fun markOn(page: Int): Mark? = article?.marks?.firstOrNull { it.offset >= pageStart(page) && it.offset < pageEnd(page) }

    private fun pageOnSide(side: Int): Int? = if (mode == Mode.SINGLE && side == 1) null else pageAt(spread, side)

    private fun drawEar(c: Canvas, page: Int, side: Int) {
        val k = corner(side)
        val live = folding && cornerSide == side && pageOnSide(side) == page && leafA < 0 && slipA < 0
        if (live) {
            DogEar.crease(foldU, foldW, maxAlong(side), maxDown(side))?.let { DogEar.draw(c, k[0], k[1], k[2], it[0], it[1], dp) }
            return
        }
        markOn(page)?.let { DogEar.draw(c, k[0], k[1], k[2], it.along * dp, it.down * dp, dp); return }
        if (fadePage == page) fadeMark?.let { DogEar.draw(c, k[0], k[1], k[2], it.along * dp * fadeK, it.down * dp * fadeK, dp) }
    }

    /** The side whose top outer corner is under (x, y), within a finger's reach. */
    private fun cornerAt(x: Float, y: Float): Int {
        if (closed || leafA >= 0 || slipA >= 0) return -1
        val reach = 56f * dp
        for (side in 0..1) {
            val page = pageOnSide(side) ?: continue
            val k = corner(side)
            val u = (x - k[0]) * k[2]; val w = y - k[1]
            val m = markOn(page)
            if (m != null) continue        // a folded corner is unfolded by a tap on it
            if (u in -8f * dp..reach && w in -8f * dp..reach) return side
        }
        return -1
    }

    /** A tap on a folded corner unfolds it. */
    private fun tapOnEar(x: Float, y: Float): Boolean {
        if (closed || leafA >= 0 || slipA >= 0) return false
        for (side in 0..1) {
            val page = pageOnSide(side) ?: continue
            val m = markOn(page) ?: continue
            val k = corner(side)
            if (DogEar.hit(x, y, k[0], k[1], k[2], m.along * dp, m.down * dp, 14f * dp)) { unmark(page, m); return true }
        }
        return false
    }

    private fun mark(side: Int) {
        val page = pageOnSide(side) ?: return
        val cr = DogEar.crease(foldU, foldW, maxAlong(side), maxDown(side)) ?: return
        if (kotlin.math.hypot(foldU, foldW) < 26f * dp) return
        val a = article ?: return
        markOn(page)?.let { a.marks.remove(it) }
        a.marks += Mark(pageStart(page), cr[0] / dp, cr[1] / dp)
        a.marks.sortBy { it.offset }
        paper?.fold()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onMarks?.invoke(); record()
    }

    private fun unmark(page: Int, m: Mark) {
        article?.marks?.remove(m)
        fadeMark = m; fadePage = page
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260; interpolator = Kit.ease
            addUpdateListener { fadeK = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { fadeMark = null; fadePage = -1; invalidate() } })
            start()
        }
        paper?.fold()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onMarks?.invoke(); record()
    }

    /** The opening words of the page starting at [off], for the list of marks. */
    fun wordsAt(off: Int): String {
        val t = layout?.text ?: return ""
        val at = off.coerceIn(0, t.length)
        val s = t.subSequence(at, (at + 90).coerceAtMost(t.length)).toString().replace('\n', ' ').trim()
        return if (at + 90 < t.length) s.substringBeforeLast(' ') + "…" else s
    }

    /** The words of a marked passage, cut to [max] characters at a word. */
    fun passage(start: Int, end: Int, max: Int): String {
        val t = plain
        val s = t.substring(start.coerceIn(0, t.length), end.coerceIn(0, t.length)).replace('\n', ' ').trim()
        return if (s.length <= max) s else s.take(max).substringBeforeLast(' ') + "…"
    }

    fun fractionAt(off: Int): Float = layout?.text?.length?.takeIf { it > 0 }?.let { off.toFloat() / it } ?: 0f

    /** Opens the book at [off] (a mark), as if you'd turned to it. */
    fun goTo(off: Int) {
        settleNow()
        offset = off
        relayout()
        moved()
    }

    private fun drawProgress(c: Canvas) {
        val sp = spreads.getOrNull(spread) ?: return
        val shown = max(sp[0], sp[1]) + 1
        val p = shown.toFloat() / starts.size.coerceAtLeast(1)
        line.color = withAlpha(Stock.ACCENT and 0xFFFFFF, .55f * chromeAlpha + .15f)
        line.strokeWidth = max(1f, dp)
        val y = height - line.strokeWidth
        c.drawLine(0f, y, width * p, y, line)
    }

    // ---- Turning and touch

    /** A tap or a key: the leaf turns over by itself, lifted from its lower corner. */
    fun turn(dir: Int): Boolean {
        if (closed) { changeCover(dir); return true }
        if (hasSelection) clearSelection()
        settleNow()
        val target = spread + dir
        if (target < 0 || target >= spreads.size) return false
        val quick = concentrato()
        val ms = if (quick) 300L else 720L
        if (mode == Mode.SINGLE && dir < 0) {
            // One page is a magazine folded back on itself: the page before is behind it, and comes
            // round the fold (the left edge) and out over this one, its print towards you
            beginSlip(target)
            spread = target
            moved()
            paper?.turn(quick)
            slide(width.toFloat(), ms, landed = true)
            return true
        }
        beginLeaf(min(spread, target), if (dir > 0) 1 else -1)
        leaf.take(leaf.len, leaf.breadth * .8f)
        spread = target
        moved()
        paper?.turn(quick)
        sweep(-leaf.grabU, leaf.grabV, arc = leaf.breadth * .09f, ms = ms, landed = true)
        return true
    }

    /** Concentrato: turning fast, only the text stays. True when this turn follows the last closely. */
    private fun concentrato(): Boolean {
        val now = System.currentTimeMillis()
        val quick = now - lastTurnAt < 450
        lastTurnAt = now
        if (quick) fadeChrome(0f, Kit.QUICK)
        removeCallbacks(restoreChrome)
        postDelayed(restoreChrome, 900)
        return quick
    }

    /** The place is now the spread on screen: tell the activity, and the walk. */
    private fun moved() {
        val sp = spreads[spread]
        val left = if (sp[0] >= 0) sp[0] else sp[1]
        layout?.let { offset = it.getLineStart(starts[left]) }
        onMoved?.invoke(offset, (max(sp[0], sp[1]) + 1).toFloat() / starts.size)
        record()
    }

    // ---- The leaf

    private val across get() = mode != Mode.STACK

    /** Places a leaf point (distance from the spine on the side it started, position along the spine) on the screen. */
    private val toScreen: (Float, Float, FloatArray) -> Unit = { u, v, out ->
        val d = sigma * u
        if (across) { out[0] = split + d; out[1] = v } else { out[0] = v; out[1] = split + d }
    }
    private fun frameU(x: Float, y: Float) = sigma * ((if (across) x else y) - split)
    private fun frameV(x: Float, y: Float) = if (across) y else x

    /**
     * Prints both sides of the sheet between spread [a] and the next: its recto is [a]'s right page,
     * its verso the next one's left (on one page, plain paper), each with the other's print faintly
     * through it. [side] is the side it starts on.
     */
    private fun beginLeaf(a: Int, side: Int) {
        leafA = a; sigma = side
        val len = if (across) max(split, width - split) else max(split, height - split)
        val breadth = if (across) height.toFloat() else width.toFloat()
        leaf.size(len, breadth)
        val w = ceil(len).toInt().coerceAtLeast(1); val h = breadth.toInt().coerceAtLeast(1)
        fun bmp(old: Bitmap?) = old?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // A lifted sheet catches more light than the page it leaves: at night that is what shows it
        val paperColour = if (Kit.dark) ColorUtils.blendARGB(Stock.GROUND, Stock.INK, .085f) else Stock.GROUND
        // From the screen into a leaf frame: u from the spine outwards on the recto (k = 1) or verso (k = -1) side
        fun frame(k: Float) = Matrix().apply {
            setValues(if (across) floatArrayOf(k, 0f, -k * split, 0f, 1f, 0f, 0f, 0f, 1f)
                      else floatArrayOf(0f, k, -k * split, 1f, 0f, 0f, 0f, 0f, 1f))
        }
        val single = mode == Mode.SINGLE
        val r = bmp(rectoBmp).also { rectoBmp = it }
        Canvas(r).apply {
            drawColor(paperColour); drawPaint(grain()); concat(frame(1f))
            val sd = if (single) 0 else 1
            pageAt(a, sd)?.let { drawPage(this, it, textRects[sd], sd) }
        }
        val vs = bmp(versoBmp).also { versoBmp = it }
        Canvas(vs).apply {
            drawColor(paperColour); drawPaint(grain())
            if (!single) { save(); concat(frame(-1f)); pageAt(a + 1, 0)?.let { drawPage(this, it, textRects[0], 0) }; restore() }
            // On one page the sheet's back is all you see as it goes: let its print show through
            // more, as thin paper does held to the light, so the page never turns blank
            drawBitmap(r, 0f, 0f, Paint().apply { alpha = if (single) (if (Kit.dark) 60 else 72) else if (Kit.dark) 8 else 11 })
        }
        // Show-through the other way too, now the verso is printed
        if (!single) Canvas(r).drawBitmap(vs, 0f, 0f, Paint().apply { alpha = if (Kit.dark) 6 else 8 })
        leaf.front = if (side > 0) r else vs
        leaf.back = if (side > 0) vs else r
        leaf.shaders()
    }

    // ---- One page, going back: the page before, from behind the fold

    /** Prints page [a] as it lies on the screen, to be drawn out from behind the left edge. */
    private fun beginSlip(a: Int, holdV: Float = height * .72f) {
        slipA = a; slipX = 0f
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        val b = slipBmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Stock.GROUND); drawPaint(grain()); pageAt(a, 0)?.let { drawPage(this, it, textRects[0], 0) } }
        slipBmp = b
        swing.set(b, holdV)
    }

    /**
     * The page before, swung back over the spine and laid on the page you're on (see [Swing]): its
     * fore-edge has come [slipX] onto the page.
     */
    private fun drawSlip(c: Canvas) {
        val w = width.toFloat()
        pageAt(slipA + 1, 0)?.let { drawPage(c, it, textRects[0], 0) }
        val p = swing.progressFor(slipX.coerceIn(0f, w))
        if (p <= 0f) return
        swing.gloss = if (Kit.dark) .30f else .16f
        swing.drawShadow(c, p, Kit.dark)
        swing.shape(p, Kit.dark)
        swing.draw(c, if (Kit.dark) withAlpha(0xF3ECDD, .30f) else withAlpha(0x000000, .12f))
    }

    /**
     * For the emulator walk: the back swing photographed at several moments, on one page, into
     * [dir]/swing-<percent>.png (the fore-edge that far across).
     */
    fun swingFrames(dir: java.io.File) {
        if (mode != Mode.SINGLE || spread <= 0 || width < 1) return
        settleNow()
        beginSlip(spread - 1)
        for (f in intArrayOf(8, 20, 35, 50, 65, 80, 92)) {
            slipX = width * f / 100f
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(b))
            java.io.File(dir, "swing-$f.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        slipA = -1; invalidate()
    }

    /** Carries the fore-edge to [to] (the right edge: laid down; 0: back behind the fold). */
    private fun slide(to: Float, ms: Long, landed: Boolean) {
        slipAnim?.run { removeAllListeners(); cancel() }
        val from = slipX
        slipAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = if (from == 0f) Kit.ease else android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { slipX = from + (to - from) * (it.animatedValue as Float); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) {
                    slipA = -1; slipAnim = null
                    invalidate()
                    if (landed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            })
            start()
        }
    }

    private fun pageAt(s: Int, side: Int): Int? = spreads.getOrNull(s)?.get(side)?.takeIf { it >= 0 }

    private fun drawTurn(c: Canvas) {
        val a = leafA
        // Beneath: this spread's left page, and the next one's right page (on one page, the page
        // beneath; under the jacket, the page you were on)
        if (fromJacket) drawJacket(c, jacketTo)
        else if (mode == Mode.SINGLE) pageAt(a + 1, 0)?.let { drawPage(c, it, textRects[0], 0) }
        else {
            pageAt(a, 0)?.let { drawPage(c, it, textRects[0], 0) }
            pageAt(a + 1, 1)?.let { drawPage(c, it, textRects[1], 1) }
        }
        leaf.shape(toScreen)
        leaf.gloss = if (Kit.dark) .30f else .22f
        leaf.drawShadow(c, toScreen, if (Kit.dark) .55f else .2f, width, height)
        leaf.draw(c)
        // The sheet's edge: lit at night, a hair darker by day, so it reads as paper over paper
        leaf.drawEdge(c, if (Kit.dark) withAlpha(0xF3ECDD, .28f) else withAlpha(0x000000, .10f), .9f * dp)
    }

    /** Carries the hand to (u, v) in the leaf's frame, the corner rising by [arc] on the way. */
    private fun sweep(u: Float, v: Float, arc: Float, ms: Long, landed: Boolean, done: (() -> Unit)? = null) {
        leafAnim?.run { removeAllListeners(); cancel() }
        val u0 = leaf.handU; val v0 = leaf.handV
        leafAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = if (arc > 0f) Kit.ease else android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                leaf.hold(u0 + (u - u0) * t, v0 + (v - v0) * t - arc * kotlin.math.sin(PI * t).toFloat())
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) {
                    leafA = -1
                    done?.invoke()
                    invalidate()
                    if (landed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            })
            start()
        }
    }

    /** Ends any turn at once, the leaf where it was going. */
    private fun settleNow() {
        leafAnim?.run { removeAllListeners(); cancel() }
        leafAnim = null; leafA = -1; fromJacket = false
        slipAnim?.run { removeAllListeners(); cancel() }
        slipAnim = null; slipA = -1
        spring.stop(); leaf.speed = 0f
    }

    // ---- The jacket

    private fun style(): Int = article?.let { if (it.cover >= 0) it.cover else Jacket.auto(it) } ?: 0

    /** Where the jacket sits: the whole cover screen upright; turned, a book standing in the middle. */
    private fun jacketRect(): RectF = if (width <= height) RectF(0f, 0f, width.toFloat(), height.toFloat())
        else (height * .66f).let { bw -> RectF((width - bw) / 2, 0f, (width + bw) / 2, height.toFloat()) }

    private fun jacket(style: Int): Bitmap {
        val r = jacketRect(); val w = r.width().toInt().coerceAtLeast(1); val h = r.height().toInt().coerceAtLeast(1)
        return jackets[style]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                article?.let { Jacket.draw(Canvas(b), w, h, it, context, style) }
                jackets[style] = b
            }
    }

    private fun drawJacket(c: Canvas, style: Int) {
        val r = jacketRect()
        if (r.width() < width) {
            // Turned: the book stands on the dark, a soft shadow under it
            shade.shader = null; shade.color = withAlpha(0x000000, .45f)
            c.drawRect(r.left + 6 * dp, r.top + 8 * dp, r.right + 6 * dp, r.bottom, shade)
        }
        c.drawBitmap(jacket(style), r.left, r.top, null)
    }

    /**
     * The cover as the leaf: the jacket printed on its front, the endpaper inside, and beneath it
     * the next design ([to]), as if leafing through a stack of covers.
     */
    private fun beginJacket(to: Int) {
        fromJacket = true; sigma = 1; leafA = spread; jacketTo = to
        leaf.size(width.toFloat(), height.toFloat())
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        val inside = versoBmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        versoBmp = inside
        Canvas(inside).apply {
            // Endpaper: Visconti green, a fine champagne lattice
            drawColor(Kit.Fixed.VISCONTI_GREEN)
            val l = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Kit.Fixed.CHAMPAGNE and 0xFFFFFF, .12f); strokeWidth = dp * .8f }
            val step = 18f * dp
            var k = -h.toFloat()
            while (k < w + h) { drawLine(k, 0f, k + h, h.toFloat(), l); drawLine(k + h, 0f, k, h.toFloat(), l); k += step }
        }
        val front = versoFront?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        versoFront = front
        Canvas(front).apply { drawColor(Stock.GROUND); drawJacket(this, style()) }
        leaf.front = front; leaf.back = inside
        leaf.shaders()
    }
    private var versoFront: Bitmap? = null

    /** The next (or previous) design, turned to by a tap of a key or a swipe that ends as a flick. */
    private fun changeCover(dir: Int) {
        val to = Math.floorMod(style() + dir, Jacket.names.size)
        settleNow()
        if (width > height) { setCover(to); return }      // turned: the book stands small; it simply changes
        beginJacket(to)
        leaf.take(leaf.len, leaf.breadth * .8f)
        paper?.cover()
        sweep(-leaf.grabU, leaf.grabV, arc = leaf.breadth * .07f, ms = 760L, landed = true) { setCover(to); fromJacket = false }
    }

    /**
     * A tap on the shut book: its cover lifts a little at the corner, showing the next design
     * beneath, and settles back, while the activity says to unfold to read.
     */
    private fun peek() {
        if (leafA >= 0 || width > height) return
        settleNow()
        beginJacket(Math.floorMod(style() + 1, Jacket.names.size))
        leaf.take(leaf.len, leaf.breadth * .82f)
        val u0 = leaf.grabU; val v0 = leaf.grabV
        paper?.fold()
        leafAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Up quickly, a breath at the top, down softly
                val k = if (t < .35f) Kit.ease.getInterpolation(t / .35f) else 1f - Kit.ease.getInterpolation(((t - .45f) / .55f).coerceIn(0f, 1f))
                leaf.hold(u0 - leaf.len * .36f * k, v0 - leaf.breadth * .04f * k)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { leafA = -1; fromJacket = false; invalidate() } })
            start()
        }
    }

    private fun setCover(to: Int) {
        article?.cover = to
        onCover?.invoke(); record(); invalidate()
    }

    // ---- Selecting and marking

    private fun lastLine(page: Int) = (if (page + 1 < starts.size) starts[page + 1] else (layout?.lineCount ?: 1)) - 1

    /** The side of the spread under (x, y) (on one page, 0), or -1 if that side is blank. */
    private fun sideAt(x: Float, y: Float): Int {
        val sp = spreads.getOrNull(spread) ?: return -1
        val s = when (mode) { Mode.SINGLE -> 0; Mode.SIDE -> if (x < split) 0 else 1; Mode.STACK -> if (y < split) 0 else 1 }
        return if (sp[s] >= 0) s else -1
    }

    /** Whether (x, y) is on a page's printed text, give or take a finger. */
    private fun onText(x: Float, y: Float): Boolean {
        val side = sideAt(x, y); if (side < 0) return false
        val lay = layout ?: return false
        val page = spreads[spread][side]; val r = textRects[side]
        val h = lay.getLineBottom(lastLine(page)) - lay.getLineTop(starts[page])
        val slack = 6f * dp
        return x in r.left - slack..r.right + slack && y in r.top - slack..r.top + h + slack
    }

    /** The character under (x, y) on the page there; above its text, its first; below, its last. */
    private fun offsetAt(x: Float, y: Float): Int? {
        val lay = layout ?: return null
        val side = sideAt(x, y).takeIf { it >= 0 } ?: return null
        val page = spreads[spread][side]
        val r = textRects[side]
        val first = starts[page]; val last = lastLine(page)
        val top = lay.getLineTop(first)
        val ly = (y - r.top + top).toInt()
        if (ly < top) return lay.getLineStart(first)
        if (ly >= lay.getLineBottom(last)) return lay.getLineEnd(last)
        val line = lay.getLineForVertical(ly).coerceIn(first, last)
        return lay.getOffsetForHorizontal(line, x - r.left)
    }

    /** The characters on show in this spread. */
    private fun visible(): IntArray {
        val lay = layout ?: return intArrayOf(0, 0)
        val sp = spreads.getOrNull(spread) ?: return intArrayOf(0, 0)
        val pages = sp.filter { it >= 0 && !(mode == Mode.SINGLE && it == sp[1]) }
        if (pages.isEmpty()) return intArrayOf(0, 0)
        return intArrayOf(lay.getLineStart(starts[pages.minOf { it }]), lay.getLineEnd(lastLine(pages.maxOf { it })))
    }

    /**
     * Where character [off] sits on screen: x, its line's top, and just under its baseline. [trailing]
     * for a selection's end, which belongs with the character before it.
     */
    private fun caret(off: Int, trailing: Boolean): FloatArray? {
        val lay = layout ?: return null
        val sp = spreads.getOrNull(spread) ?: return null
        val line = lay.getLineForOffset(if (trailing) max(0, off - 1) else off)
        val page = Pager.pageOf(starts, line)
        val side = when (page) { sp[0] -> 0; sp[1] -> 1; else -> return null }
        if (mode == Mode.SINGLE && side == 1) return null
        val r = textRects[side]
        val top = lay.getLineTop(starts[page]).toFloat()
        val x = if (trailing && lay.getLineForOffset(off) != line) lay.getPrimaryHorizontal(lay.getLineVisibleEnd(line)) else lay.getPrimaryHorizontal(off)
        return floatArrayOf(r.left + x, r.top + lay.getLineTop(line) - top, r.top + lay.getLineBaseline(line) - top + paint.descent())
    }

    private fun isWord(ch: Char) = ch.isLetterOrDigit() || ch == '\'' || ch == '’' || ch == '-'

    /** The word around [off], as a start and end; null if it falls on space or punctuation. */
    private fun wordAround(off: Int): IntArray? {
        val t = plain
        if (t.isEmpty()) return null
        var o = off.coerceIn(0, t.length - 1)
        if (!isWord(t[o]) && o > 0 && isWord(t[o - 1])) o--
        if (!isWord(t[o])) return null
        var s = o; while (s > 0 && isWord(t[s - 1])) s--
        var e = o + 1; while (e < t.length && isWord(t[e])) e++
        while (e > s + 1 && !t[e - 1].isLetterOrDigit()) e--
        while (s < e - 1 && !t[s].isLetterOrDigit()) s++
        return intArrayOf(s, e)
    }

    /** A long press on a word selects it, and while the finger stays down, drawing it on takes more, word by word. */
    private fun beginSelect(x: Float, y: Float): Boolean {
        if (closed || leafA >= 0 || slipA >= 0 || !onText(x, y)) return false
        val off = offsetAt(x, y) ?: return false
        noteAt(off)?.let { n -> select(n.start, n.end, n); publish(); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return true }
        val w = wordAround(off) ?: return false
        anchorS = w[0]; anchorE = w[1]
        select(w[0], w[1], null)
        grip = 3
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun select(s: Int, e: Int, note: Note?) {
        val v = visible()
        selStart = s.coerceIn(v[0], v[1]); selEnd = e.coerceIn(v[0], v[1]); selNote = note
        invalidate()
    }

    private fun extend(x: Float, y: Float) {
        val off = offsetAt(x, y) ?: return
        selNote = null
        when (grip) {
            3 -> { val w = wordAround(off); if (off < anchorS) select(w?.get(0) ?: off, anchorE, null) else select(anchorS, w?.get(1) ?: off, null) }
            1 -> select(min(off, selEnd - 1), selEnd, null)
            2 -> select(selStart, max(off, selStart + 1), null)
        }
    }

    /** Tells the activity what is selected and where, so its bar can sit above it. */
    private fun publish() {
        if (!hasSelection) { onSelection?.invoke(null); return }
        val a = caret(selStart, false); val b = caret(selEnd, true)
        val top = a?.get(1) ?: b?.get(1) ?: 0f
        val bottom = b?.get(2) ?: a?.get(2) ?: height.toFloat()
        val l = min(a?.get(0) ?: 0f, b?.get(0) ?: width.toFloat()); val r = max(a?.get(0) ?: 0f, b?.get(0) ?: width.toFloat())
        onSelection?.invoke(Selection(selStart, selEnd, plain.substring(selStart, selEnd).trim(), RectF(l, top, r, bottom), selNote))
    }

    fun clearSelection() {
        val had = hasSelection
        selStart = -1; selEnd = -1; selNote = null; grip = 0
        invalidate()
        if (had) onSelection?.invoke(null)
    }

    fun noteAt(off: Int): Note? = article?.notes?.lastOrNull { off >= it.start && off < it.end }

    /**
     * Marks the selection: a highlight or a pencil line. Marks of the same kind it overlaps or
     * touches join it, as a second stroke of the pen would.
     */
    fun markSelection(kind: Int) {
        val a = article ?: return
        if (!hasSelection) return
        var s = selStart; var e = selEnd
        while (s < e && plain[s].isWhitespace()) s++
        while (e > s && plain[e - 1].isWhitespace()) e--
        if (e <= s) return
        val joined = a.notes.filter { it.kind == kind && it.start <= e && it.end >= s }
        joined.forEach { s = min(s, it.start); e = max(e, it.end) }
        a.notes.removeAll(joined)
        a.notes += Note(s, e, kind)
        a.notes.sortBy { it.start }
        clearSelection()
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onNotes?.invoke()
    }

    /** Rubs out a mark. */
    fun removeNote(n: Note) {
        article?.notes?.remove(n)
        clearSelection()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onNotes?.invoke()
    }

    /** The line segments a range covers, in the layout's frame: each line's left and right. */
    private inline fun segments(lay: Layout, s: Int, e: Int, each: (line: Int, x0: Float, x1: Float) -> Unit) {
        if (e <= s) return
        val l0 = lay.getLineForOffset(s); val l1 = lay.getLineForOffset(e - 1)
        for (ln in l0..l1) {
            val a = max(s, lay.getLineStart(ln)); val b = min(e, lay.getLineVisibleEnd(ln))
            if (b <= a) continue
            val x0 = lay.getPrimaryHorizontal(a); val x1 = lay.getPrimaryHorizontal(b)
            if (abs(x1 - x0) > .5f) each(ln, min(x0, x1), max(x0, x1))
        }
    }

    /** Highlights, pencil lines and the selection on the page whose lines run [first]..[last], under its print. */
    private fun drawNotes(c: Canvas, lay: Layout, first: Int, last: Int) {
        val from = lay.getLineStart(first); val to = lay.getLineEnd(last)
        val fm = paint.fontMetrics
        article?.notes?.forEach { n ->
            if (n.end <= from || n.start >= to) return@forEach
            val s = max(n.start, from); val e = min(n.end, to)
            if (n.kind == Note.HIGHLIGHT) highlight(c, lay, s, e, fm, n.start) else underline(c, lay, s, e, n.start)
        }
        if (hasSelection && selEnd > from && selStart < to) {
            ink.style = Paint.Style.FILL
            ink.color = withAlpha(Stock.ACCENT and 0xFFFFFF, if (Kit.dark) .34f else .28f)
            segments(lay, max(selStart, from), min(selEnd, to)) { ln, x0, x1 ->
                val base = lay.getLineBaseline(ln).toFloat()
                c.drawRect(x0, base + fm.ascent * .92f, x1, base + fm.descent * 1.2f, ink)
            }
        }
    }

    /**
     * A highlighter's stroke: a band through the x-height and capitals, its ends cut on the slant
     * of a chisel tip, its edges a hair uneven, as a hand draws it.
     */
    private fun highlight(c: Canvas, lay: Layout, s: Int, e: Int, fm: Paint.FontMetrics, seed: Int) {
        ink.style = Paint.Style.FILL; ink.color = Stock.HIGHLIGHT
        val slant = 2.4f * dp
        segments(lay, s, e) { ln, x0, x1 ->
            val r = kotlin.random.Random(seed * 31 + ln)
            val base = lay.getLineBaseline(ln).toFloat()
            val t = base + fm.ascent * .80f + (r.nextFloat() - .5f) * .8f * dp
            val b = base + fm.descent * 1.05f + (r.nextFloat() - .5f) * .8f * dp
            markPath.reset()
            markPath.moveTo(x0 - 1.5f * dp + slant, t)
            markPath.lineTo(x1 + 1.5f * dp + slant, t + (r.nextFloat() - .5f) * .6f * dp)
            markPath.lineTo(x1 + 1.5f * dp, b)
            markPath.lineTo(x0 - 1.5f * dp, b + (r.nextFloat() - .5f) * .6f * dp)
            markPath.close()
            c.drawPath(markPath, ink)
        }
    }

    /** A pencil line under the words: soft graphite, not quite straight, as a ruled line never is by hand. */
    private fun underline(c: Canvas, lay: Layout, s: Int, e: Int, seed: Int) {
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.2f * dp; ink.strokeCap = Paint.Cap.ROUND
        ink.strokeJoin = Paint.Join.ROUND; ink.color = Stock.PENCIL
        segments(lay, s, e) { ln, x0, x1 ->
            val r = kotlin.random.Random(seed * 17 + ln)
            val y = lay.getLineBaseline(ln) + paint.textSize * .2f
            val tilt = (r.nextFloat() - .5f) * 1.4f * dp
            markPath.reset(); markPath.moveTo(x0, y)
            var x = x0
            while (x < x1) {
                x = min(x1, x + 9f * dp)
                markPath.lineTo(x, y + tilt * (x - x0) / max(1f, x1 - x0) + (r.nextFloat() - .5f) * .7f * dp)
            }
            c.drawPath(markPath, ink)
        }
        ink.style = Paint.Style.FILL
    }

    /** The selection's two handles: a stem from the line and a drop beneath, hanging outwards. */
    private fun drawHandles(c: Canvas) {
        if (!hasSelection || grip == 3) return
        val rad = 8.5f * dp
        ink.style = Paint.Style.FILL; ink.color = Stock.ACCENT; ink.strokeWidth = 1.6f * dp
        for (i in 0..1) {
            val h = caret(if (i == 0) selStart else selEnd, i == 1) ?: continue
            val x = h[0]; val y = h[2]
            c.drawLine(x, h[1] + 2f * dp, x, y, ink)
            val dir = if (i == 0) -1f else 1f
            c.drawCircle(x + dir * rad, y + rad, rad, ink)
            c.drawRect(min(x, x + dir * rad), y, max(x, x + dir * rad), y + rad, ink)
        }
    }

    /** Which handle is under (x, y): 1 the start, 2 the end, 0 neither. */
    private fun handleAt(x: Float, y: Float): Int {
        val rad = 8.5f * dp; val reach = 30f * dp
        var best = 0; var bestD = reach
        for (i in 0..1) {
            val h = caret(if (i == 0) selStart else selEnd, i == 1) ?: continue
            val d = kotlin.math.hypot(x - (h[0] + (if (i == 0) -rad else rad)), y - (h[2] + rad))
            if (d < bestD) { bestD = d; best = i + 1 }
        }
        return best
    }

    // ---- The hand on the leaf

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    /** One page, going back: the page before is being drawn out from behind the fold. */
    private var pulling = false
    private var velocity: VelocityTracker? = null

    private fun along(e: MotionEvent) = if (across) e.x else e.y
    private fun along(e: MotionEvent, h: Int) = if (across) e.getHistoricalX(h) else e.getHistoricalY(h)

    private val trail = FloatArray(8); private val trailT = LongArray(8); private var trailN = 0
    private fun note(pos: Float, t: Long) { val i = trailN % 8; trail[i] = pos; trailT[i] = t; trailN++ }
    /** px a second along the turning axis over the last ~80 ms of the finger's path. */
    private fun trailSpeed(): Float {
        if (trailN < 2) return 0f
        val last = (trailN - 1) % 8; val tEnd = trailT[last]
        var k = trailN - 2; var first = last
        while (k >= 0 && k > trailN - 8 && tEnd - trailT[k % 8] <= 80) { first = k % 8; k-- }
        val dt = (tEnd - trailT[first]) / 1000f
        return if (dt <= 0f) 0f else (trail[last] - trail[first]) / dt
    }
    private fun acrossOf(e: MotionEvent) = if (across) e.y else e.x

    private fun startDrag(e: MotionEvent, dir: Int): Boolean {
        if (closed) {
            if (width > height) { changeCover(dir); return false }
            settleNow(); beginJacket(Math.floorMod(style() + dir, Jacket.names.size))
            leaf.take(max(frameU(downX, downY), leaf.len * .3f), frameV(downX, downY))
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            spring.start(leaf.handU, leaf.handV)
            follow(e)
            return true
        }
        val target = spread + dir
        if (target < 0 || target >= spreads.size) return false
        if (hasSelection) clearSelection()
        settleNow()
        pulling = mode == Mode.SINGLE && dir < 0
        if (pulling) {
            // The page before is behind the fold: draw it out round the left edge, its fore-edge
            // where the finger has carried it
            beginSlip(target, downY)
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            follow(e)
            return true
        }
        beginLeaf(min(spread, target), if (dir > 0) 1 else -1)
        leaf.take(max(frameU(downX, downY), leaf.len * .3f), frameV(downX, downY))
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        spring.start(leaf.handU, leaf.handV)
        follow(e)
        return true
    }

    private fun follow(e: MotionEvent) {
        if (pulling) { slipX = (e.x - downX).coerceIn(0f, width.toFloat()); invalidate(); return }
        spring.aim(frameU(e.x, e.y), frameV(e.x, e.y), Rate.HAND)
    }

    /**
     * The paper's weight. The hand pulls the sheet through a stiff spring, so it trails the finger by
     * a hair and never jumps; let go and a softer one carries it on with the finger's own speed and
     * lays it down, settling rather than stopping dead. Its speed bows the curl (a quick turn
     * catches air). Runs on the display's frames.
     */
    private object Rate { const val HAND = 34f; const val FALL = 12.5f }
    private inner class Spring : android.view.Choreographer.FrameCallback {
        var u = 0f; var v = 0f; var vu = 0f; var vv = 0f
        var tu = 0f; var tv = 0f
        var omega = Rate.HAND; var zeta = 1f
        var running = false
        var last = 0L
        var arrive: (() -> Unit)? = null
        var started = 0L

        fun start(fromU: Float, fromV: Float) { u = fromU; v = fromV; vu = 0f; vv = 0f; tu = fromU; tv = fromV; arrive = null }

        fun aim(toU: Float, toV: Float, w: Float, z: Float = 1f, then: (() -> Unit)? = null) {
            tu = toU; tv = toV; omega = w; zeta = z; arrive = then
            if (!running) { running = true; last = 0L; started = System.nanoTime(); android.view.Choreographer.getInstance().postFrameCallback(this) }
        }

        fun stop() { running = false; android.view.Choreographer.getInstance().removeFrameCallback(this) }

        override fun doFrame(now: Long) {
            if (!running) return
            val dt = if (last == 0L) 1f / 120f else ((now - last) / 1e9f).coerceIn(1f / 240f, 1f / 30f)
            last = now
            // Semi-implicit Euler, in two half steps for stiffness
            repeat(2) {
                val h = dt / 2
                val k = omega * omega; val c = 2f * zeta * omega
                vu += (k * (tu - u) - c * vu) * h; u += vu * h
                vv += (k * (tv - v) - c * vv) * h; v += vv * h
            }
            leaf.speed = kotlin.math.hypot(vu, vv)
            leaf.hold(u, v)
            invalidate()
            val settled = arrive != null && kotlin.math.abs(tu - u) < .6f && kotlin.math.abs(tv - v) < .6f && leaf.speed < 30f
            val tooLong = arrive != null && System.nanoTime() - started > 1_600_000_000L
            if (settled || tooLong) {
                running = false
                leaf.speed = 0f; leaf.hold(tu, tv)
                val a = arrive; arrive = null
                a?.invoke()
                return
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }

    }
    private val spring = Spring()

    private fun release() {
        dragging = false
        if (pulling) {
            // Drawn a quarter of the way out, or flicked out, it comes; else it slips back behind
            pulling = false
            velocity?.computeCurrentVelocity(1000)
            val vx = velocity?.xVelocity?.takeIf { kotlin.math.abs(it) > 1f } ?: trailSpeed()
            val fast = 380f * dp
            val w = width.toFloat()
            val come = when { vx > fast -> true; vx < -fast -> false; else -> slipX > w * .25f }
            if (come) { val quick = concentrato(); spread = slipA; moved(); paper?.turn(quick) }
            val rest = if (come) 1f - slipX / w else slipX / w
            slide(if (come) w else 0f, (180 + 380 * rest).toLong(), landed = come)
            return
        }
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val tracked = vt?.let { if (across) it.xVelocity else it.yVelocity } ?: 0f
        val v = sigma * (if (kotlin.math.abs(tracked) > 1f) tracked else trailSpeed())
        val fast = 380f * dp
        // Carried towards the spine and past it, or flicked that way, it goes over; else it settles back
        // On one page the spine is the screen's edge, out of reach: past the middle is far enough
        val halfway = if (mode != Mode.SINGLE) .5f else .3f
        // Judge by where the finger let go, not where the sheet has reached: it trails the hand by
        // a little, and a quick flick lets go before it catches up
        val reached = ((leaf.grabU - spring.tu) / (2 * leaf.grabU)).coerceIn(0f, 1f)
        val over = when { v < -fast -> true; v > fast -> false; else -> max(reached, leaf.progress) > halfway }
        if (fromJacket) {
            if (over) paper?.cover()
            val to = jacketTo
            fall(over, v, landed = over) {
                if (over) setCover(to)
                fromJacket = false
            }
            return
        }
        val a = leafA
        val now = if (over == (sigma > 0)) a + 1 else a
        val changed = now != spread
        if (changed) { val quick = concentrato(); spread = now; moved(); paper?.turn(quick) }
        fall(over, v, landed = changed)
    }

    /**
     * Let go: the sheet goes on at the finger's speed ([v], px a second, in the leaf's frame) and a
     * softer spring lays it down, over or back, settling with a breath of give rather than stopping.
     */
    private fun fall(over: Boolean, v: Float, landed: Boolean, done: (() -> Unit)? = null) {
        val vel = velocity?.let { if (across) it.yVelocity else it.xVelocity } ?: 0f
        spring.vu = v.coerceIn(-9000f * dp / 2.75f, 9000f * dp / 2.75f)
        spring.vv = vel * .2f
        spring.aim(if (over) -leaf.grabU else leaf.grabU, leaf.grabV, Rate.FALL, .82f) {
            leafA = -1
            done?.invoke()
            invalidate()
            if (landed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun fadeChrome(to: Float, ms: Long) {
        chromeAnim?.cancel()
        chromeAnim = ValueAnimator.ofFloat(chromeAlpha, to).apply {
            duration = ms; interpolator = Kit.ease
            addUpdateListener { chromeAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width.toFloat(); val h = height.toFloat()
            // A selection showing: a tap on it brings its bar back, anywhere else lets it go
            if (hasSelection) {
                val off = if (onText(e.x, e.y)) offsetAt(e.x, e.y) else null
                if (off != null && off in selStart until selEnd) publish() else clearSelection()
                return true
            }
            if (tapOnEar(e.x, e.y)) return true
            if (closed) { onShutTap?.invoke(); peek(); return true }
            // A tap on a highlighted or underlined passage takes it up, to look it up or rub it out
            if (onText(e.x, e.y)) offsetAt(e.x, e.y)?.let { noteAt(it) }?.let { n ->
                select(n.start, n.end, n); publish()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
            when {
                e.y < h * .1f -> onTopEdge?.invoke()
                mode == Mode.SIDE && abs(e.x - split) < 36f * dp + gutterW / 2 -> onSpine?.invoke()
                mode == Mode.STACK && abs(e.y - split) < 36f * dp + gutterW / 2 -> onSpine?.invoke()
                // Pages turn by hand, as paper does; tapping the edges turns them only if chosen
                tapTurns && e.x < w * .3f -> turn(-1)
                tapTurns && e.x > w * .7f -> turn(1)
                else -> onMiddle?.invoke()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!folding && !dragging) beginSelect(e.x, e.y)
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (grip > 0) return false
            if (abs(vx) > abs(vy) && abs(vx) > 500f * dp / 2.75f) { turn(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // A handle held, or a long press still growing the selection: the finger moves its end
        if (grip > 0 && e.actionMasked != MotionEvent.ACTION_DOWN) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> extend(e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { grip = 0; publish(); invalidate() }
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN && hasSelection) {
            grip = handleAt(e.x, e.y)
            if (grip > 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                onSelection?.invoke(null)
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false; folding = false; trailN = 0
                cornerSide = cornerAt(e.x, e.y)
                velocity?.recycle(); velocity = VelocityTracker.obtain()
            }
        }
        velocity?.addMovement(e)
        // Our own short record of where the finger was, for the flick's speed when the tracker has none
        for (h in 0 until e.historySize) note(along(e, h), e.getHistoricalEventTime(h))
        note(if (across) e.x else e.y, e.eventTime)
        // A finger on a page's top outer corner, moving in and down, folds the corner
        if (cornerSide >= 0 && !dragging) {
            val k = corner(cornerSide)
            val fu = (e.x - k[0]) * k[2]; val fw = e.y - k[1]
            if (!folding && e.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.hypot(e.x - downX, e.y - downY) > slop && e.y > downY) {
                folding = true
                parent?.requestDisallowInterceptTouchEvent(true)
                gestures.onTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
            }
            if (folding) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> { foldU = fu.coerceAtLeast(0f); foldW = fw.coerceAtLeast(0f); invalidate() }
                    MotionEvent.ACTION_UP -> { foldU = fu.coerceAtLeast(0f); foldW = fw.coerceAtLeast(0f); mark(cornerSide); folding = false; cornerSide = -1; invalidate() }
                    MotionEvent.ACTION_CANCEL -> { folding = false; cornerSide = -1; invalidate() }
                }
                return true
            }
        }
        if (!dragging && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val d = along(e) - (if (across) downX else downY)
            val side = acrossOf(e) - (if (across) downY else downX)
            // Not from the top edge: that's Giulietta's
            if (abs(d) > slop && abs(d) > abs(side) * 1.2f && downY > height * .1f && startDrag(e, if (d < 0) 1 else -1)) {
                gestures.onTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
            }
        }
        if (dragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> follow(e)
                MotionEvent.ACTION_UP -> { follow(e); release() }
                MotionEvent.ACTION_CANCEL -> release()
            }
            return true
        }
        return gestures.onTouchEvent(e)
    }

    // ---- For the emulator walk: what is on screen, in files/layout.json

    private var lastCol = 0
    private var lastSize = 0f

    private fun record(colW: Int = lastCol, sizePx: Float = lastSize) {
        lastCol = colW; lastSize = sizePx
        val lay = layout ?: return
        val sample = text.toString().let { it.substring(min(it.length, 400), min(it.length, 2400)) }
        val avg = if (sample.isEmpty()) 1f else paint.measureText(sample) / sample.length
        val sp = spreads.getOrNull(spread) ?: return
        val usedRects = (0..1).filter { !(mode == Mode.SINGLE && it == 1) }.map { textRects[it] }
        val spine = when (mode) {
            Mode.SIDE -> RectF(split - gutterW / 2, 0f, split + gutterW / 2, height.toFloat())
            Mode.STACK -> RectF(0f, split - gutterW / 2, width.toFloat(), split + gutterW / 2)
            else -> null
        }
        fun arr(r: RectF) = JSONArray(listOf(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
        val o = JSONObject()
            .put("mode", mode.name)
            .put("closed", closed)
            .put("cover", style())
            .put("marks", article?.marks?.size ?: 0)
            .put("view", JSONArray(listOf(width, height)))
            .put("pages", starts.size)
            .put("spread", JSONArray(listOf(sp[0], sp[1])))
            .put("charsPerLine", (colW / avg).toInt())
            .put("lines", lay.lineCount)
            .put("offset", offset)
            .put("firstWords", text.toString().substring(offset.coerceIn(0, text.length)).take(40).replace('\n', ' '))
            .put("text", JSONArray(usedRects.map { arr(it) }))
            .put("spine", spine?.let { arr(it) } ?: JSONObject.NULL)
            .put("fold", fold?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            .put("textOverSpine", spine != null && gutterW > 0 && usedRects.any { RectF.intersects(it, spine) })
            .put("textOverCutout", cutouts.any { c -> usedRects.any { RectF.intersects(it, RectF(c)) } })
        runCatching { File(context.filesDir, "layout.json").writeText(o.toString()) }
    }

    companion object {
        /** Body sizes, sp. */
        val SIZES = floatArrayOf(15.5f, 17f, 18.5f, 20f, 22f, 24f)
    }
}

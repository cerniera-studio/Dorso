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
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
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
 *
 * Where you are is a character: the one that starts the line you were reading. After any fold, turn
 * or type size, the pages are cut afresh so that line starts the left page ([Pager]).
 *
 * The doctrine:
 * - Membrane: a page is one sheet hinged at the spine (on the main screen, the crease itself). Drag
 *   its edge and it lifts, bends and turns over, the fore-edge leading and the spine side following,
 *   the next page showing beneath and this page's print faintly through its back. Let go past
 *   halfway, or flick, and it falls over; otherwise it settles back. A tap turns it the same way.
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

    // ---- Turning: the leaf between spread [leafA] and the one after it, [leafP] of the way over
    private val leaf = Leaf()
    private var leafA = -1
    private var leafP = 0f
    private var leafAnim: ValueAnimator? = null
    private val leafShadow = Paint()
    private val pt = FloatArray(2)
    private var lastTurnAt = 0L
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
        span(TypefaceSpan(Kit.display(context)), s); span(Rel(1.85f), s); span(ForegroundColorSpan(Kit.INK), s)
        val by = listOf(a.site, a.byline).filter { it.isNotBlank() }.joinToString("  ·  ").uppercase()
        if (by.isNotEmpty()) {
            s = sb.length; sb.append(by).append('\n')
            span(TypefaceSpan(Kit.sans(context, 500)), s); span(Rel(.62f), s); span(ForegroundColorSpan(Kit.CHAMPAGNE), s)
        }
        s = sb.length; sb.append('\n'); span(Rel(.9f), s)
        var indent = false
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
                    span(Margin(1.4f, 1.4f), s); span(ForegroundColorSpan(Kit.MUTED), s)
                    indent = false
                }
                else -> {
                    s = sb.length; sb.append(b.text).append('\n')
                    if (indent) span(Margin(1.4f, 0f), s)
                    indent = true
                }
            }
        }
        return sb
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
        paint.color = Kit.INK

        val f = fold?.takeIf { it.right <= width + 1 && it.bottom <= height + 1 }
        mode = when {
            f != null && foldVertical && f.exactCenterX() in w * .3f..w * .7f -> Mode.SIDE
            f != null && !foldVertical && f.exactCenterY() in h * .3f..h * .7f -> Mode.STACK
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
        record(colW, sizePx)
        invalidate()
    }

    // ---- Drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(Kit.GROUND)
        drawSpine(c)
        if (leafA >= 0) drawTurn(c) else drawSpread(c, spread, 0f)
        drawProgress(c)
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
        lay.draw(c)
        c.restore()
        if (chromeAlpha <= 0.01f) return
        // Running head: the site on the left page, the title on the right (or the title alone)
        val a = article ?: return
        chrome.typeface = Kit.sans(context, 500)
        chrome.textSize = 10.5f * dp * resources.configuration.fontScale
        chrome.letterSpacing = .16f
        chrome.color = withAlpha(Kit.MUTED and 0xFFFFFF, chromeAlpha * .9f)
        val headText = (if (mode != Mode.SINGLE && side == 0) a.site.ifBlank { a.title } else a.title).uppercase()
        val fitted = TextUtils.ellipsize(headText, chrome, r.width(), TextUtils.TruncateAt.END).toString()
        chrome.textAlign = Paint.Align.CENTER
        c.drawText(fitted, r.centerX(), r.top - 20f * dp, chrome)
        // Folio
        chrome.letterSpacing = .04f
        chrome.textSize = 11.5f * dp * resources.configuration.fontScale
        c.drawText("${page + 1}", r.centerX(), r.bottom + 26f * dp, chrome)
    }

    private fun drawProgress(c: Canvas) {
        val sp = spreads.getOrNull(spread) ?: return
        val shown = max(sp[0], sp[1]) + 1
        val p = shown.toFloat() / starts.size.coerceAtLeast(1)
        line.color = withAlpha(Kit.CHAMPAGNE and 0xFFFFFF, .55f * chromeAlpha + .15f)
        line.strokeWidth = max(1f, dp)
        val y = height - line.strokeWidth
        c.drawLine(0f, y, width * p, y, line)
    }

    // ---- Turning and touch

    /** A tap or a key: the leaf turns over by itself. */
    fun turn(dir: Int): Boolean {
        settleNow()
        val target = spread + dir
        if (target < 0 || target >= spreads.size) return false
        val quick = concentrato()
        beginLeaf(min(spread, target))
        leafP = if (dir > 0) 0f else pEnd
        spread = target
        moved()
        animateLeaf(if (dir > 0) pEnd else 0f, if (quick) 300L else 640L, landed = true)
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

    /** How far a turn goes: on one page there is no left page to lie on, so the sheet leaves once past upright. */
    private val pEnd get() = if (mode == Mode.SINGLE) .66f else 1f
    private val across get() = mode != Mode.STACK

    /** Places a leaf point (distance from the spine, position along it) on the screen. */
    private val toScreen: (Float, Float, FloatArray) -> Unit = { u, v, out ->
        if (across) { out[0] = split + u; out[1] = v } else { out[0] = v; out[1] = split + u }
    }

    /** Prints both sides of the sheet between spread [a] and the next: its recto is [a]'s right page, its verso the next one's left. */
    private fun beginLeaf(a: Int) {
        leafA = a
        val len = if (across) max(split, width - split) else max(split, height - split)
        val breadth = if (across) height.toFloat() else width.toFloat()
        leaf.size(len, breadth)
        val w = ceil(len).toInt().coerceAtLeast(1); val h = breadth.toInt().coerceAtLeast(1)
        fun bmp(old: Bitmap?) = old?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paper = if (Kit.dark) ColorUtils.blendARGB(Kit.GROUND, Kit.INK, .045f) else Kit.GROUND
        // From the screen into the leaf's frame: u from the spine outwards, v along it
        fun frame(verso: Boolean) = Matrix().apply {
            val k = if (verso) -1f else 1f
            setValues(if (across) floatArrayOf(k, 0f, -k * split, 0f, 1f, 0f, 0f, 0f, 1f)
                      else floatArrayOf(0f, k, -k * split, 1f, 0f, 0f, 0f, 0f, 1f))
        }
        val r = bmp(leaf.recto).also { leaf.recto = it }
        Canvas(r).apply {
            drawColor(paper); concat(frame(false))
            pageAt(a, if (mode == Mode.SINGLE) 0 else 1)?.let { drawPage(this, it, textRects[if (mode == Mode.SINGLE) 0 else 1], if (mode == Mode.SINGLE) 0 else 1) }
        }
        val vs = bmp(leaf.verso?.takeIf { it !== r }).also { leaf.verso = it }
        Canvas(vs).apply {
            drawColor(paper)
            if (mode != Mode.SINGLE) { save(); concat(frame(true)); pageAt(a + 1, 0)?.let { drawPage(this, it, textRects[0], 0) }; restore() }
            // The other side's print, faintly through the paper
            drawBitmap(r, 0f, 0f, Paint().apply { alpha = if (Kit.dark) 14 else 20 })
        }
    }

    private fun pageAt(s: Int, side: Int): Int? = spreads.getOrNull(s)?.get(side)?.takeIf { it >= 0 }

    private fun drawTurn(c: Canvas) {
        val a = leafA
        // Beneath: this spread's left page, and the next one's right page (on one page, the next page)
        if (mode == Mode.SINGLE) pageAt(a + 1, 0)?.let { drawPage(c, it, textRects[0], 0) }
        else {
            pageAt(a, 0)?.let { drawPage(c, it, textRects[0], 0) }
            pageAt(a + 1, 1)?.let { drawPage(c, it, textRects[1], 1) }
        }
        leaf.shape(leafP)
        val lift = kotlin.math.sin(PI * leafP).toFloat().coerceIn(0f, 1f)
        // Its shadow on the page beneath, just past its edge
        val reach = leaf.reach
        if (lift > .01f) {
            val dir = if (reach >= 0f) 1f else -1f
            val w = (8f + 34f * lift) * dp
            val e0 = split + reach; val e1 = e0 + dir * w
            val dark = withAlpha(0x000000, (if (Kit.dark) .55f else .22f) * lift)
            val clear = withAlpha(0x000000, 0f)
            leafShadow.shader = if (across) LinearGradient(e0, 0f, e1, 0f, dark, clear, Shader.TileMode.CLAMP)
                                else LinearGradient(0f, e0, 0f, e1, dark, clear, Shader.TileMode.CLAMP)
            val lo = min(e0, e1); val hi = max(e0, e1)
            if (across) c.drawRect(lo, 0f, hi, height.toFloat(), leafShadow) else c.drawRect(0f, lo, width.toFloat(), hi, leafShadow)
        }
        leaf.draw(c, toScreen, withAlpha(Kit.INK and 0xFFFFFF, if (Kit.dark) .22f else .12f), lift)
    }

    private fun animateLeaf(to: Float, fullMs: Long, landed: Boolean) {
        leafAnim?.run { removeAllListeners(); cancel() }
        val from = leafP
        val share = (abs(to - from) / pEnd).coerceIn(.2f, 1f)
        leafAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = (fullMs * share).toLong()
            interpolator = Kit.ease
            addUpdateListener { leafP = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) {
                    leafA = -1; invalidate()
                    if (landed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            })
            start()
        }
    }

    /** Ends any turn at once, the leaf where it was going. */
    private fun settleNow() {
        leafAnim?.run { removeAllListeners(); cancel() }
        leafAnim = null; leafA = -1
    }

    // ---- The hand on the leaf

    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var grab = 0f
    private var edgeAtGrab = 0f
    private var velocity: VelocityTracker? = null

    private fun along(e: MotionEvent) = if (across) e.x else e.y
    private fun acrossOf(e: MotionEvent) = if (across) e.y else e.x

    private fun startDrag(e: MotionEvent, dir: Int): Boolean {
        val target = spread + dir
        if (target < 0 || target >= spreads.size) return false
        settleNow()
        beginLeaf(min(spread, target))
        leafP = if (dir > 0) 0f else pEnd
        grab = along(e)
        // On one page, going back, the previous page arrives from off the edge: its fore-edge comes to the finger
        edgeAtGrab = if (mode == Mode.SINGLE && dir < 0) grab - split else leaf.len * cos(PI * leafP).toFloat()
        dragging = true
        follow(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun follow(e: MotionEvent) {
        val edge = edgeAtGrab + (along(e) - grab)
        leafP = (acos((edge / leaf.len).coerceIn(-1f, 1f)) / PI).toFloat().coerceIn(0f, pEnd)
        invalidate()
    }

    private fun release() {
        dragging = false
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val v = vt?.let { if (across) it.xVelocity else it.yVelocity } ?: 0f
        val fast = 420f * dp
        // Moving towards the spine side carries the leaf over; a flick decides, else halfway does
        val over = when { v < -fast -> true; v > fast -> false; else -> leafP > pEnd / 2 }
        val a = leafA
        val now = if (over) a + 1 else a
        val changed = now != spread
        if (changed) { concentrato(); spread = now; moved() }
        animateLeaf(if (over) pEnd else 0f, 520L, landed = changed)
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
            when {
                e.y < h * .1f -> onTopEdge?.invoke()
                mode == Mode.SIDE && abs(e.x - split) < 36f * dp + gutterW / 2 -> onSpine?.invoke()
                mode == Mode.STACK && abs(e.y - split) < 36f * dp + gutterW / 2 -> onSpine?.invoke()
                e.x < w * .3f -> turn(-1)
                e.x > w * .7f -> turn(1)
                else -> onMiddle?.invoke()
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > 500f * dp / 2.75f) { turn(if (vx < 0) 1 else -1); return true }
            return false
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
            }
        }
        velocity?.addMovement(e)
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
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

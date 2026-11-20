package com.cerniera.dorso

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cerniera.kit.Kit
import com.cerniera.kit.blend
import com.cerniera.kit.withAlpha
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The book's jacket, on the cover screen when the phone is shut: a printed jacket in the manner of a
 * literary paperback. The author (or, with none, the site) in large spaced capitals; the title set
 * slanted in spaced capitals beneath; a treeline at the foot drawn in stipple, the grain of Solco,
 * grown from the title so that every read has its own; and a champagne ribbon from the head marking
 * how far in you are. Ivory paper by day, Visconti by night.
 *
 * Drawn once per read and size, then turned like any page.
 */
object Jacket {

    fun draw(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val paper = if (Kit.dark) Kit.GROUND else blend(Kit.GROUND, 0xFFF6F1E6.toInt(), .45f)
        val ink = Kit.INK
        c.drawColor(paper)
        val seed = (a.title + a.url).hashCode()
        val rnd = Random(seed)

        // The paper's own grain, barely there
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = withAlpha(ink and 0xFFFFFF, if (Kit.dark) .05f else .045f)
        repeat((w * h / (90 * dp * dp)).toInt().coerceAtMost(9000)) {
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, .55f * dp, dot)
        }

        val margin = w * .1f
        val col = (w - 2 * margin).toInt()

        // Author, large and spaced
        val name = (a.byline.ifBlank { a.site.ifBlank { "Dorso" } }).uppercase().let(::clean)
        val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.display(ctx); color = ink; letterSpacing = .2f
            textSize = fit(name, this, col, w * .135f, w * .075f, 2)
        }
        var y = h * .13f
        y = block(c, name, big, margin, y, col, 2, 1.12f) + h * .045f

        // Title, slanted and spaced, as on a jacket
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.display(ctx); color = ink; letterSpacing = .17f; textSkewX = -.2f
            textSize = fit(a.title.uppercase(), this, col, w * .072f, w * .045f, 5)
        }
        y = block(c, a.title.uppercase(), title, margin, y, col, 5, 1.32f)
        val markY = y + h * .04f

        // The treeline, in stipple, rising from one corner
        trees(c, w, h, max(y + h * .06f, h * .6f), rnd, ink, dp)

        // The ribbon: from the head, its length how far you've read
        val p = a.progress.coerceIn(0f, 1f)
        if (p > .005f) {
            val rx = w - margin * .38f; val rw = 6f * dp
            val len = h * (.06f + .2f * p)
            val rib = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.CHAMPAGNE }
            val path = Path().apply {
                moveTo(rx - rw / 2, 0f); lineTo(rx + rw / 2, 0f); lineTo(rx + rw / 2, len)
                lineTo(rx, len - rw * .8f); lineTo(rx - rw / 2, len); close()
            }
            c.drawPath(path, rib)
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Kit.sans(ctx, 500); color = withAlpha(Kit.CHAMPAGNE and 0xFFFFFF, .95f)
                textSize = 10f * dp; letterSpacing = .16f; textAlign = Paint.Align.RIGHT
            }
            note.textAlign = Paint.Align.CENTER
            c.drawText("${(p * 100).toInt()}%", rx, len + 13f * dp, note)
        }

        // The site at the foot, small, like a publisher's mark
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.sans(ctx, 500); color = withAlpha(ink and 0xFFFFFF, .7f)
            textSize = 9.5f * dp; letterSpacing = .3f; textAlign = Paint.Align.LEFT
        }
        if (a.byline.isNotBlank() && a.site.isNotBlank()) { mark.textAlign = Paint.Align.CENTER; c.drawText(a.site.uppercase(), w / 2f, markY, mark) }
    }

    private fun clean(s: String) = s.replace(Regex("""^BY\s+"""), "").take(48)

    /** The largest size between [hi] and [lo] at which [s] fits in [lines] lines of [col]. */
    private fun fit(s: String, p: TextPaint, col: Int, hi: Float, lo: Float, lines: Int): Float {
        var size = hi
        while (size > lo) {
            p.textSize = size
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, col).setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build()
            val widest = (0 until l.lineCount).maxOf { l.getLineWidth(it) }
            if (l.lineCount <= lines && widest <= col) return size
            size *= .94f
        }
        return lo
    }

    private fun block(c: Canvas, s: String, p: TextPaint, x: Float, y: Float, col: Int, lines: Int, spacing: Float): Float {
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, col)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setLineSpacing(0f, spacing)
            .setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    /**
     * A treeline: a low ridge with trees standing on it, leaning to the side they rise from, drawn
     * as dots that thicken towards the bottom and the trees' hearts.
     */
    private fun trees(c: Canvas, w: Int, h: Int, top: Float, rnd: Random, ink: Int, dp: Float) {
        val fromRight = rnd.nextBoolean()
        val base = h.toFloat()
        val span = base - top
        // The ridge: highest at one side, falling away
        fun ridge(x: Float): Float {
            val t = if (fromRight) x / w else 1 - x / w
            val k = t.pow(1.7f)
            return base - span * (.18f + .42f * k) + span * .05f * sin(x / w * 7f + rnd0) * (1 - k)
        }
        // Trees: each crown a cluster of lobes, standing on a trunk, along the high side
        class Tree(val x: Float, val ground: Float, val top: Float, val lobes: List<FloatArray>)
        val trees = ArrayList<Tree>()
        val count = 7 + rnd.nextInt(5)
        repeat(count) {
            val t = rnd.nextFloat().pow(.6f)
            val x = if (fromRight) w * (1 - t * .9f) else w * t * .9f
            val g = ridge(x)
            val height = span * (.3f + .5f * (1 - t) * (.6f + .4f * rnd.nextFloat()))
            val r = w * (.045f + .05f * rnd.nextFloat()) * (1.25f - t * .5f)
            val crownTop = g - height
            val lobes = ArrayList<FloatArray>()
            val nl = 4 + rnd.nextInt(4)
            repeat(nl) { k ->
                val f = k.toFloat() / (nl - 1)
                val ly = crownTop + r * .9f + f * (height * .62f - r)
                val spread = r * (.35f + .65f * sin(PI.toFloat() * (.25f + .6f * f)))
                lobes += floatArrayOf(x + (rnd.nextFloat() - .5f) * 2 * spread * .6f, ly, r * (.55f + .45f * rnd.nextFloat()) * (.7f + .5f * sin(PI.toFloat() * (.2f + .7f * f))))
            }
            trees += Tree(x, g, crownTop, lobes)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = (w * span * 1.1f / (3.2f * dp * dp)).toInt().coerceAtMost(110000)
        repeat(n) {
            val x = rnd.nextFloat() * w
            val y = top - span * .1f + rnd.nextFloat() * span * 1.1f
            var d = 0f
            val g = ridge(x)
            if (y > g) d = .3f + .6f * ((y - g) / max(1f, base - g)).coerceIn(0f, 1f).pow(.7f)
            for (t in trees) {
                if (abs(x - t.x) < t.lobes[0][2] * .09f && y in t.top..t.ground) d = max(d, .85f)
                for (l in t.lobes) {
                    val dx = (x - l[0]) / l[2]; val dy = (y - l[1]) / (l[2] * 1.1f)
                    val e = dx * dx + dy * dy
                    if (e < 1f) {
                        // Dense at the heart, lighter towards the edge, the edge itself crisp; the lit side paler
                        val lit = ((x - l[0]) / l[2] * (if (fromRight) -1f else 1f) + (l[1] - y) / l[2]) * .18f
                        d = max(d, (.92f - .45f * e - lit).coerceIn(.18f, .95f))
                    }
                }
            }
            if (d > 0f && rnd.nextFloat() < d * d) {
                dot.color = withAlpha(ink and 0xFFFFFF, .62f + .35f * rnd.nextFloat())
                c.drawCircle(x, y, (.42f + .4f * rnd.nextFloat()) * dp, dot)
            }
        }
    }

    private const val rnd0 = (PI / 3).toFloat()
}

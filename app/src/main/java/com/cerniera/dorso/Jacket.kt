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
import kotlin.math.exp
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

        // The treeline, in stipple, rising from one corner
        trees(c, w, h, max(y + h * .06f, h * .6f), rnd, ink, dp)

        // The ribbon: from the head, its length how far you've read
        val p = a.progress.coerceIn(0f, 1f)
        if (p > .005f) {
            val rx = w - margin * .9f; val rw = 7f * dp
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
            c.drawText("${(p * 100).toInt()}%", rx - rw, len - 2 * dp, note)
        }

        // The site at the foot, small, like a publisher's mark
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.sans(ctx, 500); color = withAlpha(ink and 0xFFFFFF, .7f)
            textSize = 9.5f * dp; letterSpacing = .3f; textAlign = Paint.Align.LEFT
        }
        if (a.byline.isNotBlank() && a.site.isNotBlank()) c.drawText(a.site.uppercase(), margin, h - margin * .8f, mark)
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
        // Trees: crowns as soft ellipses along the high side
        data class Tree(val x: Float, val top: Float, val r: Float, val trunk: Float)
        val trees = ArrayList<Tree>()
        val count = 9 + rnd.nextInt(6)
        repeat(count) {
            val t = rnd.nextFloat().pow(.55f)
            val x = if (fromRight) w * (1 - t * .85f) else w * t * .85f
            val g = ridge(x)
            val height = span * (.25f + .55f * (1 - t) * rnd.nextFloat() + .2f * (1 - t))
            trees += Tree(x, g - height, w * (.05f + .07f * rnd.nextFloat()) * (1.2f - t * .5f), g)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = (w * span / (6.5f * dp * dp)).toInt().coerceAtMost(60000)
        repeat(n) {
            val x = rnd.nextFloat() * w
            val y = top - span * .1f + rnd.nextFloat() * span * 1.1f
            var d = 0f
            val g = ridge(x)
            if (y > g) d = .35f + .65f * ((y - g) / max(1f, base - g)).coerceIn(0f, 1f)
            for (t in trees) {
                // A crown: an upright ellipse; a trunk below it
                val ry = (t.trunk - t.top) * .62f
                val cy = t.top + ry
                val ex = (x - t.x) / t.r; val ey = (y - cy) / ry
                val e = ex * ex + ey * ey
                if (e < 1.25f) d = max(d, (1.15f - e) .coerceIn(0f, 1f) * (.55f + .45f * exp(-e * 1.5f)) + .1f * (rnd.nextFloat() - .5f))
                if (abs(x - t.x) < t.r * .07f && y in cy..t.trunk) d = max(d, .75f)
            }
            if (d > 0f && rnd.nextFloat() < d * d * 1.15f) {
                dot.color = withAlpha(ink and 0xFFFFFF, .55f + .4f * rnd.nextFloat())
                c.drawCircle(x, y, (.5f + .55f * rnd.nextFloat()) * dp, dot)
            }
        }
    }

    private const val rnd0 = (PI / 3).toFloat()
}

package com.cerniera.dorso

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.widget.LinearLayout
import com.cerniera.kit.Kit
import com.cerniera.kit.Sheet
import com.cerniera.kit.Swatch
import com.cerniera.kit.blend
import com.cerniera.kit.px
import com.cerniera.kit.rounded
import com.cerniera.kit.text
import com.cerniera.kit.withAlpha

/**
 * How to read in Dorso, in pictures: each gesture drawn as a small page in the stock you read on,
 * beside a line of words. Shown once, the first time a book is opened on the main screen, and
 * kept under Settings (26 Nov 2026, Mechack: how to use the trim was too hidden).
 */
object Guide {
    private class Step(val title: String, val words: String, val draw: (Canvas, RectF, Float) -> Unit)

    private val steps = listOf(
        Step("Turn the page", "Take the page anywhere and turn it, as you would paper. Turn quickly and only the text stays.", ::turn),
        Step("Pull the ribbon", "The ribbon at the top of the spine: pull it down, or tap it, for type size, paper, marks and settings. Tapping the top of a page does the same.", ::ribbon),
        Step("Hold a word", "Press and hold a word to select it; keep your finger down to take more, word by word. Tap the selection to widen it to the sentence, tap again for the paragraph. Drag a handle from anywhere near it; a loupe shows the words.", ::select),
        Step("Mark a passage", "Highlight or underline in pencil. Tap a marked passage: a lit Highlight or Underline rubs that mark out, Remove rubs out both. Select part of a mark to erase just that part.", ::marks),
        Step("Fold a corner", "Draw a page's top corner in and down to fold it, for want of a bookmark. Tap the fold to smooth it out.", ::corner),
        Step("Back to the shelf", "Tap the spine, or Back. Fold the phone shut and the book shows its cover; swipe the cover for another design.", ::spine),
    )

    fun show(a: Activity) {
        Sheet.show(a, "How to read") { sh ->
            for ((i, s) in steps.withIndex()) {
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(a.px(10), a.px(10), a.px(14), a.px(10))
                    background = rounded(Kit.SURFACE, a.px(20).toFloat(), Kit.HAIRLINE, a.px(1))
                }
                val dp = a.resources.displayMetrics.density
                row.addView(Swatch(a) { c, r -> s.draw(c, r, dp) }, LinearLayout.LayoutParams(a.px(104), a.px(78)))
                val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.px(16), 0, 0, 0) }
                words.addView(a.text(s.title, 16.5f, Kit.INK, 500))
                words.addView(a.text(s.words, 13.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.px(3) })
                row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                sh.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = a.px(10) })
            }
        }
    }

    // ---- The pictures: a scrap of page in the reading stock, and the gesture on it

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun page(c: Canvas, r: RectF) { c.drawColor(Stock.GROUND) }

    /** Lines of type from [x0] to [x1], [n] of them from [y0], [gap] apart. */
    private fun lines(c: Canvas, x0: Float, x1: Float, y0: Float, gap: Float, n: Int, dp: Float, alpha: Float = .38f) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 2.2f * dp
        p.color = withAlpha(Stock.INK and 0xFFFFFF, alpha)
        for (k in 0 until n) c.drawLine(x0, y0 + k * gap, if (k == n - 1) x0 + (x1 - x0) * .6f else x1, y0 + k * gap, p)
        p.style = Paint.Style.FILL
    }

    /** A fingertip: a soft disc with a ring. */
    private fun finger(c: Canvas, x: Float, y: Float, dp: Float) {
        p.style = Paint.Style.FILL; p.color = withAlpha(Stock.ACCENT and 0xFFFFFF, .28f); c.drawCircle(x, y, 9f * dp, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * dp; p.color = Stock.ACCENT; c.drawCircle(x, y, 9f * dp, p)
        p.style = Paint.Style.FILL
    }

    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, dp: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * dp; p.strokeCap = Paint.Cap.ROUND; p.color = Stock.ACCENT
        c.drawLine(x0, y0, x1, y1, p)
        val a = kotlin.math.atan2(y1 - y0, x1 - x0); val h = 6f * dp
        c.drawLine(x1, y1, x1 - h * kotlin.math.cos(a - .5f), y1 - h * kotlin.math.sin(a - .5f), p)
        c.drawLine(x1, y1, x1 - h * kotlin.math.cos(a + .5f), y1 - h * kotlin.math.sin(a + .5f), p)
        p.style = Paint.Style.FILL
    }

    private fun turn(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        lines(c, r.left + 12 * dp, r.right - 12 * dp, r.top + 14 * dp, 9 * dp, 6, dp)
        // The lower corner lifting
        val cx = r.right; val cy = r.bottom; val s = r.height() * .55f
        path.reset(); path.moveTo(cx - s, cy); path.lineTo(cx, cy - s * .8f); path.lineTo(cx, cy); path.close()
        p.color = blend(Stock.GROUND, 0xFF000000.toInt(), if (Kit.dark) .4f else .12f); c.drawPath(path, p)
        path.reset(); path.moveTo(cx - s, cy); path.lineTo(cx, cy - s * .8f); path.lineTo(cx - s * .95f, cy - s * .75f); path.close()
        p.color = blend(Stock.GROUND, if (Kit.dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), if (Kit.dark) .12f else .06f); c.drawPath(path, p)
        finger(c, cx - s * .85f, cy - s * .65f, dp)
        arrow(c, cx - s * 1.05f, cy - s * .7f, r.left + 16 * dp, r.top + r.height() * .55f, dp)
    }

    private fun ribbon(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        lines(c, r.left + 12 * dp, r.right - 12 * dp, r.top + 40 * dp, 9 * dp, 4, dp)
        val mid = r.centerX(); val w = 10f * dp; val len = 26f * dp
        path.reset(); path.moveTo(mid - w / 2, r.top); path.lineTo(mid + w / 2, r.top); path.lineTo(mid + w / 2, r.top + len)
        path.lineTo(mid, r.top + len - 4 * dp); path.lineTo(mid - w / 2, r.top + len); path.close()
        p.color = Stock.ACCENT; c.drawPath(path, p)
        // The trim, on its way down
        p.color = withAlpha(Kit.RAISED and 0xFFFFFF, .95f)
        val t = RectF(r.left + 8 * dp, r.top + 6 * dp, mid - 12 * dp, r.top + 20 * dp)
        c.drawRoundRect(t, 7 * dp, 7 * dp, p)
        p.color = withAlpha(Kit.INK and 0xFFFFFF, .5f)
        for (k in 0..2) c.drawCircle(t.left + 8 * dp + k * 10 * dp, t.centerY(), 1.8f * dp, p)
        finger(c, mid, r.top + len + 8 * dp, dp)
        arrow(c, mid + 16 * dp, r.top + 14 * dp, mid + 16 * dp, r.top + 40 * dp, dp)
    }

    private fun select(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        lines(c, r.left + 12 * dp, r.right - 12 * dp, r.top + 16 * dp, 12 * dp, 5, dp, .26f)
        val y = r.top + 28 * dp
        val x0 = r.left + 34 * dp; val x1 = r.left + 66 * dp
        p.color = withAlpha(Stock.ACCENT and 0xFFFFFF, .35f); c.drawRect(x0, y - 6 * dp, x1, y + 5 * dp, p)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 2.6f * dp; p.color = Stock.INK
        c.drawLine(x0 + 2 * dp, y, x1 - 2 * dp, y, p)
        p.style = Paint.Style.FILL; p.color = Stock.ACCENT
        val h = 4f * dp
        c.drawCircle(x0 - h, y + 6 * dp + h, h, p); c.drawRect(x0 - h, y + 6 * dp, x0, y + 6 * dp + h, p)
        c.drawCircle(x1 + h, y + 6 * dp + h, h, p); c.drawRect(x1, y + 6 * dp, x1 + h, y + 6 * dp + h, p)
        // The bar over it
        p.color = Kit.RAISED
        c.drawRoundRect(RectF(x0 - 14 * dp, r.top + 4 * dp, x1 + 18 * dp, r.top + 17 * dp), 6.5f * dp, 6.5f * dp, p)
        p.color = withAlpha(Kit.INK and 0xFFFFFF, .5f)
        for (k in 0..3) c.drawCircle(x0 - 6 * dp + k * 13 * dp, r.top + 10.5f * dp, 1.7f * dp, p)
        finger(c, (x0 + x1) / 2, y + 22 * dp, dp)
    }

    private fun marks(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        val x0 = r.left + 12 * dp; val x1 = r.right - 12 * dp
        val y1 = r.top + 22 * dp; val y2 = r.top + 46 * dp
        p.color = Stock.HIGHLIGHT
        path.reset(); path.moveTo(x0 + 2 * dp, y1 - 6 * dp); path.lineTo(x1 + 2 * dp, y1 - 6 * dp); path.lineTo(x1, y1 + 5 * dp); path.lineTo(x0, y1 + 5 * dp); path.close()
        c.drawPath(path, p)
        lines(c, x0, x1, r.top + 10 * dp, 12 * dp, 6, dp)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * dp; p.color = Stock.PENCIL
        path.reset(); path.moveTo(x0, y2 + 5 * dp)
        var x = x0; var k = 0
        while (x < x1) { x = minOf(x1, x + 8 * dp); path.lineTo(x, y2 + 5 * dp + (if (k++ % 2 == 0) .5f else -.4f) * dp) }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun corner(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        lines(c, r.left + 12 * dp, r.right - 12 * dp, r.top + 18 * dp, 10 * dp, 6, dp)
        val along = 30f * dp; val down = 24f * dp
        DogEar.draw(c, r.right, r.top, -1f, along, down, dp)
        finger(c, r.right - along * .62f, r.top + down * .78f, dp)
    }

    private fun spine(c: Canvas, r: RectF, dp: Float) {
        page(c, r)
        val mid = r.centerX()
        lines(c, r.left + 8 * dp, mid - 10 * dp, r.top + 14 * dp, 9 * dp, 6, dp)
        lines(c, mid + 10 * dp, r.right - 8 * dp, r.top + 14 * dp, 9 * dp, 6, dp)
        p.shader = android.graphics.LinearGradient(mid - 12 * dp, 0f, mid + 12 * dp, 0f,
            intArrayOf(0, withAlpha(0x000000, if (Kit.dark) .5f else .16f), 0), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(mid - 12 * dp, r.top, mid + 12 * dp, r.bottom, p)
        p.shader = null
        finger(c, mid, r.centerY(), dp)
    }
}

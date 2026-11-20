package com.cerniera.dorso

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.cerniera.kit.Kit
import com.cerniera.kit.blend
import com.cerniera.kit.withAlpha
import kotlin.math.hypot
import kotlin.math.min

/**
 * A folded corner, as you'd fold a page in a book for want of a bookmark.
 *
 * In the corner's own frame u runs inward along the top edge and w down the outer edge, from the
 * corner itself. The finger holds the corner's tip at (fu, fw); the crease is the line that tip has
 * been folded across, the perpendicular bisector of corner and tip. It meets the top edge at
 * u = L²/2fu and the outer edge at w = L²/2fw. The corner is gone from the page (the page beneath
 * shows, a little in the flap's shadow) and the flap lies on the page, its back showing, palest at
 * the crease where it rolls over and a touch greyer towards the tip.
 */
internal object DogEar {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Where the crease meets the edges, for a tip held at (fu, fw); null if it isn't a fold yet. */
    fun crease(fu: Float, fw: Float, maxAlong: Float, maxDown: Float): FloatArray? {
        if (fu < 1f || fw < 1f) return null
        val l2 = fu * fu + fw * fw
        return floatArrayOf(min(l2 / (2 * fu), maxAlong), min(l2 / (2 * fw), maxDown))
    }

    /** The tip, from where the crease meets the edges: the corner reflected across the crease. */
    fun tip(along: Float, down: Float): FloatArray {
        // Reflect (0,0) across the line through (along,0) and (0,down)
        val nx = down; val ny = along; val n = hypot(nx, ny)
        val d = (along * down) / n       // distance from the corner to the crease
        return floatArrayOf(2 * d * nx / n, 2 * d * ny / n)
    }

    /**
     * Draws a fold at the corner (cx, cy) whose inward direction along the top is [inward] (+1 or -1),
     * with the crease meeting the edges [along] and [down] px from the corner.
     */
    fun draw(c: Canvas, cx: Float, cy: Float, inward: Float, along: Float, down: Float, dp: Float) {
        if (along < 1f || down < 1f) return
        val t = tip(along, down)
        fun x(u: Float) = cx + inward * u
        // The corner, gone: the page beneath, in the flap's shadow
        val beneath = if (Kit.dark) blend(Kit.GROUND, Color.BLACK, .35f) else blend(Kit.GROUND, Color.BLACK, .07f)
        path.reset(); path.moveTo(cx, cy); path.lineTo(x(along), cy); path.lineTo(cx, cy + down); path.close()
        p.shader = null; p.style = Paint.Style.FILL; p.color = beneath
        c.drawPath(path, p)
        // The flap's shadow on the page, just past its free edges
        path.reset(); path.moveTo(x(along), cy); path.lineTo(x(t[0] + 2.5f * dp), cy + t[1] + 3f * dp); path.lineTo(cx, cy + down); path.close()
        p.color = withAlpha(0x000000, if (Kit.dark) .35f else .12f)
        c.drawPath(path, p)
        // The flap: the page's back, palest at the crease
        path.reset(); path.moveTo(x(along), cy); path.lineTo(x(t[0]), cy + t[1]); path.lineTo(cx, cy + down); path.close()
        val paper = if (Kit.dark) blend(Kit.GROUND, Kit.INK, .10f) else blend(Kit.GROUND, Color.WHITE, .25f)
        val tipShade = if (Kit.dark) blend(Kit.GROUND, Kit.INK, .05f) else blend(Kit.GROUND, Color.BLACK, .05f)
        val mx = x(along / 2); val my = cy + down / 2
        p.shader = LinearGradient(mx, my, x(t[0]), cy + t[1], paper, tipShade, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        // The crease
        p.style = Paint.Style.STROKE; p.strokeWidth = .8f * dp
        p.color = withAlpha(Kit.INK and 0xFFFFFF, if (Kit.dark) .18f else .14f)
        c.drawLine(x(along), cy, cx, cy + down, p)
        p.style = Paint.Style.FILL
    }

    /** Whether (px, py) lies on the flap, give or take a finger. */
    fun hit(px: Float, py: Float, cx: Float, cy: Float, inward: Float, along: Float, down: Float, slack: Float): Boolean {
        val u = (px - cx) * inward; val w = py - cy
        return u >= -slack && w >= -slack && u / (along + slack) + w / (down + slack) <= 1.6f
    }
}

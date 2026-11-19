package com.cerniera.dorso

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The turning leaf: one sheet of paper hinged at the spine, bent by the hand that turns it.
 *
 * Geometry, in the leaf's own frame: u runs from the spine (0) out to the fore-edge ([len]), v along
 * the spine (0 to [breadth]). [p] is how far the turn has gone: 0 lies flat on the recto side (the
 * right page, or the lower one when the crease runs across), 1 lies flat on the verso side.
 *
 * The sheet doesn't stretch: it is built from short strips, each turned a little further than the one
 * nearer the spine (the fore-edge leads, the spine side follows), and their angles summed into a
 * curve. Strips facing away from you show the verso; a strip's light falls off as it turns from you.
 * A mild perspective makes the lifted edge a touch larger, as it is nearer the eye.
 */
internal class Leaf {
    var recto: Bitmap? = null
    var verso: Bitmap? = null
    var len = 1f; private set
    var breadth = 1f; private set

    private val cols = 40
    private val rows = 6
    private val phi = FloatArray(cols + 1)
    private val us = FloatArray(cols + 1)   // projected distance from the spine
    private val sc = FloatArray(cols + 1)   // perspective scale
    private val front = FloatArray((cols + 1) * (rows + 1) * 2)
    private val back = FloatArray(front.size)
    private val frontLight = IntArray((cols + 1) * (rows + 1))
    private val backLight = IntArray(frontLight.size)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The furthest the sheet reaches from the spine on the side it's over (signed), for its shadow. */
    var reach = 0f; private set

    fun size(len: Float, breadth: Float) { this.len = len; this.breadth = breadth }

    /** Bends the sheet for turn [p] (0..1). */
    fun shape(p: Float) {
        val a = (PI * p).toFloat()
        val curl = .95f * sin(a)                                    // most bent mid-turn
        val spine = (a - curl * .5f).coerceIn(0f, PI.toFloat())
        val fore = (a + curl * .5f).coerceIn(0f, PI.toFloat())
        for (k in 0..cols) phi[k] = spine + (fore - spine) * (k.toFloat() / cols).pow(1.5f)
        val du = len / cols
        val eye = 3.2f * max(len, breadth)
        var u = 0f; var z = 0f
        us[0] = 0f; sc[0] = 1f
        var far = 0f
        for (k in 1..cols) {
            val m = (phi[k - 1] + phi[k]) * .5f
            u += cos(m) * du; z += sin(m) * du
            sc[k] = eye / (eye - z)
            us[k] = u * sc[k]
            if (a < PI / 2) far = max(far, us[k]) else far = min(far, us[k])
        }
        reach = far
    }

    /**
     * Draws the bent sheet. [toScreen] places a point given as (distance from the spine, position
     * along it) on the screen; it differs only in which way the spine runs.
     */
    fun draw(c: Canvas, toScreen: (u: Float, v: Float, out: FloatArray) -> Unit, edgeColor: Int, lift: Float) {
        val r = recto ?: return; val vs = verso ?: return
        // The fold line: where the sheet stands edge-on to you
        val half = (PI / 2).toFloat()
        var kc = cols + 1
        for (k in 0..cols) if (phi[k] >= half) { kc = k; break }
        val crossU: Float; val crossS: Float
        if (kc in 1..cols) {
            val f = (half - phi[kc - 1]) / (phi[kc] - phi[kc - 1]).coerceAtLeast(1e-4f)
            crossU = us[kc - 1] + (us[kc] - us[kc - 1]) * f; crossS = sc[kc - 1] + (sc[kc] - sc[kc - 1]) * f
        } else { crossU = 0f; crossS = 1f }
        val pt = FloatArray(2)
        val mid = breadth / 2
        for (k in 0..cols) {
            val cosPhi = cos(phi[k])
            val lf = (.58f + .42f * max(0f, cosPhi)).coerceIn(0f, 1f)
            val lb = (.58f + .42f * max(0f, -cosPhi)).coerceIn(0f, 1f)
            for (j in 0..rows) {
                val v = breadth * j / rows
                val i = j * (cols + 1) + k
                // This strip, and the fold line, on screen
                toScreen(us[k], mid + (v - mid) * sc[k], pt)
                val x = pt[0]; val y = pt[1]
                toScreen(crossU, mid + (v - mid) * crossS, pt)
                val inFront = k < kc
                front[i * 2] = if (inFront) x else pt[0]; front[i * 2 + 1] = if (inFront) y else pt[1]
                back[i * 2] = if (inFront) pt[0] else x; back[i * 2 + 1] = if (inFront) pt[1] else y
                frontLight[i] = grey(lf); backLight[i] = grey(lb)
            }
        }
        if (kc > 0) c.drawBitmapMesh(r, cols, rows, front, 0, frontLight, 0, bmpPaint)
        if (kc <= cols) c.drawBitmapMesh(vs, cols, rows, back, 0, backLight, 0, bmpPaint)
        // The fore-edge, so the sheet reads against a dark page too
        if (lift > .02f) {
            edge.color = edgeColor; edge.alpha = (Color.alpha(edgeColor) * min(1f, lift * 3f)).toInt()
            edge.strokeWidth = 1.2f
            toScreen(us[cols], mid - mid * sc[cols], pt); val x0 = pt[0]; val y0 = pt[1]
            toScreen(us[cols], mid + mid * sc[cols], pt)
            c.drawLine(x0, y0, pt[0], pt[1], edge)
        }
    }

    private fun grey(l: Float): Int { val g = (l * 255).toInt(); return Color.rgb(g, g, g) }
}

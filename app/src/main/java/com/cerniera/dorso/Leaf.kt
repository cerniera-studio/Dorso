package com.cerniera.dorso

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The turning leaf: one sheet of paper hinged at the spine, rolled over by the hand that holds it.
 *
 * In the leaf's own frame u runs from the spine (0) out to the fore-edge ([len]) and v along the spine
 * (0 to [breadth]); the leaf starts lying flat on the u > 0 side. The hand has taken the paper at
 * [grab] and holds it at [hand]. The paper rolls over a soft cylinder whose axis is square to the
 * hand's pull, so the fold runs at whatever angle the hand sets: take the bottom corner and lift it
 * across, and the fold runs diagonally, as with a real page. Paper on the near side of the fold lies
 * flat; on the cylinder it curls up; past it, it lies over on its back, showing the other side.
 * The cylinder is slim when the turn starts and ends and fullest mid-way, so the page lands flat.
 *
 * The paper can't stretch: the hand is kept no further from each end of the spine than the point it
 * holds was, so the spine never lifts. Each triangle of the mesh shows the front or the back
 * according to which way it faces; light falls off over the roll.
 */
internal class Leaf {
    var front: Bitmap? = null
    var back: Bitmap? = null
    var len = 1f; private set
    var breadth = 1f; private set

    val grabU get() = gu
    val grabV get() = gv
    private var gu = 0f; private var gv = 0f
    var handU = 0f; private set
    var handV = 0f; private set

    private val cols = 30
    private val rows = 22
    private val n = (cols + 1) * (rows + 1)
    private val pos = FloatArray(n * 2)      // on screen
    private val tex = FloatArray(n * 2)
    private val light = IntArray(n)
    private val angle = FloatArray(n)
    private val frontIdx = ShortArray(cols * rows * 6)
    private val backIdx = ShortArray(cols * rows * 6)
    private var frontCount = 0; private var backCount = 0
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint()

    /** The roll: where its axis is, which way it runs, how thick. For the shadow. */
    private var fu = 0f; private var fv = 0f; private var du = 1f; private var dv = 0f; private var radius = 0f

    fun size(len: Float, breadth: Float) {
        this.len = len; this.breadth = breadth
        for (j in 0..rows) for (i in 0..cols) {
            val k = j * (cols + 1) + i
            tex[k * 2] = len * i / cols; tex[k * 2 + 1] = breadth * j / rows
        }
    }

    fun shaders() {
        front?.let { fp.shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        back?.let { bp.shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    fun take(u: Float, v: Float) { gu = u.coerceIn(1f, len); gv = v.coerceIn(0f, breadth); handU = gu; handV = gv }

    /** How far over the leaf is: 0 lying where it started, 1 lying on the other side. */
    val progress get() = ((gu - handU) / (2 * gu)).coerceIn(0f, 1f)

    /** Moves the hand to (u, v), keeping it where paper hinged at the spine can reach. */
    fun hold(u: Float, v: Float) {
        var x = u.coerceIn(-gu * 1.02f, gu); var y = v
        val rMax = maxRadius()
        repeat(3) {
            for (cv in floatArrayOf(0f, breadth)) {
                val reach = hypot(gu, gv - cv) - .5f
                val d = hypot(x, y - cv)
                if (d > reach) { x = x * reach / d; y = cv + (y - cv) * reach / d }
            }
        }
        handU = x; handV = y
        radius = rMax * sin(PI * progress).toFloat()
    }

    private fun maxRadius() = min(len * .12f, 64f * dpScale)
    var dpScale = 1f

    /** Bends the mesh for the hand where it is. [toScreen] places a leaf point on the screen. */
    fun shape(toScreen: (Float, Float, FloatArray) -> Unit) {
        val pu = gu - handU; val pv = gv - handV
        val dist = hypot(pu, pv)
        val r = radius
        val pt = FloatArray(2)
        if (dist < .5f) {
            du = 1f; dv = 0f; fu = len * 4; fv = 0f
        } else {
            du = pu / dist; dv = pv / dist
            // Where the roll's axis must sit for the grabbed point to land under the hand
            val s0 = (dist + PI.toFloat() * r) / 2
            fu = gu - du * s0; fv = gv - dv * s0
        }
        val halfPi = (PI / 2).toFloat()
        for (j in 0..rows) for (i in 0..cols) {
            val k = j * (cols + 1) + i
            val u = tex[k * 2]; val v = tex[k * 2 + 1]
            val s = (u - fu) * du + (v - fv) * dv
            var s2 = s; var a = 0f
            if (s > 0f) {
                if (r < .5f) { a = PI.toFloat(); s2 = -s }
                else if (s < PI * r) { a = s / r; s2 = r * sin(a) }
                else { a = PI.toFloat(); s2 = -(s - PI.toFloat() * r) }
            }
            angle[k] = a
            toScreen(u + (s2 - s) * du, v + (s2 - s) * dv, pt)
            pos[k * 2] = pt[0]; pos[k * 2 + 1] = pt[1]
            // Light: full on the flat, falling off over the roll, a touch lower on the back
            // Matte paper: the face dims gently as it turns from the light, the back a little greyer
            val l = when {
                a <= 0f -> 1f
                a >= PI.toFloat() -> .95f
                a < halfPi -> .8f + .2f * cos(a)
                else -> .8f - .15f * cos(a)
            }
            val g = (l * 255).toInt().coerceIn(0, 255)
            light[k] = Color.rgb(g, g, g)
        }
        frontCount = 0; backCount = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * (cols + 1) + i
            val a = (angle[k] + angle[k + 1] + angle[k + cols + 1] + angle[k + cols + 2]) / 4
            val quad = shortArrayOf(k.toShort(), (k + 1).toShort(), (k + cols + 1).toShort(),
                (k + 1).toShort(), (k + cols + 2).toShort(), (k + cols + 1).toShort())
            if (a < halfPi) { quad.copyInto(frontIdx, frontCount); frontCount += 6 }
            else { quad.copyInto(backIdx, backCount); backCount += 6 }
        }
    }

    /** The shadow the roll casts on the page beneath, just past it. */
    fun drawShadow(c: Canvas, toScreen: (Float, Float, FloatArray) -> Unit, strength: Float, w: Int, h: Int) {
        val lift = sin(PI * progress).toFloat()
        if (lift < .01f || (du == 1f && dv == 0f && fu > len * 2)) return
        val reach = (10f + 40f * lift) * dpScale
        val pt = FloatArray(2)
        toScreen(fu + du * (radius - 2f), fv + dv * (radius - 2f), pt); val x0 = pt[0]; val y0 = pt[1]
        toScreen(fu + du * radius, fv + dv * radius, pt); val x1 = pt[0]; val y1 = pt[1]
        toScreen(fu + du * (radius + reach), fv + dv * (radius + reach), pt); val x2 = pt[0]; val y2 = pt[1]
        val total = hypot(x2 - x0, y2 - y0).coerceAtLeast(1f)
        val dark = Color.argb((255 * strength * lift).toInt().coerceIn(0, 255), 0, 0, 0)
        shadow.shader = LinearGradient(x0, y0, x2, y2, intArrayOf(Color.TRANSPARENT, dark, Color.TRANSPARENT),
            floatArrayOf(0f, (hypot(x1 - x0, y1 - y0) / total).coerceIn(0f, .5f), 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadow)
    }

    fun draw(c: Canvas) {
        if (frontCount > 0) c.drawVertices(Canvas.VertexMode.TRIANGLES, pos.size, pos, 0, tex, 0, light, 0, frontIdx, 0, frontCount, fp)
        if (backCount > 0) c.drawVertices(Canvas.VertexMode.TRIANGLES, pos.size, pos, 0, tex, 0, light, 0, backIdx, 0, backCount, bp)
    }

}

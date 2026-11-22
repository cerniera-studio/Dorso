package com.cerniera.dorso

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One page, going back: the page before, swung back over the spine onto the page you're on.
 *
 * On one page the book is held folded back on its spine (the screen's left edge). Going back, the
 * leaf comes up over the spine and is laid down, as a page is in a book in the hand: it rises from
 * the spine standing almost upright, its print facing you, and is lowered from the spine outwards,
 * the part by the spine settling first, the fore-edge last, held up a little by the hand. Seen from
 * above, in perspective: standing, it is a narrow sliver at the edge, its print foreshortened; as it
 * comes down it widens to the full page and settles flat. It never leans past upright, so its print
 * is all that is seen. The hand's corner leads (take it low and the low corner comes down first),
 * light falls on it from above and dims as it stands, and it casts its shadow on the page beneath.
 *
 * The leaf's own frame: u from the spine (0) to the fore-edge ([w]); v down the page (0 to [h]).
 */
internal class Swing(private val dp: Float) {
    private val cols = 28
    private val rows = 18
    private val n = (cols + 1) * (rows + 1)
    private val pos = FloatArray(n * 2)
    private val tex = FloatArray(n * 2)
    private val light = IntArray(n)
    private val sheen = IntArray(n)
    private val idx = ShortArray(cols * rows * 6)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = android.graphics.BlendMode.SCREEN }
    private val shade = Paint()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val rimPath = Path()
    private val ang = FloatArray(cols + 1)
    private val x3 = FloatArray(cols + 1)
    private val z3 = FloatArray(cols + 1)

    private var w = 1f; private var h = 1f
    /** Where the hand holds it, down the page: that corner leads. */
    var holdV = 0f
    var gloss = .3f

    /** How far the fore-edge has come onto the page (px) at each step of the swing, to follow a finger. */
    private val edgeAt = FloatArray(65)

    fun set(page: Bitmap, holdV: Float) {
        w = page.width.toFloat(); h = page.height.toFloat()
        this.holdV = holdV.coerceIn(0f, h)
        paper.shader = BitmapShader(page, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        var k = 0
        for (j in 0..rows) for (i in 0..cols) { tex[k * 2] = w * i / cols; tex[k * 2 + 1] = h * j / rows; k++ }
        k = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val a = j * (cols + 1) + i
            idx[k++] = a.toShort(); idx[k++] = (a + 1).toShort(); idx[k++] = (a + cols + 1).toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = (a + cols + 2).toShort(); idx[k++] = (a + cols + 1).toShort()
        }
        // The fore-edge's place on screen through the swing, kept rising so a finger can be followed
        var best = -w
        for (s in 0..64) { best = max(best, edge(s / 64f)); edgeAt[s] = best }
        edgeAt[64] = w
    }

    /** How far through the swing (0 upright at the spine, 1 laid) puts the fore-edge at [x]. */
    fun progressFor(x: Float): Float {
        if (x <= edgeAt[0]) return 0f
        for (s in 1..64) if (edgeAt[s] >= x) {
            val a = edgeAt[s - 1]; val b = edgeAt[s]
            val f = if (b - a < .01f) 1f else (x - a) / (b - a)
            return ((s - 1) + f) / 64f
        }
        return 1f
    }

    // ---- The shape

    private val eye get() = 2.6f * max(w, h)

    /** The leaf's lean at the spine and how it bows out to the fore-edge, for row offset [lead]. */
    private fun bend(p: Float, lead: Float) {
        val e = 1f - (1f - p).pow(1.7f)                 // the spine settles early, the rest follows
        val upright = 1.50f                              // just short of upright: print towards you
        val root = upright * (1f - e)
        val lift = sin(PI * p).toFloat()
        // The fore-edge is held up by the hand while the rest settles
        val fore = min(upright, root + (upright - root) * (.62f * lift.pow(.7f)) + lead * lift)
        val du = w / cols
        var x = 0f; var z = 0f
        for (i in 0..cols) {
            val t = i / cols.toFloat()
            val a = (root + (fore - root) * t * t * (3 - 2 * t)).coerceIn(0f, upright)
            ang[i] = a
            x3[i] = x; z3[i] = z
            if (i < cols) {
                val t2 = (i + .5f) / cols
                val a2 = (root + (fore - root) * t2 * t2 * (3 - 2 * t2)).coerceIn(0f, upright)
                x += du * cos(a2); z += du * sin(a2)
            }
        }
    }

    private fun projX(x: Float, z: Float) = w / 2 + (x - w / 2) * eye / (eye - z)
    private fun projY(y: Float, z: Float) = h / 2 + (y - h / 2) * eye / (eye - z)

    /** The fore-edge's place on screen at the hand's height, [p] through the swing. */
    private fun edge(p: Float): Float { bend(p, 0f); return projX(x3[cols], z3[cols]) }

    /** Lays the mesh out [p] through the swing (0 upright at the spine, 1 laid on the page). */
    fun shape(p: Float, dark: Boolean) {
        val lift = sin(PI * p).toFloat()
        // Light from above, a little from the left and from you
        val lx = -.35f; val lz = .94f
        for (j in 0..rows) {
            val v = h * j / rows
            // The held corner leads: rows away from the hand trail, held up a little longer
            val lead = .30f * ((v - holdV) / h).let { it * it }.coerceAtMost(1f) * if (p < .98f) 1f else 0f
            bend(p, lead)
            for (i in 0..cols) {
                val k = j * (cols + 1) + i
                pos[k * 2] = projX(x3[i], z3[i]); pos[k * 2 + 1] = projY(v, z3[i])
                val a = ang[i]
                val face = (-sin(a) * lx + cos(a) * lz).coerceIn(0f, 1f)
                val l = (if (dark) .52f else .60f) + (if (dark) .48f else .40f) * face.pow(1.4f) / lz.pow(1.4f)
                val g = (min(1f, l) * 255).toInt()
                light[k] = Color.rgb(g, g, g)
                // The sheen where the page turns its face to the light as it comes down
                val s = cos(a - .55f).coerceAtLeast(0f).pow(10f) * lift * gloss
                sheen[k] = Color.argb((255 * s).toInt().coerceIn(0, 255), 255, 250, 238)
            }
        }
    }

    /** The shadow the leaf casts on the page beneath: under it, and soft past its fore-edge. */
    fun drawShadow(c: Canvas, p: Float, dark: Boolean) {
        val lift = sin(PI * p).toFloat()
        if (lift < .01f) return
        bend(p, 0f)
        val top = z3.maxOrNull() ?: 0f
        val edgeX = projX(x3[cols], 0f)
        val fall = edgeX + z3[cols] * .45f + top * .12f
        val reach = (14f + 46f * lift) * dp
        val strength = (if (dark) .55f else .26f) * lift
        val d = Color.argb((255 * strength).toInt(), 0, 0, 0)
        val d2 = Color.argb((255 * strength * .55f).toInt(), 0, 0, 0)
        val end = max(fall + reach, 1f)
        shade.shader = LinearGradient(0f, 0f, end, 0f, intArrayOf(d2, d, Color.TRANSPARENT),
            floatArrayOf(0f, (fall / end).coerceIn(0f, .98f), 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, end, h, shade)
        shade.shader = null
    }

    fun draw(c: Canvas, edgeColour: Int) {
        c.drawVertices(Canvas.VertexMode.TRIANGLES, pos.size, pos, 0, tex, 0, light, 0, idx, 0, idx.size, paper)
        c.drawVertices(Canvas.VertexMode.TRIANGLES, pos.size, pos, 0, null, 0, sheen, 0, idx, 0, idx.size, shine)
        // The sheet's head, fore-edge and tail, as a fine line, so it reads as paper over paper
        rimPath.reset()
        rimPath.moveTo(pos[0], pos[1])
        for (i in 1..cols) rimPath.lineTo(pos[i * 2], pos[i * 2 + 1])
        for (j in 1..rows) { val k = j * (cols + 1) + cols; rimPath.lineTo(pos[k * 2], pos[k * 2 + 1]) }
        for (i in cols - 1 downTo 0) { val k = rows * (cols + 1) + i; rimPath.lineTo(pos[k * 2], pos[k * 2 + 1]) }
        rim.color = edgeColour; rim.strokeWidth = max(1f, .9f * dp)
        c.drawPath(rimPath, rim)
    }

}

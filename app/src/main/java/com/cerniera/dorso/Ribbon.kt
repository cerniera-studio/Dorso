package com.cerniera.dorso

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.cerniera.kit.Kit
import com.cerniera.kit.blend
import com.cerniera.kit.withAlpha
import kotlin.math.abs

/**
 * The book's ribbon marker, hanging from the head of the spine (26 Nov 2026, Mechack: the tools
 * were too hidden). Always there on the open book, so it says where they are: pull it down and the
 * trim comes down with it, following the finger, or tap it. It hangs low enough that the tip is
 * clear of the edge where the phone's own status bar is pulled down.
 *
 * [onPull] is told how far down it has been pulled (0..1) as the finger moves; [onLet] whether to
 * open, when it lets go; [onTap] on a tap.
 */
@SuppressLint("ViewConstructor")
class Ribbon(c: Context, private val dp: Float) : View(c) {
    var onPull: ((Float) -> Unit)? = null
    var onLet: ((Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    /** How far the trim is down when drawn (0..1); the ribbon lengthens with it a little. */
    var drop = 0f
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downY = 0f
    private var pulling = false
    /** The pull that brings the trim fully down, px. */
    private val reach get() = 110f * dp

    override fun onDraw(c: Canvas) {
        val w = 15f * dp
        val x0 = (width - w) / 2f; val x1 = x0 + w
        val len = 40f * dp + 10f * dp * drop
        val notch = 6f * dp
        // Its shadow on the page
        path.reset()
        path.moveTo(x0 + 1.5f * dp, 0f); path.lineTo(x1 + 1.5f * dp, 0f); path.lineTo(x1 + 1.5f * dp, len + 1.5f * dp)
        path.lineTo(width / 2f + 1.5f * dp, len - notch + 1.5f * dp); path.lineTo(x0 + 1.5f * dp, len + 1.5f * dp); path.close()
        p.shader = null; p.color = withAlpha(0x000000, if (Kit.dark) .45f else .16f)
        c.drawPath(path, p)
        // The silk: the accent, a sheen down its middle, its tail cut in a swallowtail
        path.reset()
        path.moveTo(x0, 0f); path.lineTo(x1, 0f); path.lineTo(x1, len); path.lineTo(width / 2f, len - notch); path.lineTo(x0, len); path.close()
        val base = Stock.ACCENT
        p.shader = LinearGradient(x0, 0f, x1, 0f,
            intArrayOf(blend(base, 0xFF000000.toInt(), .18f), blend(base, 0xFFFFFFFF.toInt(), .22f), blend(base, 0xFF000000.toInt(), .22f)),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        // A woven rib across, faintly
        p.color = withAlpha(0x000000, .08f); p.strokeWidth = .6f * dp
        var y = 3f * dp
        while (y < len - notch) { c.drawLine(x0, y, x1, y, p); y += 2.4f * dp }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; pulling = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val d = e.rawY - downY
                if (!pulling && abs(d) > slop) pulling = true
                if (pulling) onPull?.invoke((d / reach).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) {
                    val k = ((e.rawY - downY) / reach).coerceIn(0f, 1f)
                    onLet?.invoke(k > .35f)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onTap?.invoke()
                }
                pulling = false
            }
            MotionEvent.ACTION_CANCEL -> { if (pulling) onLet?.invoke(false); pulling = false }
        }
        return true
    }
}

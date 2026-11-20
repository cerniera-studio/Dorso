package com.cerniera.dorso

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.widget.RemoteViews
import com.cerniera.kit.Kit
import com.cerniera.kit.withAlpha
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On the nightstand: the book you're in the middle of, on the home screen (or the cover screen,
 * which is a home screen too). Its jacket, in the design you chose, stands as a book does: rounded
 * at the corners, a soft shadow beneath, a sliver of page edges along the fore-edge, the champagne
 * ribbon showing how far in you are. Beneath it, one quiet line: how long is left, and where it's
 * from. A tap opens it at your page (on the cover screen, at its cover: unfold to read).
 *
 * The book is the read opened most recently and not yet finished, else the newest on the shelf.
 * Widgets can't move, so it is a still object; it is redrawn when you stop reading, when a read is
 * saved, and when the widget is resized.
 */
class Nightstand : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, ids)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = refresh(c, intArrayOf(id))

    companion object {
        /** Redraws every Nightstand on the phone, off the main thread. Cheap when there are none. */
        fun refresh(c: Context, only: IntArray? = null) {
            val app = c.applicationContext
            val m = AppWidgetManager.getInstance(app)
            val ids = only ?: m.getAppWidgetIds(ComponentName(app, Nightstand::class.java))
            if (ids.isEmpty()) return
            Thread {
                runCatching {
                    Kit.follow(app)
                    val a = current(app)
                    for (id in ids) m.updateAppWidget(id, views(app, a, size(app, m, id)))
                }
            }.start()
        }

        /** The book on the nightstand. */
        fun current(c: Context): Article? {
            val all = Store.all(c)
            return all.filter { it.openedAt > 0 && it.progress < .99f }.maxByOrNull { it.openedAt } ?: all.firstOrNull()
        }

        /** The widget's size in px: its width, and its height as laid out upright. */
        private fun size(c: Context, m: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = m.getAppWidgetOptions(id)
            val d = c.resources.displayMetrics.density
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 110
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 190
            return (w * d).roundToInt() to (h * d).roundToInt()
        }

        private fun views(c: Context, a: Article?, size: Pair<Int, Int>): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.nightstand)
            v.setImageViewBitmap(R.id.book, render(c, a, size.first, size.second))
            val open = if (a != null) Intent(c, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, a.id)
                       else Intent(c, MainActivity::class.java)
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.book, PendingIntent.getActivity(c, (a?.id ?: "").hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            v.setContentDescription(R.id.book, a?.let { "${it.title}, ${left(it)}" } ?: "Dorso: share a long read to begin")
            return v
        }

        private fun left(a: Article): String {
            val m = (a.minutes * (1f - a.progress)).roundToInt()
            return when {
                a.progress >= .99f -> "finished"
                a.progress < .01f -> "${a.minutes} min"
                m <= 1 -> "a minute left"
                else -> "$m min left"
            }
        }

        /**
         * The book, drawn whole at [w] x [h] px on a clear ground: the jacket as tall as the widget
         * allows (less a line for the words beneath), at a book's proportions, centred.
         */
        fun render(c: Context, a: Article?, w: Int, h: Int): Bitmap {
            val d = c.resources.displayMetrics.density
            val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val line = 24f * d
            val edge = 4f * d                                   // the page block along the fore-edge
            var bh = h - line - 8f * d
            var bw = bh * .64f
            if (bw + edge > w - 10f * d) { bw = w - 10f * d - edge; bh = bw / .64f }
            val left = (w - bw - edge) / 2f; val top = (h - line - bh) / 2f
            val book = RectF(left, top, left + bw, top + bh)
            val r = 7f * d

            // The shadow it casts, then the page block peeking from under the board
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = withAlpha(0x000000, .38f); p.setShadowLayer(9f * d, 0f, 4f * d, withAlpha(0x000000, .45f))
            cv.drawRoundRect(RectF(book.left, book.top, book.right + edge, book.bottom), r, r, p)
            p.clearShadowLayer()
            p.color = 0xFFEDE6D6.toInt()
            cv.drawRoundRect(RectF(book.right - r, book.top + 3f * d, book.right + edge, book.bottom - 3f * d), r * .6f, r * .6f, p)
            p.color = withAlpha(0x8A7F70, .55f); p.strokeWidth = .6f * d
            var x = book.right + 1.2f * d
            while (x < book.right + edge - .5f * d) { cv.drawLine(x, book.top + 5f * d, x, book.bottom - 5f * d, p); x += 1.1f * d }

            // The jacket
            val jw = bw.roundToInt().coerceAtLeast(1); val jh = bh.roundToInt().coerceAtLeast(1)
            val jacket = Bitmap.createBitmap(jw, jh, Bitmap.Config.ARGB_8888)
            if (a != null) Jacket.draw(Canvas(jacket), jw, jh, a, c) else Canvas(jacket).drawColor(Kit.Fixed.VISCONTI_GREEN)
            cv.save()
            cv.clipPath(Path().apply { addRoundRect(book, floatArrayOf(r * .35f, r * .35f, r, r, r, r, r * .35f, r * .35f), Path.Direction.CW) })
            cv.drawBitmap(jacket, book.left, book.top, Paint(Paint.FILTER_BITMAP_FLAG))
            // The hinge near the spine, and a little light across the board
            p.color = withAlpha(0x000000, .22f); p.strokeWidth = 1.4f * d
            cv.drawLine(book.left + bw * .045f, book.top, book.left + bw * .045f, book.bottom, p)
            p.shader = android.graphics.LinearGradient(book.left, book.top, book.right, book.bottom,
                intArrayOf(withAlpha(0xFFFFFF, .10f), withAlpha(0xFFFFFF, 0f), withAlpha(0x000000, .10f)), null, android.graphics.Shader.TileMode.CLAMP)
            cv.drawRect(book, p); p.shader = null
            cv.restore()
            if (a == null) {
                val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.display(c); color = Kit.Fixed.IVORY; textSize = bw * .16f; textAlign = Paint.Align.CENTER }
                cv.drawText("Dorso", book.centerX(), book.centerY() + t.textSize * .3f, t)
            }

            // The line beneath: how long is left, and where it's from
            val words = a?.let { listOf(left(it), it.site.ifBlank { null }).filterNotNull().joinToString("  ·  ") } ?: "Share a long read"
            val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Kit.sans(c, 500); color = Kit.Fixed.IVORY; textSize = 11.5f * d; letterSpacing = .06f
                textAlign = Paint.Align.CENTER; setShadowLayer(3f * d, 0f, 1f * d, withAlpha(0x000000, .6f))
            }
            var s = words
            while (t.measureText(s) > w - 8f * d && s.length > 4) s = s.dropLast(2).trimEnd() + "…".takeIf { !s.endsWith("…") }.orEmpty()
            cv.drawText(s, w / 2f, min(h - 6f * d, book.bottom + line - 4f * d), t)
            return out
        }
    }
}

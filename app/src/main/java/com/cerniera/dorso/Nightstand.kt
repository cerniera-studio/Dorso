package com.cerniera.dorso

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.SizeF
import android.widget.RemoteViews
import com.cerniera.kit.Backdrop
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Kit
import com.cerniera.kit.Shapes
import com.cerniera.kit.Surface
import com.cerniera.kit.withAlpha
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On the nightstand: the book you're reading, on the home screen (or the cover screen, a home screen
 * too), drawn to Fold Widgets' recipe so it sits with them as one set (21 Nov 2026: Mechack's first
 * look found the type soft, the book small in its box, and the whole apart from the pack).
 *
 *  - **Sharp:** one picture per size and orientation the launcher shows it at, drawn to that exact
 *    size at the screen's density and shown 1:1 (the first version drew one picture and let the
 *    launcher scale it, which softened the type).
 *  - **The family's look:** the finish and corners chosen in Fold Widgets, read from its
 *    LookProvider as Nodo's widget does, painted by the Kit's Surface exactly as Fold Widgets paints
 *    its own, adapting to the wallpaper behind it; without Fold Widgets, Vetro at radius 24.
 *  - **Legible by proof:** every word takes the Surface's ink for the zone it sits in, which the
 *    Kit has proven at 7:1 over the lightest and darkest of the wallpaper behind; no shadows on type.
 *  - **Fold Widgets' type:** Jost for every word (label 11/600 tracked, the title 14.5 to 19/600,
 *    meta 12.5/500 muted); widgets of 300 dp and more scale the whole system, up to 1.5x.
 *  - **Composed by shape:** portrait, the book standing above its words; landscape or square, the
 *    book at the left and the words beside it; small, the jacket full bleed with the title over a
 *    shade, as Fold Widgets' music widget does at its small sizes.
 *
 * Like Fold Widgets' widgets it learns where it sits from a tap (the tap that opens the book), and
 * until then reads the wallpaper's overall colour.
 */
class Nightstand : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = refresh(c, ids)

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = refresh(c, intArrayOf(id))

    companion object {
        const val SEAT_WIDGET = "dorso.widget"
        const val SEAT_SIZE = "dorso.seat.size"
        private const val AUTHORITY = "com.fergusonconsultancy.foldwidgets.look"

        private fun store(c: Context) = c.getSharedPreferences("nightstand", Context.MODE_PRIVATE)

        /** Redraws every Nightstand on the phone, off the main thread. Cheap when there are none. */
        fun refresh(c: Context, only: IntArray? = null) {
            val app = c.applicationContext
            val m = AppWidgetManager.getInstance(app) ?: return
            val ids = only ?: runCatching { m.getAppWidgetIds(ComponentName(app, Nightstand::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            Thread {
                runCatching {
                    Backdrop.invalidate()
                    val a = current(app)
                    val look = look(app)
                    for (id in ids) draw(app, m, id, a, look)
                }.onFailure { android.util.Log.w("Dorso", "nightstand", it) }
            }.start()
        }

        /** The book on the nightstand: opened most recently and not finished, else the newest on the shelf. */
        fun current(c: Context): Article? {
            val all = Store.all(c)
            return all.filter { it.openedAt > 0 && it.progress < .99f }.maxByOrNull { it.openedAt } ?: all.firstOrNull()
        }

        /**
         * Fold Widgets' look when it is installed (the finish and corners chosen there, and the
         * images chosen for each screen's glass, which the Kit's Backdrop reads from this store);
         * otherwise Vetro at radius 24.
         */
        fun look(c: Context): WidgetLook {
            var look = WidgetLook(Finish.VETRO, 24f)
            var images = mapOf<String, String?>("cover" to null, "main" to null)
            runCatching {
                c.contentResolver.query(Uri.parse("content://$AUTHORITY/look"), null, null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val f = runCatching { Finish.valueOf(cur.getString(cur.getColumnIndexOrThrow("finish"))) }.getOrDefault(Finish.VETRO)
                        look = WidgetLook(f, cur.getInt(cur.getColumnIndexOrThrow("radius")).toFloat())
                        images = mapOf(
                            "cover" to if (cur.getInt(cur.getColumnIndexOrThrow("image_cover")) == 1) "content://$AUTHORITY/image/cover" else null,
                            "main" to if (cur.getInt(cur.getColumnIndexOrThrow("image_main")) == 1) "content://$AUTHORITY/image/main" else null)
                    }
                }
            }
            store(c).edit().apply { images.forEach { (s, u) -> if (u == null) remove("image_$s") else putString("image_$s", u) } }.apply()
            return look
        }

        /** The sizes the launcher shows this widget at (one per orientation), in dp. */
        private fun sizes(m: AppWidgetManager, id: Int): List<SizeF> {
            val o = m.getAppWidgetOptions(id)
            @Suppress("DEPRECATION")
            val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
            if (listed.isNotEmpty()) return listed.distinctBy { "${it.width.toInt()}x${it.height.toInt()}" }.take(4)
            val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return listOf(SizeF(pw.coerceAtLeast(110).toFloat(), ph.coerceAtLeast(180).toFloat()),
                SizeF(lw.coerceAtLeast(110).toFloat(), lh.coerceAtLeast(110).toFloat())).distinct()
        }

        private fun draw(c: Context, m: AppWidgetManager, id: Int, a: Article?, look: WidgetLook) {
            val d = c.resources.displayMetrics.density
            val ground = Backdrop.ground(c, store(c), id)
            val map = LinkedHashMap<SizeF, RemoteViews>()
            for ((i, s) in sizes(m, id).withIndex()) {
                val pic = paint(c, s.width, s.height, d, look, ground, a)
                val rv = RemoteViews(c.packageName, R.layout.nightstand)
                rv.setImageViewBitmap(R.id.face, pic)
                rv.setContentDescription(R.id.face, a?.let { "${it.title}. ${left(it)}" } ?: "Dorso. Share a long read to begin.")
                rv.setOnClickPendingIntent(R.id.root, open(c, a, id, i, s))
                map[s] = rv
            }
            if (map.isEmpty()) return
            runCatching { m.updateAppWidget(id, if (map.size == 1) map.values.first() else RemoteViews(map)) }
                .onFailure { android.util.Log.w("Dorso", "nightstand $id not drawn", it) }
        }

        /** Opens the book at your page; the tap's bounds teach the widget where it sits. */
        private fun open(c: Context, a: Article?, id: Int, index: Int, s: SizeF): PendingIntent {
            val i = (if (a != null) Intent(c, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, a.id) else Intent(c, MainActivity::class.java))
                .putExtra(SEAT_WIDGET, id).putExtra(SEAT_SIZE, floatArrayOf(s.width, s.height))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(c, 700_000 + id * 8 + index, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** From the activity a tap opened: learn the widget's seat, and redraw it if it moved. */
        fun seatFrom(c: Context, i: Intent?) {
            i ?: return
            val id = i.getIntExtra(SEAT_WIDGET, -1); if (id < 0) return
            val size = i.getFloatArrayExtra(SEAT_SIZE)?.takeIf { it.size == 2 }?.let { SizeF(it[0], it[1]) } ?: return
            val moved = runCatching { Backdrop.record(c, store(c), i, id, null, size, true, size) }.getOrDefault(false)
            if (moved) refresh(c, intArrayOf(id))
        }

        private fun left(a: Article): String {
            val m = (a.minutes * (1f - a.progress)).roundToInt()
            return when {
                a.progress >= .99f -> "Finished"
                a.progress < .01f -> "${a.minutes} min read"
                m <= 1 -> "A minute left"
                else -> "$m min left"
            }
        }

        // ---- The picture

        /** Fold Widgets' type: Jost for every word. */
        private fun ink(c: Context, size: Float, colour: Int, weight: Int, tracking: Float = 0f) =
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Kit.sans(c, weight); textSize = size; color = colour; letterSpacing = tracking
            }

        /** [s] in at most [lines] lines of [width], breaking only between words, ending on a word and an ellipsis. */
        private fun wrap(s: String, p: TextPaint, width: Float, lines: Int): List<String> {
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val out = ArrayList<String>(); var i = 0
            while (i < words.size && out.size < lines) {
                var line = words[i]; i++
                if (p.measureText(line) > width) { out += TextUtils.ellipsize(line, p, width, TextUtils.TruncateAt.END).toString(); continue }
                while (i < words.size && p.measureText("$line ${words[i]}") <= width) { line += " " + words[i]; i++ }
                out += line
            }
            if (i < words.size && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1).split(" ").toMutableList()
                while (last.size > 1 && p.measureText(last.joinToString(" ") + "…") > width) last.removeAt(last.size - 1)
                out += TextUtils.ellipsize(last.joinToString(" ") + "…", p, width, TextUtils.TruncateAt.END).toString()
            }
            return out
        }

        /** The title as large as fits in [lines] lines, from [sizes] largest first, never splitting a word. */
        private fun title(c: Context, s: String, colour: Int, width: Float, lines: Int, sizes: List<Float>): Pair<TextPaint, List<String>> {
            for (size in sizes) {
                val p = ink(c, size, colour, 600)
                if (s.split(Regex("\\s+")).all { p.measureText(it) <= width }) {
                    val w = wrap(s, p, width, lines)
                    if (w.none { it.endsWith("…") }) return p to w
                }
            }
            val p = ink(c, sizes.last(), colour, 600)
            return p to wrap(s, p, width, lines)
        }

        private fun capTop(p: Paint) = -p.fontMetrics.ascent * .72f

        /**
         * The widget at [w] x [h] dp, drawn at [density] (kept within what a launcher accepts), in
         * [look]'s finish over [ground].
         */
        fun paint(c: Context, w: Float, h: Float, density: Float, look: WidgetLook, ground: Ground, a: Article?): Bitmap {
            val d = min(density, sqrt(1_600_000f / (w * h)))
            val bmp = Bitmap.createBitmap(max(1, (w * d).toInt()), max(1, (h * d).toInt()), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.scale(d, d)
            val radius = min(look.radius, min(w, h) / 2)
            val k = if (min(w, h) >= 300f) min(1.5f, min(w, h) / 300f) else 1f
            val pad = 14f * k
            val surface = Surface(look.finish, ground, w, h, radius, 1.1f / d)

            if (a == null) {
                surface.paint(cv)
                val t = ink(c, 17f * k, surface.ink(w / 2, h * .45f), 600).apply { textAlign = Paint.Align.CENTER }
                cv.drawText("Share a long read", w / 2, h * .47f, t)
                val m = ink(c, 12.5f * k, surface.muted(w / 2, h * .58f), 500).apply { textAlign = Paint.Align.CENTER }
                wrap("It waits here, like a book on the nightstand.", m, w - 2 * pad, 2).forEachIndexed { i, l -> cv.drawText(l, w / 2, h * .47f + 22f * k + i * 17f * k, m) }
                return bmp
            }

            // Too small for a book beside its words: the jacket full bleed, the title over a shade
            if (min(w, h) < 125f || (w < 150f && h < 220f)) { cover(c, cv, w, h, d, radius, k, a); return bmp }

            surface.paint(cv)
            val portrait = h >= 1.3f * w
            val label = if (a.progress >= .01f) "READING" else "UP NEXT"
            val meta = listOfNotNull(left(a), a.site.takeIf { it.isNotBlank() })
            if (portrait) {
                // Label, the book standing in the middle, then its words and the progress line
                val lab = ink(c, 11f * k, surface.ink(pad, pad), 600, .14f)
                cv.drawText(label, pad, pad + capTop(lab), lab)
                val colW = w - 2 * pad
                val (tp, tl) = title(c, a.title, surface.ink(w / 2, h * .8f), colW, 2, listOf(17f, 15.5f, 14.5f).map { it * k })
                val mp = ink(c, 12.5f * k, surface.muted(w / 2, h * .9f), 500)
                val lineH = tp.textSize * 1.22f
                val textH = tl.size * lineH + 6f * k + mp.textSize * 1.25f + 12f * k
                val bookTop = pad + lab.textSize + 10f * k
                val bookBottom = h - pad - textH - 10f * k
                var bh = bookBottom - bookTop; var bw = bh * .64f
                if (bw > colW * .78f) { bw = colW * .78f; bh = bw / .64f }
                val top = bookTop + (bookBottom - bookTop - bh) / 2
                drawBook(c, cv, RectF((w - bw) / 2, top, (w + bw) / 2, top + bh), d, a)
                var y = bookBottom + 10f * k
                tl.forEach { l -> tp.color = surface.ink(pad + 10f, y + lineH / 2); cv.drawText(l, pad, y + capTop(tp), tp); y += lineH }
                metaLine(cv, meta, mp, pad, y + 6f * k + capTop(mp), colW)
                progress(cv, surface, pad, h - pad - 2f * k, colW, a.progress, k)
            } else {
                // The book at the left, its words beside it
                var bh = h - 2 * pad; var bw = bh * .64f
                if (bw > w * .42f) { bw = w * .42f; bh = bw / .64f }
                val book = RectF(pad, (h - bh) / 2, pad + bw, (h + bh) / 2)
                drawBook(c, cv, book, d, a)
                val x = book.right + 16f * k; val colW = w - x - pad
                val lab = ink(c, 11f * k, surface.ink(x + 10f, pad), 600, .14f)
                var y = pad
                cv.drawText(label, x, y + capTop(lab), lab); y += lab.textSize + 12f * k
                val mp = ink(c, 12.5f * k, surface.muted(x + colW / 2, h - pad - 20f * k), 500)
                val room = h - y - pad - (mp.textSize * 1.25f + 18f * k)
                val maxLines = max(1, min(4, (room / (19f * k * 1.22f)).toInt()))
                val (tp, tl) = title(c, a.title, surface.ink(x + colW / 2, h / 2), colW, maxLines, listOf(19f, 17f, 15.5f, 14.5f).map { it * k })
                val lineH = tp.textSize * 1.22f
                tl.forEach { l -> tp.color = surface.ink(x + 10f, y + lineH / 2); cv.drawText(l, x, y + capTop(tp), tp); y += lineH }
                metaLine(cv, meta, mp, x, h - pad - 12f * k - mp.textSize * .3f, colW)
                progress(cv, surface, x, h - pad - 2f * k, colW, a.progress, k)
            }
            return bmp
        }

        /** Time left and where it's from, the place dropped first when there is no room. */
        private fun metaLine(cv: Canvas, parts: List<String>, p: TextPaint, x: Float, baseline: Float, width: Float) {
            var s = parts.joinToString("  ·  ")
            if (p.measureText(s) > width) s = parts.first()
            cv.drawText(TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString(), x, baseline, p)
        }

        /** How far in: a fine track in the faint ink, the read part in the finish's light. */
        private fun progress(cv: Canvas, s: Surface, x: Float, y: Float, width: Float, p: Float, k: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 2.4f * k }
            paint.color = s.faint(x + width / 2, y); cv.drawLine(x, y, x + width, y, paint)
            if (p > .01f) { paint.color = s.accent(x + width / 2, y); cv.drawLine(x, y, x + width * p.coerceIn(0f, 1f), y, paint) }
        }

        /**
         * The book: its jacket drawn at the true pixel size of [r], small rounded corners (sharper at
         * the spine), a soft shadow under it, a sliver of page edges at the fore-edge, the hinge, and
         * a little light across the board. Shadows here are on the object, never on type.
         */
        private fun drawBook(c: Context, cv: Canvas, r: RectF, d: Float, a: Article) {
            val edge = max(2.5f, r.width() * .045f)
            val rad = max(2f, r.width() * .04f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = withAlpha(0x000000, .3f); p.setShadowLayer(6f, 0f, 2.5f, withAlpha(0x000000, .42f))
            cv.drawRoundRect(RectF(r.left, r.top, r.right + edge, r.bottom), rad, rad, p); p.clearShadowLayer()
            p.color = 0xFFEDE6D6.toInt()
            cv.drawRoundRect(RectF(r.right - rad, r.top + edge * .8f, r.right + edge, r.bottom - edge * .8f), rad * .6f, rad * .6f, p)
            p.color = withAlpha(0x8A7F70, .5f); p.strokeWidth = .35f
            var x = r.right + .7f
            while (x < r.right + edge - .3f) { cv.drawLine(x, r.top + edge * 1.2f, x, r.bottom - edge * 1.2f, p); x += .75f }
            val pw = (r.width() * d).roundToInt().coerceAtLeast(1); val ph = (r.height() * d).roundToInt().coerceAtLeast(1)
            val jacket = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            Jacket.draw(Canvas(jacket), pw, ph, a, c)
            cv.save()
            cv.clipPath(Path().apply { addRoundRect(r, floatArrayOf(rad * .4f, rad * .4f, rad, rad, rad, rad, rad * .4f, rad * .4f), Path.Direction.CW) })
            cv.drawBitmap(jacket, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            p.color = withAlpha(0x000000, .2f); p.strokeWidth = max(.6f, r.width() * .012f)
            cv.drawLine(r.left + r.width() * .045f, r.top, r.left + r.width() * .045f, r.bottom, p)
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(withAlpha(0xFFFFFF, .09f), withAlpha(0xFFFFFF, 0f), withAlpha(0x000000, .08f)), null, Shader.TileMode.CLAMP)
            cv.drawRect(r, p); p.shader = null
            cv.restore()
        }

        /**
         * Small: the jacket fills the widget within its corners; a shade rises from the foot and the
         * title sits on it in ivory, which the shade keeps far above 7:1 whatever the jacket.
         */
        private fun cover(c: Context, cv: Canvas, w: Float, h: Float, d: Float, radius: Float, k: Float, a: Article) {
            val shape = Shapes.squircle(RectF(0f, 0f, w, h), radius)
            cv.save(); cv.clipPath(shape)
            val pw = (w * d).roundToInt().coerceAtLeast(1); val ph = (h * d).roundToInt().coerceAtLeast(1)
            val jacket = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            Jacket.draw(Canvas(jacket), pw, ph, a, c)
            cv.drawBitmap(jacket, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            val pad = 10f * k
            val t = ink(c, 13.5f * k, Kit.Fixed.IVORY, 600)
            val lines = wrap(a.title, t, w - 2 * pad, 2)
            val m = ink(c, 11.5f * k, withAlpha(Kit.Fixed.IVORY and 0xFFFFFF, .82f), 500)
            val block = lines.size * t.textSize * 1.2f + m.textSize * 1.4f + pad * 1.6f
            cv.drawRect(0f, h - block * 1.7f, w, h, Paint().apply {
                shader = LinearGradient(0f, h - block * 1.7f, 0f, h, intArrayOf(withAlpha(0x000000, 0f), withAlpha(0x000000, .62f), withAlpha(0x000000, .86f)),
                    floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            })
            var y = h - block + pad * .6f
            lines.forEach { l -> cv.drawText(l, pad, y + capTop(t), t); y += t.textSize * 1.2f }
            cv.drawText(TextUtils.ellipsize(left(a), m, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, y + 4f * k + capTop(m), m)
            cv.restore()
            // The finish's edge, so the jacket reads as one of the set
            cv.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f / d; color = withAlpha(0xFFFFFF, .22f) })
        }
    }
}

/** The widget's finish and corners: Fold Widgets' when it is installed. */
class WidgetLook(val finish: Finish, val radius: Float)

/** Fold Widgets says its look or the wallpaper changed: redraw to match. */
class NightstandLook : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == "com.cerniera.LOOK_CHANGED") Nightstand.refresh(c) }
}

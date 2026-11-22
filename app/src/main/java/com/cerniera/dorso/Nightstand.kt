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
import com.cerniera.kit.Grammar
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
 * (21 Nov 2026) from Fold Widgets' live mode, which sees it on the home screen like its own and
 * passes on its place and the wallpaper's, at content://…look/seats/com.cerniera.dorso; until then
 * it reads the wallpaper's overall colour.
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
                    take(app, m, ids)
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

        /** The launcher's placing and tint of the wallpaper behind widget [id], when live mode saw them. */
        private fun live(c: Context, id: Int): Backdrop.LiveView? = runCatching {
            val v = store(c).getString("live_$id", null)!!.split(",")
            Backdrop.LiveView(v[0].toFloat(), v[1].toFloat(), floatArrayOf(v[2].toFloat(), v[3].toFloat(), v[4].toFloat()), v[5].toInt(), v[6].toInt())
        }.getOrNull()

        /**
         * Takes up where Fold Widgets' live mode last saw Dorso's widgets, if that is new: each seen
         * place goes to the Dorso widget nearest its last known place, else closest in size.
         */
        private fun take(c: Context, m: AppWidgetManager, ids: IntArray) {
            class Seen(val l: Float, val t: Float, val w: Float, val h: Float, val sw: Int, val sh: Int, val live: Backdrop.LiveView?)
            val seen = ArrayList<Seen>(); var at = 0L
            runCatching {
                c.contentResolver.query(Uri.parse("content://$AUTHORITY/seats/${c.packageName}"), null, null, null, null)?.use { cur ->
                    fun f(n: String) = cur.getFloat(cur.getColumnIndexOrThrow(n))
                    fun i(n: String) = cur.getInt(cur.getColumnIndexOrThrow(n))
                    while (cur.moveToNext()) {
                        // An older Fold Widgets has no package column and passes on Nodo's places only
                        val pk = cur.getColumnIndex("package")
                        if (pk < 0 || cur.getString(pk) != c.packageName) continue
                        val sw = i("screen_w"); val sh = i("screen_h")
                        val live = if (i("live") == 1) Backdrop.LiveView(f("ox"), f("oy"), floatArrayOf(f("gain_r"), f("gain_g"), f("gain_b")), sw, sh) else null
                        seen += Seen(f("left"), f("top"), f("width"), f("height"), sw, sh, live)
                        at = cur.getLong(cur.getColumnIndexOrThrow("at"))
                    }
                }
            }
            val st = store(c)
            if (seen.isEmpty() || st.getLong("seatsAt", 0L) == at) return
            val screen = Backdrop.screenSize(c)
            val here = seen.filter { it.sw == screen.x && it.sh == screen.y }
            if (here.isEmpty()) return
            val d = Backdrop.density(c)
            val claimed = HashSet<Int>(); val e = st.edit()
            for (s in here) {
                fun distance(id: Int): Float {
                    val old = Backdrop.seat(st, id)
                    if (old != null && old.screenW == s.sw && old.screenH == s.sh)
                        return Math.abs(old.left - s.l) + Math.abs(old.top - s.t) + 3f * (Math.abs(old.width - s.w) + Math.abs(old.height - s.h))
                    return 400f + 3f * (sizes(m, id).minOfOrNull { Math.abs(it.width * d - s.w) + Math.abs(it.height * d - s.h) } ?: 400f)
                }
                val id = ids.filter { it !in claimed }.minByOrNull { distance(it) } ?: break
                claimed += id
                e.putString("seat_$id", Backdrop.Seat(s.l, s.t, s.w, s.h, s.sw, s.sh, true).encode())
                s.live?.let { v -> val g = v.gain ?: floatArrayOf(1f, 1f, 1f); e.putString("live_$id", listOf(v.ox, v.oy, g[0], g[1], g[2], v.screenW, v.screenH).joinToString(",")) } ?: e.remove("live_$id")
            }
            e.putLong("seatsAt", at).apply()
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
            // The density of the screen in use now (on the Fold, the app's own resources can carry the other screen's)
            val d = Backdrop.density(c)
            val ground = Backdrop.ground(c, store(c), id, live(c, id))
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

        /** The author as a byline would give it, without "By". */
        private fun byline(a: Article): String? = a.byline.replace(Regex("""^[Bb]y\s+"""), "").trim().takeIf { it.isNotBlank() }

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

        private fun capTop(p: Paint) = -p.fontMetrics.ascent * .72f

        /**
         * The widget at [w] x [h] dp, drawn at [density] (kept within what a launcher accepts), in
         * [look]'s finish over [ground]. Composed from the family's widget grammar (Kit Grammar,
         * 22 Nov 2026), so it shares Fold Widgets' shapes, scale, inset, label and foot:
         *  - COLUMN: the book as wide as the widget allows, its words beneath, the line at the foot.
         *    (Before: a book capped at three quarters of the width floated in an empty tall box.)
         *  - ROW and STRIP: the book standing at the left, full height; label, title grown to fill
         *    the column, author, and the foot beside it.
         *  - SQUARE: the book at the top left with the label and author beside it, the title across
         *    the full width resting on the foot (before: a title squeezed beside the book into
         *    "Enthusi… Who…").
         *  - Small: the jacket, full bleed, with a band of the finish at the foot.
         */
        fun paint(c: Context, w: Float, h: Float, density: Float, look: WidgetLook, ground: Ground, a: Article?): Bitmap {
            val d = min(density, sqrt(1_600_000f / (w * h)))
            val bmp = Bitmap.createBitmap(max(1, (w * d).toInt()), max(1, (h * d).toInt()), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.scale(d, d)
            val radius = min(look.radius, min(w, h) / 2)
            val k = Grammar.scale(w, h)
            val pad = Grammar.inset(w, h, k)
            val surface = Surface(look.finish, ground, w, h, radius, 1.1f / d)

            if (a == null) {
                surface.paint(cv)
                Grammar.eyebrow(c, cv, surface, "Dorso", pad, pad, w - 2 * pad, k)
                Grammar.statement(c, cv, surface, "Share a long read", "It waits here, like a book on the nightstand.", pad, h - pad, w - 2 * pad, k)
                return bmp
            }

            // Too small for a book beside its words: the jacket full bleed, the time left on a band
            if (min(w, h) < 90f || (w < 150f && h < 200f)) { cover(c, cv, w, h, d, radius, k, a, surface); return bmp }

            surface.paint(cv)
            val label = if (a.progress >= .01f) "Reading" else "Up next"
            val meta = listOfNotNull(left(a), a.site.takeIf { it.isNotBlank() })
            val mp = Grammar.meta(c, k, surface.muted(w / 2, h - pad - 10f * k))
            val lineY = h - pad - Grammar.LINE * k / 2
            val metaBase = lineY - Grammar.LINE * k / 2 - 8f * k          // the meta line sits just above the progress line
            val footTop = metaBase - Grammar.cap(mp) - Grammar.GAP * k   // where the words above it must end
            val ASPECT = .64f
            // A square under 220 dp is too small for the title across the foot: it takes the row's arrangement
            val shape = Grammar.shape(w, h).let { if (it == Grammar.Shape.SQUARE && min(w, h) < 220f) Grammar.Shape.ROW else it }
            when (shape) {
                Grammar.Shape.COLUMN -> {
                    val colW = w - 2 * pad
                    val top = Grammar.eyebrow(c, cv, surface, label, pad, pad, colW, k)
                    // The book first, as wide as the column (less its page edges); the title then takes the
                    // room beneath, at the largest size that fits in up to four lines
                    val edge = { bw: Float -> max(2.5f, bw * .045f) }
                    val minTitle = Grammar.title(c, a.title, 0, colW, 3 * 14.5f * k * Grammar.LEAD, k, 3, largest = 14.5f)
                    var bw = colW - edge(colW); var bh = bw / ASPECT
                    val maxBh = footTop - top - Grammar.GAP * k - minTitle.height - Grammar.GAP * k
                    if (bh > maxBh) { bh = maxBh; bw = bh * ASPECT }
                    val room = footTop - (top + bh + Grammar.GAP * k * 1.4f)
                    val t = Grammar.title(c, a.title, surface.ink(w / 2, h * .8f), colW, room, k, 4, largest = 32f)
                    // Whatever is still left is shared: above the book and between book and title
                    val spare = (room - t.height).coerceAtLeast(0f)
                    val bookTop = top + spare / 2
                    drawBook(c, cv, RectF(pad + (colW - bw - edge(bw)) / 2, bookTop, pad + (colW - bw - edge(bw)) / 2 + bw, bookTop + bh), d, a)
                    t.draw(cv, pad, footTop - t.height)
                    metaLine(cv, meta, mp, pad, metaBase, colW)
                    Grammar.line(cv, surface, pad, lineY, colW, a.progress, k)
                }
                Grammar.Shape.SQUARE -> {
                    val colW = w - 2 * pad
                    // The title across the full width, resting on the foot; the book takes the height above it
                    val t0 = Grammar.title(c, a.title, surface.ink(w / 2, h * .7f), colW, (footTop - pad) * .42f, k, 3, largest = 24f)
                    val bookBottom = footTop - t0.height - Grammar.GAP * k * 1.4f
                    var bh = bookBottom - pad; var bw = bh * ASPECT
                    if (bw > colW * .46f) { bw = colW * .46f; bh = bw / ASPECT }
                    drawBook(c, cv, RectF(pad, bookBottom - bh, pad + bw, bookBottom), d, a)
                    val x = pad + bw + max(2.5f, bw * .045f) + 14f * k; val sideW = w - pad - x
                    var y = Grammar.eyebrow(c, cv, surface, label, x, bookBottom - bh, sideW, k)
                    byline(a)?.let { by ->
                        val bp = Grammar.meta(c, k, surface.muted(x + sideW / 2, y + 6f))
                        for (l in Grammar.wrap(by, bp, sideW, 2)) { cv.drawText(l, x, y + Grammar.cap(bp), bp); y += bp.textSize * 1.3f }
                    }
                    t0.draw(cv, pad, footTop - t0.height)
                    metaLine(cv, meta, mp, pad, metaBase, colW)
                    Grammar.line(cv, surface, pad, lineY, colW, a.progress, k)
                }
                else -> {
                    // ROW and STRIP: the book at the left, its words beside it
                    var bh = h - 2 * pad; var bw = bh * ASPECT
                    if (bw > w * .42f) { bw = w * .42f; bh = bw / ASPECT }
                    val book = RectF(pad, (h - bh) / 2, pad + bw, (h + bh) / 2)
                    drawBook(c, cv, book, d, a)
                    val x = book.right + max(2.5f, bw * .045f) + 16f * k; val colW = w - x - pad
                    val y = Grammar.eyebrow(c, cv, surface, label, x, pad, colW, k)
                    val by = byline(a)
                    val bp = Grammar.meta(c, k, surface.muted(x + colW / 2, h / 2))
                    val byH = if (by != null) bp.textSize * 1.6f else 0f
                    // The title grows to fill the column between the label and the foot
                    val t = Grammar.title(c, a.title, surface.ink(x + colW / 2, h / 2), colW, footTop - y - byH, k, 5, largest = 28f)
                    val end = t.draw(cv, x, y)
                    if (by != null && end + byH <= footTop + 1f) cv.drawText(Grammar.fit(by, bp, colW), x, end + 6f * k + Grammar.cap(bp), bp)
                    metaLine(cv, meta, mp, x, metaBase, colW)
                    Grammar.line(cv, surface, x, lineY, colW, a.progress, k)
                }
            }
            return bmp
        }

        /** Time left and where it's from, the place dropped first when there is no room. */
        private fun metaLine(cv: Canvas, parts: List<String>, p: TextPaint, x: Float, baseline: Float, width: Float) {
            var s = parts.joinToString("  ·  ")
            if (p.measureText(s) > width) s = parts.first()
            cv.drawText(TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString(), x, baseline, p)
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
         * Small: the jacket fills the widget within its corners, untouched (it carries its own title),
         * and a slim band of the finish at the foot holds the time left and the progress line, in the
         * finish's proven ink, as a label strip on a book.
         */
        private fun cover(c: Context, cv: Canvas, w: Float, h: Float, d: Float, radius: Float, k: Float, a: Article, surface: Surface) {
            val shape = Shapes.squircle(RectF(0f, 0f, w, h), radius)
            val band = (30f * k).coerceAtMost(h * .3f)
            // The finish everywhere first (so the band is the finish itself), then the jacket above the band
            surface.paint(cv)
            cv.save(); cv.clipPath(shape); cv.clipRect(0f, 0f, w, h - band)
            val pw = (w * d).roundToInt().coerceAtLeast(1); val ph = ((h - band) * d).roundToInt().coerceAtLeast(1)
            val jacket = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            Jacket.draw(Canvas(jacket), pw, ph, a, c)
            cv.drawBitmap(jacket, null, RectF(0f, 0f, w, h - band), Paint(Paint.FILTER_BITMAP_FLAG))
            cv.restore()
            val pad = 10f * k
            val m = ink(c, 11.5f * k, surface.ink(w / 2, h - band / 2), 600)
            val y = h - band
            cv.drawText(TextUtils.ellipsize(left(a), m, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, y + band * .42f + capTop(m) / 2, m)
            Grammar.line(cv, surface, pad, h - band * .22f, w - 2 * pad, a.progress, k * .85f)
        }
    }
}

/** The widget's finish and corners: Fold Widgets' when it is installed. */
class WidgetLook(val finish: Finish, val radius: Float)

/** Fold Widgets says its look or the wallpaper changed: redraw to match. */
class NightstandLook : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (i.action == "com.cerniera.LOOK_CHANGED") Nightstand.refresh(c) }
}

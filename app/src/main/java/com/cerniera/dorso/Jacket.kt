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
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The book's jacket, on the cover screen: the phone shut is the book shut. Six designs, each a
 * different tradition of the printed cover, all grown from the read itself (its title, author and
 * site; the drawing seeded by the title, so each read looks like itself), and on every one a
 * champagne ribbon from the head whose length is how far in you are. Swipe across the cover to try
 * another; the one you stop on is kept with the read.
 *
 *  - Bosco: a literary paperback. Author in large spaced capitals, the title slanted beneath, a
 *    stippled treeline at the foot (Solco's grain).
 *  - Classico: the three-band paperback. Coloured bands top and foot, a cream band for the title,
 *    a cartouche with the house's D.
 *  - Svizzero: the Swiss grid. The title huge and flush left in bold sans, ruled columns of facts.
 *  - Tela: a clothbound hardback. Woven cloth, a blind-stamped border, the title stamped in gilt.
 *  - Marmo: marbled boards with a printed paper label.
 *  - Luna: a night piece. A stippled moon over a dark field, the title in champagne beneath.
 *
 * Drawn once per read, design and size, then turned like any page.
 */
object Jacket {
    val names = listOf("Bosco", "Classico", "Svizzero", "Tela", "Marmo", "Luna")

    /** The design a read starts with, before you choose: its own, from its title. */
    fun auto(a: Article) = Math.floorMod((a.title + a.url).hashCode(), names.size)

    fun draw(c: Canvas, w: Int, h: Int, a: Article, ctx: Context, style: Int = if (a.cover >= 0) a.cover else auto(a)) {
        when (Math.floorMod(style, names.size)) {
            1 -> classico(c, w, h, a, ctx)
            2 -> svizzero(c, w, h, a, ctx)
            3 -> tela(c, w, h, a, ctx)
            4 -> marmo(c, w, h, a, ctx)
            5 -> luna(c, w, h, a, ctx)
            else -> bosco(c, w, h, a, ctx)
        }
        ribbon(c, w, h, a, ctx)
    }

    private fun bosco(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
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
        val markY = y + h * .04f

        // The treeline, in stipple, rising from one corner
        trees(c, w, h, max(y + h * .06f, h * .6f), rnd, ink, dp)

        // The site at the foot, small, like a publisher's mark
        val mark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Kit.sans(ctx, 500); color = withAlpha(ink and 0xFFFFFF, .7f)
            textSize = 9.5f * dp; letterSpacing = .3f; textAlign = Paint.Align.LEFT
        }
        if (a.byline.isNotBlank() && a.site.isNotBlank()) { mark.textAlign = Paint.Align.CENTER; c.drawText(a.site.uppercase(), w / 2f, markY, mark) }
    }

    /** The ribbon: from the head, its length how far you've read. */
    private fun ribbon(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val margin = w * .1f
        // The ribbon: from the head, its length how far you've read
        val p = a.progress.coerceIn(0f, 1f)
        if (p > .005f) {
            val rx = w - margin * .38f; val rw = 6f * dp
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
            note.textAlign = Paint.Align.CENTER
            c.drawText("${(p * 100).toInt()}%", rx, len + 13f * dp, note)
        }

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
        // Trees: each crown a cluster of lobes, standing on a trunk, along the high side
        class Tree(val x: Float, val ground: Float, val top: Float, val lobes: List<FloatArray>)
        val trees = ArrayList<Tree>()
        val count = 7 + rnd.nextInt(5)
        repeat(count) {
            val t = rnd.nextFloat().pow(.6f)
            val x = if (fromRight) w * (1 - t * .9f) else w * t * .9f
            val g = ridge(x)
            val height = span * (.3f + .5f * (1 - t) * (.6f + .4f * rnd.nextFloat()))
            val r = w * (.045f + .05f * rnd.nextFloat()) * (1.25f - t * .5f)
            val crownTop = g - height
            val lobes = ArrayList<FloatArray>()
            val nl = 4 + rnd.nextInt(4)
            repeat(nl) { k ->
                val f = k.toFloat() / (nl - 1)
                val ly = crownTop + r * .9f + f * (height * .62f - r)
                val spread = r * (.35f + .65f * sin(PI.toFloat() * (.25f + .6f * f)))
                lobes += floatArrayOf(x + (rnd.nextFloat() - .5f) * 2 * spread * .6f, ly, r * (.55f + .45f * rnd.nextFloat()) * (.7f + .5f * sin(PI.toFloat() * (.2f + .7f * f))))
            }
            trees += Tree(x, g, crownTop, lobes)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = (w * span * 1.1f / (3.2f * dp * dp)).toInt().coerceAtMost(110000)
        repeat(n) {
            val x = rnd.nextFloat() * w
            val y = top - span * .1f + rnd.nextFloat() * span * 1.1f
            var d = 0f
            val g = ridge(x)
            if (y > g) d = .3f + .6f * ((y - g) / max(1f, base - g)).coerceIn(0f, 1f).pow(.7f)
            for (t in trees) {
                if (abs(x - t.x) < t.lobes[0][2] * .09f && y in t.top..t.ground) d = max(d, .85f)
                for (l in t.lobes) {
                    val dx = (x - l[0]) / l[2]; val dy = (y - l[1]) / (l[2] * 1.1f)
                    val e = dx * dx + dy * dy
                    if (e < 1f) {
                        // Dense at the heart, lighter towards the edge, the edge itself crisp; the lit side paler
                        val lit = ((x - l[0]) / l[2] * (if (fromRight) -1f else 1f) + (l[1] - y) / l[2]) * .18f
                        d = max(d, (.92f - .45f * e - lit).coerceIn(.18f, .95f))
                    }
                }
            }
            if (d > 0f && rnd.nextFloat() < d * d) {
                dot.color = withAlpha(ink and 0xFFFFFF, .62f + .35f * rnd.nextFloat())
                c.drawCircle(x, y, (.42f + .4f * rnd.nextFloat()) * dp, dot)
            }
        }
    }

    private const val rnd0 = (PI / 3).toFloat()
    // ---- The other designs

    private fun seeded(a: Article) = Random((a.title + a.url).hashCode())
    private fun author(a: Article) = clean(a.byline.uppercase()).let { if (it.isBlank()) "" else it }
    private fun paint(ctx: Context, face: android.graphics.Typeface, colour: Int, spacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; color = colour; letterSpacing = spacing }

    /** Text set in [col] from [x] (left, centre or right per [align]), returning the bottom. */
    private fun set(c: Canvas, s: String, p: TextPaint, x: Float, y: Float, col: Int, lines: Int, spacing: Float,
                    align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER): Float {
        if (s.isBlank()) return y
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, col).setAlignment(align)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setLineSpacing(0f, spacing)
            .setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    /** Classico: coloured bands top and foot, a cream band for the title, the house's cartouche. */
    private fun classico(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val bands = intArrayOf(0xFFD3612A.toInt(), 0xFF2E6B4E.toInt(), 0xFF2C4C7C.toInt(), 0xFF7B2D3B.toInt(), 0xFF5B3A6B.toInt(), 0xFF2A6A6E.toInt())
        val band = bands[Math.floorMod(a.site.hashCode(), bands.size)]
        val cream = 0xFFF2EADB.toInt(); val ink = 0xFF221E1A.toInt()
        c.drawColor(cream)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = h * .25f; val foot = h * .75f
        p.color = band; c.drawRect(0f, 0f, w.toFloat(), top, p); c.drawRect(0f, foot, w.toFloat(), h.toFloat(), p)
        // The fine cream rules set inside each band, as printed
        p.color = cream; p.strokeWidth = 1.2f * dp
        c.drawLine(0f, top - 7 * dp, w.toFloat(), top - 7 * dp, p); c.drawLine(0f, foot + 7 * dp, w.toFloat(), foot + 7 * dp, p)
        val margin = w * .1f; val col = (w - 2 * margin).toInt()
        val site = paint(ctx, Kit.sans(ctx, 600), cream, .32f).apply { textSize = w * .045f }
        set(c, a.site.uppercase().ifBlank { "DORSO" }, site, margin, top * .42f, col, 1, 1f)
        val title = paint(ctx, Kit.sans(ctx, 500), ink, .02f)
        title.textSize = fit(a.title, title, col, w * .085f, w * .05f, 4)
        val tl = StaticLayout.Builder.obtain(a.title, 0, a.title.length, title, col).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setLineSpacing(0f, 1.12f).setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        val by = author(a)
        val byP = paint(ctx, Kit.sans(ctx, 400), withAlpha(ink and 0xFFFFFF, .8f), .2f).apply { textSize = w * .034f }
        val blockH = tl.height + (if (by.isNotEmpty()) 30 * dp + byP.textSize else 0f)
        var y = (top + foot) / 2 - blockH / 2
        c.save(); c.translate(margin, y); tl.draw(c); c.restore(); y += tl.height
        if (by.isNotEmpty()) {
            p.color = band; p.strokeWidth = 1.2f * dp; c.drawLine(w / 2f - 14 * dp, y + 13 * dp, w / 2f + 14 * dp, y + 13 * dp, p)
            set(c, by, byP, margin, y + 24 * dp, col, 1, 1f)
        }
        // The cartouche: a cream oval with the house's D
        val cx = w / 2f; val cy = (foot + h) / 2f + 3 * dp; val rx = w * .095f; val ry = w * .065f
        p.style = Paint.Style.FILL; p.color = cream; c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p)
        p.style = Paint.Style.STROKE; p.color = band; p.strokeWidth = 1f * dp; c.drawOval(cx - rx + 3 * dp, cy - ry + 3 * dp, cx + rx - 3 * dp, cy + ry - 3 * dp, p)
        p.style = Paint.Style.FILL
        val d = paint(ctx, Kit.display(ctx), band).apply { textSize = ry * 1.35f; textAlign = Paint.Align.CENTER }
        c.drawText("D", cx, cy + d.textSize * .34f, d)
    }

    /** Svizzero: the Swiss grid. The title huge and flush left, ruled columns of facts, one bold disc. */
    private fun svizzero(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val schemes = arrayOf(
            intArrayOf(0xFFEDEAE3.toInt(), 0xFF141414.toInt(), 0xFFD7262E.toInt()),
            intArrayOf(0xFF171716.toInt(), 0xFFF1EEE7.toInt(), 0xFFE5B12C.toInt()),
            intArrayOf(0xFF1E3E8C.toInt(), 0xFFF4F1EA.toInt(), 0xFFE9573F.toInt()),
        )
        val (bg, ink, accent) = schemes[Math.floorMod((a.title).hashCode(), schemes.size)].let { Triple(it[0], it[1], it[2]) }
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // The disc, bold, partly off the page
        p.color = accent; c.drawCircle(w * .86f, h * .86f, w * .52f, p)
        val m = w * .08f; val col = (w - 2 * m).toInt()
        // The head: two columns of facts over a rule
        val fact = paint(ctx, Kit.sans(ctx, 500), ink, .06f).apply { textSize = w * .034f }
        c.drawText(a.site.ifBlank { "Dorso" }, m, h * .055f + fact.textSize, fact)
        fact.textAlign = Paint.Align.RIGHT; c.drawText("${a.minutes} min", w - m, h * .055f + fact.textSize, fact); fact.textAlign = Paint.Align.LEFT
        p.color = ink; p.strokeWidth = 1.6f * dp
        val rule = h * .055f + fact.textSize * 1.8f
        c.drawLine(m, rule, w - m, rule, p)
        // The title: as large as it will go, flush left, tight
        val t = paint(ctx, Kit.sans(ctx, 700), ink, -.02f)
        t.textSize = fit(a.title, t, col, w * .15f, w * .075f, 6)
        set(c, a.title, t, m, rule + h * .035f, col, 6, .98f, Layout.Alignment.ALIGN_NORMAL)
        // The foot: author, and the house, in the grid's columns
        p.strokeWidth = 1f * dp; c.drawLine(m, h * .8f, w * .55f, h * .8f, p)
        val by = a.byline.replace(Regex("""^[Bb]y\s+"""), "").take(40)
        val foot = paint(ctx, Kit.sans(ctx, 600), ink, .02f).apply { textSize = w * .04f }
        if (by.isNotBlank()) set(c, by, foot, m, h * .8f + 10 * dp, (w * .47f).toInt(), 2, 1.1f, Layout.Alignment.ALIGN_NORMAL)
        val house = paint(ctx, Kit.sans(ctx, 500), withAlpha(ink and 0xFFFFFF, .7f), .3f).apply { textSize = w * .028f }
        c.drawText("DORSO", m, h - m * .9f, house)
    }

    /** Tela: a clothbound hardback, the title stamped in gilt within a blind-stamped border. */
    private fun tela(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val cloths = intArrayOf(0xFF5A1A20.toInt(), 0xFF1C2A44.toInt(), 0xFF1F3A2C.toInt(), 0xFF4E3220.toInt())
        val cloth = cloths[Math.floorMod(a.site.hashCode() + a.title.length, cloths.size)]
        c.drawColor(cloth)
        val r = seeded(a)
        // The weave: fine threads both ways, each a shade lighter or darker than the cloth
        val p = Paint(); p.strokeWidth = .9f * dp
        var x = 0f
        while (x < w) { p.color = withAlpha(if (r.nextBoolean()) 0xFFFFFF else 0x000000, .025f + .04f * r.nextFloat()); c.drawLine(x, 0f, x, h.toFloat(), p); x += 1.3f * dp }
        var y = 0f
        while (y < h) { p.color = withAlpha(if (r.nextBoolean()) 0xFFFFFF else 0x000000, .025f + .045f * r.nextFloat()); c.drawLine(0f, y, w.toFloat(), y, p); y += 1.3f * dp }
        // The joint: the board's hinge groove near the spine
        p.strokeWidth = 2.2f * dp; p.color = withAlpha(0x000000, .35f); c.drawLine(w * .055f, 0f, w * .055f, h.toFloat(), p)
        p.strokeWidth = 1f * dp; p.color = withAlpha(0xFFFFFF, .08f); c.drawLine(w * .055f + 2.5f * dp, 0f, w * .055f + 2.5f * dp, h.toFloat(), p)
        // Blind-stamped border: pressed in, so dark above and light below each line
        fun blind(inset: Float) {
            val l = w * .055f + inset; val t = inset; val rr = w - inset; val b = h - inset
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * dp
            p.color = withAlpha(0x000000, .38f); c.drawRect(l, t, rr, b, p)
            p.color = withAlpha(0xFFFFFF, .09f); c.drawRect(l + 1.2f * dp, t + 1.2f * dp, rr + 1.2f * dp, b + 1.2f * dp, p)
            p.style = Paint.Style.FILL
        }
        blind(w * .06f); blind(w * .075f)
        // Gilt: a gold that catches the light across its face
        fun gilt(t: TextPaint, top: Float, bottom: Float) {
            t.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
                intArrayOf(0xFFF3DDA5.toInt(), 0xFFC9A15A.toInt(), 0xFF8E6B33.toInt(), 0xFFE2C27F.toInt()),
                floatArrayOf(0f, .45f, .7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        val m = w * .17f; val col = (w - m - w * .12f).toInt()
        val title = paint(ctx, Kit.display(ctx), 0xFFE6C987.toInt(), .1f)
        title.textSize = fit(a.title.uppercase(), title, col, w * .078f, w * .05f, 5)
        val tl = StaticLayout.Builder.obtain(a.title.uppercase(), 0, a.title.uppercase().length, title, col).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setLineSpacing(0f, 1.25f).setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        val ty = h * .36f - tl.height / 2f
        gilt(title, ty, ty + tl.height)
        // The stamp sits slightly into the cloth: a dark edge above, then the gold
        val shadow = TextPaint(title).apply { shader = null; color = withAlpha(0x000000, .45f) }
        val sl = StaticLayout.Builder.obtain(a.title.uppercase(), 0, a.title.uppercase().length, shadow, col).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setLineSpacing(0f, 1.25f).setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        val lx = w * .055f + (w - w * .055f - col) / 2f
        c.save(); c.translate(lx, ty - 1.2f * dp); sl.draw(c); c.restore()
        c.save(); c.translate(lx, ty); tl.draw(c); c.restore()
        // A gilt panel of double rules around the title, and a fleuron beneath
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8B873.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * dp }
        val pl = lx - 14 * dp; val pt = ty - 18 * dp; val pr = lx + col + 14 * dp; val pb = ty + tl.height + 18 * dp
        c.drawRect(pl, pt, pr, pb, gp); c.drawRect(pl + 4 * dp, pt + 4 * dp, pr - 4 * dp, pb - 4 * dp, gp)
        gp.style = Paint.Style.FILL
        val fx = (pl + pr) / 2; val fy = pb + 30 * dp; val fs = 6 * dp
        c.drawPath(Path().apply { moveTo(fx, fy - fs); lineTo(fx + fs, fy); lineTo(fx, fy + fs); lineTo(fx - fs, fy); close() }, gp)
        gp.strokeWidth = .9f * dp; c.drawLine(fx - 34 * dp, fy, fx - fs - 5 * dp, fy, gp); c.drawLine(fx + fs + 5 * dp, fy, fx + 34 * dp, fy, gp)
        val by = author(a).ifBlank { a.site.uppercase() }
        val byP = paint(ctx, Kit.sans(ctx, 500), 0xFFD8B873.toInt(), .3f).apply { textSize = w * .032f }
        set(c, by, byP, lx, h * .86f, col, 1, 1f)
    }

    /** Marmo: marbled boards, a printed paper label pasted on. */
    private fun marmo(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val r = seeded(a)
        val palettes = arrayOf(
            intArrayOf(0xFF1F2B4A.toInt(), 0xFFE9DFC9.toInt(), 0xFFB5552F.toInt(), 0xFFC9A04F.toInt()),
            intArrayOf(0xFF233A31.toInt(), 0xFFEDE4D1.toInt(), 0xFF8C2F2B.toInt(), 0xFF6F8F86.toInt()),
            intArrayOf(0xFF3A2440.toInt(), 0xFFF0E6D6.toInt(), 0xFF2F5E7A.toInt(), 0xFFD1A75A.toInt()),
        )
        val pal = palettes[Math.floorMod(a.title.hashCode() / 7, palettes.size)]
        // The marbling: combed bands of colour, pulled about by layered swirls (domain warping),
        // drawn small and laid over the board smoothly
        val bw = 220; val bh = (bw * h / w.toFloat()).toInt().coerceAtLeast(1)
        val px = IntArray(bw * bh)
        val f = FloatArray(6) { 1.5f + 5f * r.nextFloat() }; val ph = FloatArray(6) { 6.28f * r.nextFloat() }
        for (j in 0 until bh) for (i in 0 until bw) {
            val u = i / bw.toFloat(); val v = j / bh.toFloat() * h / w
            val wx = sin(v * f[0] + ph[0]) * .35f + sin((u + v) * f[1] + ph[1]) * .18f
            val wy = sin(u * f[2] + ph[2]) * .35f + sin((u - v) * f[3] + ph[3]) * .15f
            val q = sin((u + wx) * f[4] * 2.2f + sin((v + wy) * f[5] * 1.6f) * 2.4f + ph[4])
            val vein = sin((v + wy * 1.4f) * 38f + q * 3f)
            val col = when {
                vein > .92f -> pal[3]
                q > .45f -> pal[2]
                q > -.15f -> pal[1]
                else -> pal[0]
            }
            px[j * bw + i] = col
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        c.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        // The label: cream paper with a double rule, a soft shadow where it sits on the board
        val lw = w * .66f; val lh = h * .27f; val lx = (w - lw) / 2; val ly = h * .27f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = withAlpha(0x000000, .28f); c.drawRect(lx + 2 * dp, ly + 3 * dp, lx + lw + 2 * dp, ly + lh + 3 * dp, p)
        p.color = 0xFFF1E9D7.toInt(); c.drawRect(lx, ly, lx + lw, ly + lh, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF2A2420.toInt(); p.strokeWidth = 1.4f * dp
        c.drawRect(lx + 6 * dp, ly + 6 * dp, lx + lw - 6 * dp, ly + lh - 6 * dp, p)
        p.strokeWidth = .7f * dp; c.drawRect(lx + 9 * dp, ly + 9 * dp, lx + lw - 9 * dp, ly + lh - 9 * dp, p)
        p.style = Paint.Style.FILL
        val col = (lw - 34 * dp).toInt()
        val t = paint(ctx, Kit.display(ctx), 0xFF221C18.toInt(), .04f)
        t.textSize = fit(a.title, t, col, w * .07f, w * .042f, 4)
        val tl = StaticLayout.Builder.obtain(a.title, 0, a.title.length, t, col).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setLineSpacing(0f, 1.15f).setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        val by = author(a).ifBlank { a.site.uppercase() }
        val byP = paint(ctx, Kit.sans(ctx, 500), 0xFF5A4E44.toInt(), .25f).apply { textSize = w * .028f }
        val total = tl.height + 16 * dp + byP.textSize
        var y = ly + (lh - total) / 2
        c.save(); c.translate(lx + 17 * dp, y); tl.draw(c); c.restore(); y += tl.height + 16 * dp
        set(c, by, byP, lx + 17 * dp, y, col, 1, 1f)
    }

    /** Luna: a night piece, a stippled moon over a dark field, the title in champagne. */
    private fun luna(c: Canvas, w: Int, h: Int, a: Article, ctx: Context) {
        val dp = ctx.resources.displayMetrics.density
        val r = seeded(a)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0B1020.toInt(), 0xFF18203A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        // Stars, a few bright, most faint
        repeat(260) {
            val b = r.nextFloat().pow(3f)
            p.color = withAlpha(0xF3ECDD, .15f + .75f * b)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * .62f, (.4f + 1.1f * b) * dp, p)
        }
        // The moon: a disc of stipple, lit to a phase grown from the title, with darker seas
        val cx = w * .5f; val cy = h * .33f; val rad = w * .27f
        val phase = .25f + .7f * r.nextFloat()           // how much of the disc is lit, from the right
        val seas = List(6) { floatArrayOf(cx + (r.nextFloat() - .5f) * rad * 1.1f, cy + (r.nextFloat() - .5f) * rad * 1.1f, rad * (.15f + .25f * r.nextFloat())) }
        val n = (rad * rad * 3.14f / (2.1f * dp * dp)).toInt().coerceAtMost(70000)
        repeat(n) {
            val ang = r.nextFloat() * 6.2832f; val rr = rad * kotlin.math.sqrt(r.nextFloat())
            val x = cx + rr * kotlin.math.cos(ang); val y = cy + rr * sin(ang)
            // Lit if right of the terminator (an ellipse across the disc)
            val yy = (y - cy) / rad; val half = kotlin.math.sqrt((1 - yy * yy).coerceAtLeast(0f))
            val term = cx + rad * half * (1 - 2 * phase)
            var d = if (x > term) .9f else .06f
            val limb = (rr / rad); d *= (1f - .35f * limb.pow(6f))
            for (s in seas) { val dx = x - s[0]; val dy = y - s[1]; if (dx * dx + dy * dy < s[2] * s[2]) d *= .62f }
            if (r.nextFloat() < d) {
                p.color = withAlpha(0xF3ECDD, .55f + .45f * r.nextFloat())
                c.drawCircle(x, y, (.45f + .35f * r.nextFloat()) * dp, p)
            }
        }
        val m = w * .1f; val col = (w - 2 * m).toInt()
        val t = paint(ctx, Kit.display(ctx), 0xFFD9BB86.toInt(), .2f)
        t.textSize = fit(a.title.uppercase(), t, col, w * .068f, w * .043f, 5)
        val y = set(c, a.title.uppercase(), t, m, h * .64f, col, 5, 1.3f)
        val by = author(a)
        val byP = paint(ctx, Kit.sans(ctx, 500), withAlpha(0xF3ECDD, .75f), .3f).apply { textSize = w * .03f }
        set(c, by.ifBlank { a.site.uppercase() }, byP, m, max(y + 22 * dp, h * .86f), col, 1, 1f)
    }
}

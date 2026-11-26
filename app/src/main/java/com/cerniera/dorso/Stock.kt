package com.cerniera.dorso

import android.content.Context
import com.cerniera.kit.Kit

/**
 * The paper the book is printed on (26 Nov 2026, Mechack: a page colour beyond night, for
 * readability: a sepia-toned stock and a white one).
 *
 * - **Phone:** as before. Visconti after dark, Avorio by day, following the phone's setting.
 * - **Night:** Visconti always: smoked green-black, ivory ink.
 * - **Sepia:** a toned, cream-and-tobacco stock, as an old paperback's; brown-black ink, soft on the eye.
 * - **White:** bright, near-white stock with near-black ink, for daylight and the sharpest contrast.
 *
 * Kit's light ([Kit.dark]) is set to match, so the leaf's lighting, the dog-ears, the grain and
 * the trim all read the stock as day or night paper; only the four page colours come from here.
 */
object Stock {
    enum class Tone(val key: String, val title: String, val detail: String) {
        PHONE("phone", "Match the phone", "Night after dark, Avorio by day"),
        NIGHT("night", "Night", "Ivory ink on smoked green-black"),
        SEPIA("sepia", "Sepia", "A toned paperback stock, brown-black ink"),
        WHITE("white", "White", "Bright paper, near-black ink"),
    }

    @Volatile var tone = Tone.PHONE

    fun load(c: Context) {
        val k = c.getSharedPreferences("dorso", Context.MODE_PRIVATE).getString("paper", Tone.PHONE.key)
        tone = Tone.values().firstOrNull { it.key == k } ?: Tone.PHONE
    }

    fun save(c: Context, t: Tone) {
        tone = t
        c.getSharedPreferences("dorso", Context.MODE_PRIVATE).edit().putString("paper", t.key).apply()
    }

    /** Sets Kit's light for this stock. Call from the reader's onCreate, onResume and config changes. */
    fun apply(c: Context) {
        when (tone) {
            Tone.PHONE -> Kit.follow(c)
            Tone.NIGHT -> Kit.dark = true
            Tone.SEPIA, Tone.WHITE -> Kit.dark = false
        }
    }

    val GROUND: Int get() = when (tone) {
        Tone.SEPIA -> 0xFFF2E5C9.toInt()
        Tone.WHITE -> 0xFFFBFAF7.toInt()
        Tone.NIGHT -> Kit.Fixed.VISCONTI_GROUND
        Tone.PHONE -> Kit.GROUND
    }
    val INK: Int get() = when (tone) {
        Tone.SEPIA -> 0xFF3D2B1B.toInt()
        Tone.WHITE -> 0xFF151515.toInt()
        Tone.NIGHT -> Kit.Fixed.IVORY
        Tone.PHONE -> Kit.INK
    }
    val MUTED: Int get() = when (tone) {
        Tone.SEPIA -> 0xFF86705A.toInt()
        Tone.WHITE -> 0xFF6F6C67.toInt()
        Tone.NIGHT -> 0xFF96A098.toInt()
        Tone.PHONE -> Kit.MUTED
    }
    /** The bylines, the ribbon, the selection. */
    val ACCENT: Int get() = when (tone) {
        Tone.SEPIA -> 0xFF95632B.toInt()
        Tone.WHITE -> 0xFF8E6E3C.toInt()
        Tone.NIGHT -> Kit.Fixed.CHAMPAGNE
        Tone.PHONE -> Kit.CHAMPAGNE
    }

    /** A highlighter's ink on this stock: a warm yellow by day, a champagne wash at night. */
    val HIGHLIGHT: Int get() = if (Kit.dark) 0x3DD9BB86 else when (tone) {
        Tone.SEPIA -> 0x66E7B53C
        Tone.WHITE -> 0x70FFD94A
        else -> 0x66F2C94C
    }

    /** A pencil's graphite on this stock. */
    val PENCIL: Int get() = if (Kit.dark) 0xA6F3ECDD.toInt() else when (tone) {
        Tone.SEPIA -> 0xB04A3826.toInt()
        else -> 0xB04B4A48.toInt()
    }

    /** Samples for the chooser, whatever the current tone: ground, ink. */
    fun sample(t: Tone, phoneDark: Boolean): IntArray = when (t) {
        Tone.SEPIA -> intArrayOf(0xFFF2E5C9.toInt(), 0xFF3D2B1B.toInt())
        Tone.WHITE -> intArrayOf(0xFFFBFAF7.toInt(), 0xFF151515.toInt())
        Tone.NIGHT -> intArrayOf(Kit.Fixed.VISCONTI_GROUND, Kit.Fixed.IVORY)
        Tone.PHONE -> if (phoneDark) intArrayOf(Kit.Fixed.VISCONTI_GROUND, Kit.Fixed.IVORY) else intArrayOf(0xFFE9E3D6.toInt(), 0xFF1D1B19.toInt())
    }
}

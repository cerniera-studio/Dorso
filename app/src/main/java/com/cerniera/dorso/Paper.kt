package com.cerniera.dorso

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import kotlin.random.Random

/**
 * The sound of a page going over: a swish of air across paper, a little crackle, and a soft flap as
 * it lands. Three takes (synthesised by tools/leaf_sound.py), never the same one twice running, each
 * at a slightly different pitch, so a chapter of turns doesn't tick like a metronome. A quick turn is
 * quieter and higher, as a flicked page is. Silent when the phone is on silent or vibrate, or when
 * turned off on the trim.
 */
class Paper(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val takes = listOf(R.raw.leaf_1, R.raw.leaf_2, R.raw.leaf_3).map { pool.load(context, it, 1) }
    private var last = -1
    var on = true

    fun turn(quick: Boolean) {
        if (!on || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        var i = Random.nextInt(takes.size)
        if (i == last) i = (i + 1) % takes.size
        last = i
        val volume = if (quick) .38f else .62f
        val rate = (if (quick) 1.12f else 1f) * (0.93f + Random.nextFloat() * .14f)
        pool.play(takes[i], volume, volume, 1, 0, rate)
    }

    fun release() = pool.release()
}

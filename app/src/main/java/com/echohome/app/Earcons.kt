package com.echohome.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three short synthesised cues so the assistant needs no words: listening (one soft blip),
 * understood (rising pair) and not understood (falling pair). Generated at start, no audio files.
 */
class Earcons {
    enum class Cue { LISTENING, UNDERSTOOD, NOT_UNDERSTOOD }

    private val rate = 22_050
    private val handler = Handler(Looper.getMainLooper())
    private val clips: Map<Cue, ShortArray> = mapOf(
        Cue.LISTENING to tones(1000 to 70),
        Cue.UNDERSTOOD to tones(700 to 60, 1000 to 90),
        Cue.NOT_UNDERSTOOD to tones(520 to 90, 330 to 140),
    )

    /** Fire and forget: plays on its own AudioTrack, released after the clip. */
    fun play(cue: Cue) {
        val samples = clips.getValue(cue)
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            handler.postDelayed({ runCatching { track.release() } }, samples.size * 1000L / rate + 150)
        }
    }

    /** (frequency Hz, length ms) pairs, each with a short fade in/out so nothing clicks. */
    private fun tones(vararg parts: Pair<Int, Int>): ShortArray {
        val out = ArrayList<Short>()
        for ((hz, ms) in parts) {
            val n = rate * ms / 1000
            val fade = rate / 200 // 5 ms
            for (i in 0 until n) {
                val env = minOf(1f, i / fade.toFloat(), (n - i) / fade.toFloat())
                out += (sin(2 * PI * hz * i / rate) * 0.35 * env * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}

package com.echohome.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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
        // Soft, round "marimba" notes: low-ish pitches, slow attack, exponential decay.
        Cue.LISTENING to notes(587 to 170),                       // D5
        Cue.UNDERSTOOD to notes(523 to 120, 659 to 200),          // C5 -> E5, a friendly rise
        Cue.NOT_UNDERSTOOD to notes(392 to 150, 311 to 260),      // G4 -> Eb4, a gentle fall
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

    /**
     * (frequency Hz, length ms) notes played one after the other. Each is a sine with a quiet 2nd and
     * 3rd harmonic (warmth), a ~15 ms smooth attack and an exponential decay, so nothing sounds sharp.
     */
    private fun notes(vararg parts: Pair<Int, Int>): ShortArray {
        val out = ArrayList<Short>()
        for ((hz, ms) in parts) {
            val n = rate * ms / 1000
            val attack = rate * 15 / 1000
            for (i in 0 until n) {
                val t = i / rate.toDouble()
                val rise = if (i < attack) 0.5 - 0.5 * cos(PI * i / attack) else 1.0
                val decay = exp(-t * 14.0) * (1.0 - (i.toDouble() / n).let { it * it * it }) // dies out before the end
                val wave = sin(2 * PI * hz * t) + 0.25 * sin(4 * PI * hz * t) + 0.06 * sin(6 * PI * hz * t)
                out += (wave * 0.28 * rise * decay * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}

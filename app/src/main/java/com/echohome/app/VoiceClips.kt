package com.echohome.app

import android.content.res.AssetManager

/**
 * Pre-rendered Piper words (assets/voice/clips.pcm + index.txt) joined into an answer.
 * Missing assets are fine: [compose] then returns null and the caller uses Piper.
 */
class VoiceClips(private val assets: AssetManager) {

    class Audio(val samples: FloatArray, val sampleRate: Int)

    private class Clip(val offset: Int, val length: Int)

    private var loaded = false
    private var rate = 22_050
    private var index: Map<String, Clip> = emptyMap()
    private var pcm: ByteArray = ByteArray(0)

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val lines = assets.open("voice/index.txt").bufferedReader().readLines()
            rate = lines.first().removePrefix("rate ").trim().toInt()
            index = lines.drop(1).filter { it.isNotBlank() }.associate {
                val (key, offset, length) = it.split(' ')
                key to Clip(offset.toInt(), length.toInt())
            }
            pcm = assets.open("voice/clips.pcm").use { it.readBytes() }
        }.onFailure { index = emptyMap() }
    }

    /** The answer as audio, or null when it contains a word without a clip. */
    fun compose(text: String): Audio? {
        load()
        val keys = ClipPlanner.plan(text, index.keys) ?: return null
        val gap = rate / 25 // 40 ms between words
        val out = FloatArray(keys.sumOf { index.getValue(it).length + gap })
        var pos = 0
        for (key in keys) {
            val clip = index.getValue(key)
            for (i in 0 until clip.length) {
                val b = (clip.offset + i) * 2
                out[pos++] = ((pcm[b + 1].toInt() shl 8) or (pcm[b].toInt() and 0xff)).toShort() / 32768f
            }
            pos += gap
        }
        return Audio(out, rate)
    }
}

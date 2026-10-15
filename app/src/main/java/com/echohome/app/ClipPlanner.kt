package com.echohome.app

/**
 * Decides whether an answer can be spoken by joining pre-rendered word clips (assets/voice, see
 * scripts/gen-voice-clips.sh) instead of running Piper, which takes seconds on the Echo.
 * Plain Kotlin: no Android deps.
 */
object ClipPlanner {

    /** Clip keys in order, or null when any word has no clip (the caller then falls back to Piper). */
    fun plan(text: String, vocabulary: Set<String>): List<String>? {
        val words = text.lowercase()
            .replace(Regex("[.,;:!?]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.any { it !in vocabulary }) return null
        return words
    }
}

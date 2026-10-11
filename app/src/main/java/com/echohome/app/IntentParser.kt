package com.echohome.app

/** Commands Echo Home understands. Plain Kotlin on purpose: no Android deps, testable on the JVM. */
sealed interface EchoIntent {
    data class MusicSearch(val query: String) : EchoIntent
    data object MusicPause : EchoIntent
    data object MusicResume : EchoIntent
    data object MusicNext : EchoIntent
    data object MusicPrevious : EchoIntent
}

/**
 * Deterministic Italian intent matcher: regexes only, first match wins.
 * Returns null when the text is not a known command.
 */
object IntentParser {

    private fun rx(pattern: String) = Regex("^(?:$pattern)$", RegexOption.IGNORE_CASE)

    // Order matters: "metti in pausa" must hit PAUSE before the generic "metti ..." search.
    private val pause = rx("(?:metti in |fai una )?pausa|fermati|ferma(?: la musica)?|stop")
    private val resume = rx("riprendi(?: la musica)?|continua|riparti|play")
    private val next = rx("(?:la )?(?:prossima|prossimo|successiva|successivo)(?: canzone| brano)?|avanti|salta|next")
    private val previous = rx("(?:la )?(?:precedente)(?: canzone| brano)?|indietro|torna indietro|previous")

    // "metti i Radiohead", "metti su Wish You Were Here", "fai partire le ...", "suona ..."
    // Only plural/partitive articles are dropped; "metti La Vie en Rose" keeps its "La".
    private val search = Regex(
        "^(?:metti(?:mi)?(?: su)?|fai partire|riproduci|suona|ascolta|voglio ascoltare)\\s+" +
            "(?:(?:la musica|le canzoni|la canzone|il brano|l'album|l'artista)\\s+(?:di|dei|degli|delle|del|della)\\s+|" +
            "(?:i|gli|le|dei|degli|delle|l')\\s*)?" +
            "(.+)$",
        RegexOption.IGNORE_CASE,
    )

    // Verbs the speech recogniser tends to mangle ("meti", "metì", "suonna"): matched within 1 edit.
    private val searchVerbs = listOf("metti", "mettimi", "suona", "riproduci", "ascolta")

    // Single-word commands, also matched within 1 edit ("prossimo", "riprendi" -> "riprendo").
    private val fuzzyCommands = listOf(
        "pausa" to EchoIntent.MusicPause,
        "ferma" to EchoIntent.MusicPause,
        "riprendi" to EchoIntent.MusicResume,
        "continua" to EchoIntent.MusicResume,
        "prossima" to EchoIntent.MusicNext,
        "successiva" to EchoIntent.MusicNext,
        "precedente" to EchoIntent.MusicPrevious,
    )

    fun parse(text: String): EchoIntent? {
        val cleaned = text
            .trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
            .replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null

        exact(cleaned)?.let { return it }
        return fuzzy(cleaned)
    }

    private fun exact(cleaned: String): EchoIntent? = when {
        pause.matches(cleaned) -> EchoIntent.MusicPause
        resume.matches(cleaned) -> EchoIntent.MusicResume
        next.matches(cleaned) -> EchoIntent.MusicNext
        previous.matches(cleaned) -> EchoIntent.MusicPrevious
        else -> search.matchEntire(cleaned)
            ?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { EchoIntent.MusicSearch(it) }
    }

    private fun fuzzy(cleaned: String): EchoIntent? {
        val words = cleaned.split(' ')
        val first = java.text.Normalizer.normalize(words.first().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

        // One word that is almost a command. Only words of 5+ letters, to avoid random hits.
        if (words.size == 1 && first.length >= 5) {
            fuzzyCommands.firstOrNull { editDistance(first, it.first) <= 1 }?.let { return it.second }
        }
        // "<almost metti> <something>": fix the verb and reuse the exact search grammar.
        if (words.size >= 2 && first.length >= 4 && searchVerbs.any { editDistance(first, it) <= 1 }) {
            return search.matchEntire("metti " + words.drop(1).joinToString(" "))
                ?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { EchoIntent.MusicSearch(it) }
        }
        return null
    }

    /** Plain Levenshtein distance; inputs are a few letters long. */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

package com.echohome.app

/** Commands Echo Home understands. Plain Kotlin on purpose: no Android deps, testable on the JVM. */
sealed interface EchoIntent {
    data class MusicSearch(val query: String) : EchoIntent
    data object MusicPause : EchoIntent
    data object MusicResume : EchoIntent
    data object MusicNext : EchoIntent
    data object MusicPrevious : EchoIntent
    data object VolumeUp : EchoIntent
    data object VolumeDown : EchoIntent
    data class VolumeSet(val percent: Int) : EchoIntent
    data object VolumeUnmute : EchoIntent
    data object Time : EchoIntent
    data object Date : EchoIntent
    data class MusicShuffle(val enabled: Boolean) : EchoIntent
    data class MusicRepeat(val enabled: Boolean) : EchoIntent
    data object TimerRemaining : EchoIntent
    data object WeatherNow : EchoIntent
    data object WeatherTomorrow : EchoIntent
    data class TimerCreate(val seconds: Int) : EchoIntent
    data object TimerCancel : EchoIntent
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

    private val volumeUp = rx("(?:alza|aumenta|incrementa)(?: il)?(?: volume)?|volume (?:su|alto|più alto)|più forte|(?:metti|fai) (?:più forte|il volume più alto)")
    private val volumeDown = rx("(?:abbassa|diminuisci|riduci)(?: il)?(?: volume)?|volume (?:giù|basso|più basso)|più piano|(?:metti|fai) (?:più piano|il volume più basso)")
    private val volumeMax = rx("(?:metti )?(?:il )?volume (?:al )?massimo")
    private val volumeMute = rx("(?:metti )?(?:il )?volume (?:al )?minimo|silenzio|muto")
    private val volumeUnmute = rx("(?:riattiva|ripristina|rimetti)(?: l'| il)? ?(?:audio|volume)|(?:togli|disattiva) (?:il )?(?:muto|silenzio)")
    private val volumeSet = rx("(?:(?:imposta|metti|porta|regola)(?: il)? )?volume (?:(?:al|a|su) )?(.+?)(?: ?%| per cento)?")
    private val timerStart = Regex(
        "^(?:(?:imposta|metti|avvia|fai partire|crea|programma)(?: un| il)? )?timer(?: di| per| da)?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val timerBare = rx("(?:(?:imposta|metti|avvia|fai partire|crea|programma)(?: un| il)? )?timer")
    private val timerCancel = rx("(?:annulla|cancella|elimina|ferma|spegni|stoppa|stop)(?: il| la)? (?:timer|sveglia)|timer (?:annulla|stop|basta)")
    private val weatherTomorrow = rx(
        "(?:che tempo (?:farà|fa|ci sarà|c'è) domani|(?:com'è|come sarà|dimmi)? ?(?:il )?(?:meteo|tempo) (?:di |per )?domani|" +
            "previsioni(?: del tempo)?(?: per| di)? domani|domani (?:che tempo fa|piove|pioverà))",
    )
    private val weatherNow = rx(
        "(?:che tempo (?:fa|c'è)(?: adesso| ora| oggi| in questo momento)?|(?:com'è|come è|dimmi) (?:il )?(?:tempo|meteo)(?: oggi| adesso| ora)?|" +
            "meteo(?: oggi| adesso)?|previsioni(?: del tempo)?|piove(?: adesso| ora)?)",
    )
    private val date = rx("(?:che|quale) (?:giorno|data) (?:è|e)(?: oggi)?|(?:dimmi )?(?:la )?data(?: di oggi)?|che giorno è oggi")
    private val timerRemaining = rx("quanto (?:tempo )?(?:manca|resta|rimane)(?: al| del| per il)?(?: timer)?|a che punto è il timer")
    private val shuffleOn = rx("(?:attiva )?(?:la )?(?:riproduzione |modalità )?(?:casuale|shuffle)|mischia(?: i brani| la musica)?")
    private val shuffleOff = rx("(?:disattiva|togli|spegni) (?:la )?(?:riproduzione |modalità )?(?:casuale|shuffle)|non mischiare")
    private val repeatOn = rx("ripeti(?: questo| il)?(?: brano| canzone)?|(?:attiva )?(?:la )?ripetizione")
    private val repeatOff = rx("non ripetere(?: il brano)?|(?:disattiva|togli) (?:la )?ripetizione")
    private val time = rx("(?:che|quale) (?:ore sono|ora è|ora e|ora)|dimmi (?:l'ora|che ore sono)|l'ora|ora")

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

        if (timerCancel.matches(cleaned)) return EchoIntent.TimerCancel
        // A timer request without a duration ("metti un timer") is incomplete, not a music search.
        if (timerBare.matches(cleaned)) return null
        // "timer ..." is always a timer request: never let it fall through to a music search.
        timerStart.matchEntire(cleaned)?.let { m ->
            return parseDuration(m.groupValues[1])?.let { EchoIntent.TimerCreate(it) }
        }
        exact(cleaned)?.let { return it }
        return fuzzy(cleaned)
    }

    private fun exact(cleaned: String): EchoIntent? = when {
        // Volume and time come before the generic "metti ..." search ("metti il volume a 40").
        volumeMax.matches(cleaned) -> EchoIntent.VolumeSet(100)
        volumeMute.matches(cleaned) -> EchoIntent.VolumeSet(0)
        volumeUnmute.matches(cleaned) -> EchoIntent.VolumeUnmute
        volumeUp.matches(cleaned) -> EchoIntent.VolumeUp
        volumeDown.matches(cleaned) -> EchoIntent.VolumeDown
        volumeSet.matchEntire(cleaned)?.groupValues?.get(1)?.let { parseNumber(it) } != null ->
            EchoIntent.VolumeSet(parseNumber(volumeSet.matchEntire(cleaned)!!.groupValues[1])!!.coerceIn(0, 100))
        weatherTomorrow.matches(cleaned) -> EchoIntent.WeatherTomorrow
        weatherNow.matches(cleaned) -> EchoIntent.WeatherNow
        time.matches(cleaned) -> EchoIntent.Time
        date.matches(cleaned) -> EchoIntent.Date
        timerRemaining.matches(cleaned) -> EchoIntent.TimerRemaining
        shuffleOff.matches(cleaned) -> EchoIntent.MusicShuffle(false)
        shuffleOn.matches(cleaned) -> EchoIntent.MusicShuffle(true)
        repeatOff.matches(cleaned) -> EchoIntent.MusicRepeat(false)
        repeatOn.matches(cleaned) -> EchoIntent.MusicRepeat(true)
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

    private val units = mapOf(
        "zero" to 0, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11,
        "dodici" to 12, "tredici" to 13, "quattordici" to 14, "quindici" to 15, "sedici" to 16,
        "diciassette" to 17, "diciotto" to 18, "diciannove" to 19,
    )
    private val tens = mapOf(
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50,
        "sessanta" to 60, "settanta" to 70, "ottanta" to 80, "novanta" to 90,
    )

    /** "40", "quaranta", "venticinque", "cento" -> number; null for anything else. Range 0..100. */
    internal fun parseNumber(text: String): Int? {
        val t = text.trim().lowercase().removeSuffix("%").trim()
        t.toIntOrNull()?.let { return it }
        if (t == "cento") return 100
        units[t]?.let { return it }
        tens[t]?.let { return it }
        // "venticinque", "trentotto": tens word (minus its final vowel before uno/otto) + unit
        for ((word, value) in tens) {
            val stem = word.dropLast(1)
            for ((uWord, uValue) in units) {
                if (uValue !in 1..9) continue
                if (t == word + uWord || t == stem + uWord) return value + uValue
            }
        }
        return null
    }

    private val durationPart = Regex("(\\S+)\\s+(ore|ora|minuti|minuto|secondi|secondo)\\b")

    /**
     * "10 minuti", "dieci minuti", "un'ora e mezza", "mezz'ora", "un minuto e 30 secondi" -> seconds.
     * Null when nothing sensible is found; capped at 24 hours.
     */
    internal fun parseDuration(text: String): Int? {
        var t = " " + text.lowercase().replace('’', '\'') + " "
        var extra = 0
        if (Regex("\\bun quarto d'ora\\b").containsMatchIn(t)) { extra += 15 * 60; t = t.replace(Regex("\\bun quarto d'ora\\b"), " ") }
        if (Regex("\\bmezz'ora\\b|\\bmezza ora\\b").containsMatchIn(t)) { extra += 30 * 60; t = t.replace(Regex("\\bmezz'ora\\b|\\bmezza ora\\b"), " ") }
        val hasHours = Regex("\\b(?:ora|ore)\\b").containsMatchIn(t)
        t = t.replace("un'ora", "1 ora").replace("un'", "1 ")
        if (hasHours && Regex("\\be (?:mezza|mezzo)\\b").containsMatchIn(t)) {
            extra += 30 * 60
            t = t.replace(Regex("\\be (?:mezza|mezzo)\\b"), " ")
        }
        var total = extra
        var found = extra > 0
        for (m in durationPart.findAll(t)) {
            val word = m.groupValues[1]
            val qty = if (word == "un" || word == "uno" || word == "una") 1 else parseAnyNumber(word) ?: return null
            val unit = when (m.groupValues[2]) {
                "ore", "ora" -> 3600
                "minuti", "minuto" -> 60
                else -> 1
            }
            total += qty * unit
            found = true
        }
        return if (found && total in 1..24 * 3600) total else null
    }

    /** Like [parseNumber] but also accepts hundreds ("centoventi") through plain digits only. */
    private fun parseAnyNumber(word: String): Int? = parseNumber(word) ?: word.toIntOrNull()
}

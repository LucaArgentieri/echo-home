package com.echohome.app

/** Italian wording of the spoken (and written) answers. Plain Kotlin, tested on the JVM. */
object Replies {

    fun time(hour: Int, minute: Int): String {
        val minutes = if (minute == 0) "" else " e $minute"
        return when {
            hour == 0 && minute == 0 -> "È mezzanotte."
            hour == 12 && minute == 0 -> "È mezzogiorno."
            hour == 0 -> "È mezzanotte$minutes."
            hour == 12 -> "È mezzogiorno$minutes."
            hour == 1 -> "È l'una$minutes."
            minute == 0 -> "Sono le $hour in punto."
            else -> "Sono le $hour$minutes."
        }
    }

    /** weekday "domenica", month "ottobre". */
    fun date(weekday: String, day: Int, month: String): String =
        "Oggi è $weekday ${if (day == 1) "primo" else day.toString()} $month."

    fun timerRemaining(seconds: Int): String {
        val singular = seconds == 1 || seconds == 60 || seconds == 3600
        return "${if (singular) "Manca" else "Mancano"} ${describeDuration(seconds)}."
    }

    fun shuffle(enabled: Boolean) = if (enabled) "Riproduzione casuale attiva." else "Riproduzione casuale disattivata."
    fun repeat(enabled: Boolean) = if (enabled) "Ripeto il brano." else "Ripetizione disattivata."

    fun volume(percent: Int): String = when (percent) {
        0 -> "Volume azzerato."
        100 -> "Volume al massimo."
        else -> "Volume al $percent per cento."
    }

    fun timerSet(seconds: Int, label: String? = null) =
        "Timer ${if (label != null) "$label " else ""}impostato per ${describeDuration(seconds)}."
    fun timerDone(label: String?) = if (label == null) TIMER_DONE else "Il timer $label è scaduto."
    fun timerNotFound(label: String) = "Non ho un timer $label."
    fun timerCancelled(label: String?) = if (label == null) TIMER_CANCELLED else "Timer $label annullato."

    /** (label, seconds left) for every active timer. */
    fun timersRemaining(items: List<Pair<String?, Int>>): String {
        if (items.size == 1 && items[0].first == null) return timerRemaining(items[0].second)
        val parts = items.map { (label, s) -> "${label ?: "un timer"} tra ${describeDuration(s)}" }
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        return if (items.size == 1) "$list." else "Hai ${items.size} timer: $list."
    }
    const val TOO_MANY_TIMERS = "Ci sono già troppi timer attivi."
    const val TIMERS_CANCELLED = "Timer annullati."
    const val TIMER_CANCELLED = "Timer annullato."
    const val NEXT = "Brano successivo."
    const val PREVIOUS = "Brano precedente."
    const val NO_NEXT = "Non c'è un brano successivo."
    const val NO_TIMER = "Nessun timer attivo."
    const val TIMER_DONE = "Il timer è scaduto."

    /** 90 -> "1 minuto e 30 secondi", 3600 -> "1 ora". */
    fun describeDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val sec = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h ${if (h == 1) "ora" else "ore"}" else null,
            if (m > 0) "$m ${if (m == 1) "minuto" else "minuti"}" else null,
            if (sec > 0) "$sec ${if (sec == 1) "secondo" else "secondi"}" else null,
        ).joinToString(" e ")
    }

    const val WEATHER_UNAVAILABLE = "Non riesco a leggere il meteo."

    /** WMO weather code -> short Italian description. */
    fun condition(code: Int): String = when (code) {
        0 -> "sereno"
        1 -> "prevalentemente sereno"
        2 -> "parzialmente nuvoloso"
        3 -> "coperto"
        45, 48 -> "nebbia"
        51, 53, 55, 56, 57 -> "pioviggine"
        61, 63, 65, 66, 67 -> "pioggia"
        71, 73, 75, 77, 85, 86 -> "neve"
        80, 81, 82 -> "rovesci"
        95, 96, 99 -> "temporale"
        else -> "variabile"
    }

    /** Emoji for the screen; the sun becomes a moon at night. */
    fun icon(code: Int, night: Boolean = false): String = when (code) {
        0, 1 -> if (night) "🌙" else "☀️"
        2 -> if (night) "☁️" else "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67 -> "🌧️"
        80, 81, 82 -> "🌦️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    fun unknownCity(name: String) = "Non conosco la città $name."

    /** "A Roma" / "Ad Ancona": the place before the answer, for cities other than home. */
    fun inCity(city: String): String {
        val vowel = city.first().lowercaseChar() in "aeiou"
        return (if (vowel) "Ad " else "A ") + city
    }

    fun weatherNow(f: Forecast): String =
        "Adesso ci sono ${f.nowTemp} gradi, ${condition(f.nowCode)}. Oggi massima ${f.todayMax}, minima ${f.todayMin}" +
            if (f.todayRainMax >= 30) ", pioggia fino al ${f.todayRainMax} per cento." else "."

    fun weatherTomorrow(f: Forecast): String =
        "Domani minima ${f.tomorrowMin} gradi, massima ${f.tomorrowMax}, ${condition(f.tomorrowCode)}. " +
            rainSummary(f.tomorrowRainByHour)

    private val weekdays = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")

    /** "2026-10-14" -> "mercoledì". */
    fun weekday(date: String): String = weekdays[java.time.LocalDate.parse(date).dayOfWeek.value - 1]

    /** Spoken: tomorrow and the two days after it. */
    fun weatherWeek(f: Forecast): String =
        f.days.drop(1).take(3).mapIndexed { i, d ->
            "${if (i == 0) "Domani" else weekday(d.date).replaceFirstChar { it.uppercase() }} ${condition(d.code)}, " +
                "tra ${d.min} e ${d.max} gradi."
        }.joinToString(" ").ifEmpty { WEATHER_UNAVAILABLE }

    /** One line per day for the screen: "Mer   12° / 20°   coperto". */
    fun weatherWeekRows(f: Forecast): List<String> = f.days.drop(1).take(5).mapIndexed { i, d ->
        val name = if (i == 0) "Domani" else weekday(d.date).take(3).replaceFirstChar { it.uppercase() }
        "%-7s %2d° / %2d°  %s".format(name, d.min, d.max, condition(d.code))
    }

    /** One sentence about rain: wet periods (>= 40%) as hour ranges, or a calm statement. */
    fun rainSummary(byHour: IntArray): String {
        val max = byHour.max()
        val wet = byHour.indices.filter { byHour[it] >= 40 }
        if (wet.isEmpty()) {
            return if (max >= 20) "Probabilità di pioggia bassa, al massimo $max per cento." else "Nessuna pioggia prevista."
        }
        val ranges = mutableListOf<String>()
        var start = wet.first()
        var prev = start
        for (h in wet.drop(1) + (Int.MAX_VALUE)) {
            if (h != prev + 1) {
                ranges += if (start == prev) "alle $start" else "dalle $start alle ${prev + 1}"
                start = h
            }
            prev = h
        }
        return "Pioggia probabile ${ranges.joinToString(" e ")}, fino al $max per cento."
    }

    /** Hourly rain probability for the screen: 4 rows of 6 hours, e.g. "00:0%  01:0% ...". */
    fun rainTable(byHour: IntArray): String =
        (0 until 24).chunked(6).joinToString("\n") { row ->
            row.joinToString("   ") { "%02d:%d%%".format(it, byHour[it]) }
        }
}

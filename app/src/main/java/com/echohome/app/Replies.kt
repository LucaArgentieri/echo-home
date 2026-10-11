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

    fun volume(percent: Int): String = when (percent) {
        0 -> "Volume azzerato."
        100 -> "Volume al massimo."
        else -> "Volume al $percent per cento."
    }

    fun timerSet(seconds: Int) = "Timer impostato per ${describeDuration(seconds)}."
    const val TIMER_CANCELLED = "Timer annullato."
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

    fun weatherNow(f: Forecast): String =
        "Adesso ci sono ${f.nowTemp} gradi, ${condition(f.nowCode)}. Oggi massima ${f.todayMax}, minima ${f.todayMin}" +
            if (f.todayRainMax >= 30) ", pioggia fino al ${f.todayRainMax} per cento." else "."

    fun weatherTomorrow(f: Forecast): String =
        "Domani minima ${f.tomorrowMin} gradi, massima ${f.tomorrowMax}, ${condition(f.tomorrowCode)}. " +
            rainSummary(f.tomorrowRainByHour)

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

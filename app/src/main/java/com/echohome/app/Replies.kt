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
}

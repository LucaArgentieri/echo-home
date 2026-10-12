package com.echohome.app

import android.content.SharedPreferences
import android.os.Handler

/**
 * Up to [MAX_TIMERS] countdown timers, each with an optional name. Deadlines are stored as
 * wall-clock time, so a timer that expired while the app was not running still rings when the
 * app is back ([resume]).
 */
class KitchenTimer(
    private val prefs: SharedPreferences,
    private val handler: Handler,
    private val onFire: (Timer) -> Unit,
) {
    data class Timer(val id: Long, val label: String?, val endAt: Long)

    companion object {
        const val MAX_TIMERS = 4
        private const val KEY_TIMERS = "timers"
    }

    private val timers = mutableListOf<Timer>()
    private val pending = mutableMapOf<Long, Runnable>()

    init {
        // One line per timer: "id|endAtMs|label". Names never contain '|' (see start()).
        prefs.getString(KEY_TIMERS, "")!!.lines().forEach { line ->
            val parts = line.split('|', limit = 3)
            val id = parts.getOrNull(0)?.toLongOrNull()
            val endAt = parts.getOrNull(1)?.toLongOrNull()
            if (id != null && endAt != null) timers += Timer(id, parts.getOrNull(2)?.ifEmpty { null }, endAt)
        }
    }

    val isRunning get() = timers.isNotEmpty()

    /** Soonest first. */
    fun active(): List<Timer> = timers.sortedBy { it.endAt }

    fun remainingMs(t: Timer): Long = maxOf(0L, t.endAt - System.currentTimeMillis())

    /** Timers a spoken name refers to ("pasta" -> the one called "pasta"). */
    fun find(query: String): List<Timer> = active().filter { IntentParser.labelMatches(it.label, query) }

    /** Null when [MAX_TIMERS] are already running. */
    fun start(seconds: Int, label: String?): Timer? {
        if (timers.size >= MAX_TIMERS) return null
        val t = Timer(System.currentTimeMillis(), label?.replace('|', ' '), System.currentTimeMillis() + seconds * 1000L)
        timers += t
        save()
        arm(t, seconds * 1000L)
        return t
    }

    fun cancel(t: Timer) {
        pending.remove(t.id)?.let { handler.removeCallbacks(it) }
        timers.removeAll { it.id == t.id }
        save()
    }

    /** Returns how many timers were running. */
    fun cancelAll(): Int {
        val n = timers.size
        active().forEach { cancel(it) }
        return n
    }

    /** Re-arms stored timers (app start); the overdue ones ring at once. */
    fun resume() {
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
        active().forEach { t ->
            val left = t.endAt - System.currentTimeMillis()
            if (left <= 0) fire(t) else arm(t, left)
        }
    }

    private fun arm(t: Timer, delayMs: Long) {
        val r = Runnable { fire(t) }
        pending[t.id] = r
        handler.postDelayed(r, delayMs)
    }

    private fun fire(t: Timer) {
        pending.remove(t.id)
        timers.removeAll { it.id == t.id }
        save()
        onFire(t)
    }

    private fun save() {
        prefs.edit().putString(KEY_TIMERS, timers.joinToString("\n") { "${it.id}|${it.endAt}|${it.label.orEmpty()}" }).apply()
    }
}

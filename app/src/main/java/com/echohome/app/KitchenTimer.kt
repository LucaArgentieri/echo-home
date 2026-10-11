package com.echohome.app

import android.content.SharedPreferences
import android.os.Handler

/**
 * A single countdown timer. The deadline is stored as wall-clock time, so a timer that expired
 * while the app was not running still rings when the app is back ([resume]).
 */
class KitchenTimer(
    private val prefs: SharedPreferences,
    private val handler: Handler,
    private val onFire: () -> Unit,
) {
    companion object {
        private const val KEY_END = "timer_end_ms"
    }

    private var endAt = prefs.getLong(KEY_END, 0L)
    private val fire = Runnable {
        clear()
        onFire()
    }

    val isRunning get() = endAt != 0L

    fun remainingMs(): Long = if (endAt == 0L) 0L else maxOf(0L, endAt - System.currentTimeMillis())

    fun start(seconds: Int) {
        handler.removeCallbacks(fire)
        endAt = System.currentTimeMillis() + seconds * 1000L
        prefs.edit().putLong(KEY_END, endAt).apply()
        handler.postDelayed(fire, seconds * 1000L)
    }

    /** Returns true if a timer was actually running. */
    fun cancel(): Boolean {
        val was = isRunning
        handler.removeCallbacks(fire)
        clear()
        return was
    }

    /** Re-arms a stored timer (app start); rings at once if it is already overdue. */
    fun resume() {
        handler.removeCallbacks(fire)
        if (endAt == 0L) return
        val left = endAt - System.currentTimeMillis()
        if (left <= 0) fire.run() else handler.postDelayed(fire, left)
    }

    private fun clear() {
        endAt = 0L
        prefs.edit().remove(KEY_END).apply()
    }
}

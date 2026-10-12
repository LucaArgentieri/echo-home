package com.echohome.app

/**
 * The system music volume has few steps (15 on the Echo), so a percentage is split into a system
 * step (rounded up) plus a player volume 0..1 that trims it down to the exact value.
 */
object VolumeMath {
    /** [percent] 0..100 -> (system step 0..[max], player trim 0..1). Without a trim the nearest step is used. */
    fun split(percent: Int, max: Int, canTrim: Boolean = true): Pair<Int, Float> {
        val p = percent.coerceIn(0, 100)
        if (p == 0) return 0 to 1f
        if (!canTrim) return Math.round(p * max / 100f).coerceIn(1, max) to 1f
        val step = Math.ceil(p * max / 100.0).toInt().coerceIn(1, max)
        return step to (p / (step * 100f / max)).coerceIn(0f, 1f)
    }

    fun percent(step: Int, max: Int, trim: Float): Int = Math.round(step * 100f / max * trim)
}

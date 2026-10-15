package com.echohome.app

import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

/**
 * Material 3 design tokens (https://m3.material.io/styles): the style reference for every screen.
 * Dark scheme on true black (OLED friendly, no burn-in glow), tonal surfaces instead of shadows,
 * the M3 type scale in Roboto (the system "sans-serif"), M3 corner radii. See docs/DESIGN.md.
 */
object M3 {

    // ---- Colour roles (dark scheme; surface stays pure black on purpose)
    val surface = Color.BLACK
    val surfaceContainer = 0xFF1C1B1F.toInt()
    val surfaceContainerHigh = 0xFF2B2930.toInt()
    val onSurface = 0xFFE6E1E5.toInt()
    val onSurfaceVariant = 0xFFC9C5CA.toInt()
    val outline = 0xFF938F99.toInt()
    val primary = 0xFFA8C7FA.toInt()
    val secondaryContainer = 0xFF3A4457.toInt()
    val onSecondaryContainer = 0xFFD9E3F8.toInt()
    val tertiary = 0xFFF5C451.toInt()
    val tertiaryContainer = 0xFF4D3B00.toInt()
    val onTertiaryContainer = 0xFFFFE08C.toInt()

    // ---- Shape (dp): extra-large for cards, "full" = half the height for pills
    const val SHAPE_MEDIUM = 12
    const val SHAPE_LARGE = 16
    const val SHAPE_EXTRA_LARGE = 28

    /**
     * Type scale: size (sp), weight, tracking (sp). Sizes are the M3 values; on this 5" display seen
     * from a metre or two the call sites multiply them (the `scale` argument).
     */
    enum class Type(val sp: Float, val medium: Boolean, val trackingSp: Float) {
        DISPLAY_LARGE(57f, false, -0.25f),
        DISPLAY_MEDIUM(45f, false, 0f),
        HEADLINE_LARGE(32f, false, 0f),
        HEADLINE_MEDIUM(28f, false, 0f),
        HEADLINE_SMALL(24f, false, 0f),
        TITLE_LARGE(22f, false, 0f),
        TITLE_MEDIUM(16f, true, 0.15f),
        LABEL_LARGE(14f, true, 0.1f),
        BODY_LARGE(16f, false, 0.5f),
        BODY_MEDIUM(14f, false, 0.25f),
    }

    fun apply(view: TextView, role: Type, scale: Float = 1f) {
        view.textSize = role.sp * scale
        view.typeface = Typeface.create(if (role.medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        view.letterSpacing = role.trackingSp / role.sp // em
    }
}

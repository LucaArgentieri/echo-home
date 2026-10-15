package com.echohome.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Thin gradient bar along the bottom edge that slides while the assistant listens, thinks or speaks.
 * One rectangle and a shader translation per frame: cheap enough for the Echo's CPU. Idle = invisible
 * and the animator is stopped.
 */
class GlowBar(context: Context) : View(context) {

    enum class Mode(val colors: IntArray, val periodMs: Long) {
        OFF(intArrayOf(), 0),
        /** Idle with the wake word switched off: a thin static grey line. */
        DIM(intArrayOf(), 0),
        LISTEN(intArrayOf(0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt()), 2400),
        THINK(intArrayOf(0xFFF59E0B.toInt(), 0xFFF97316.toInt(), 0xFFFACC15.toInt()), 1200),
        SPEAK(intArrayOf(0xFF22C55E.toInt(), 0xFF14B8A6.toInt(), 0xFF84CC16.toInt()), 3200),
    }

    private val paint = Paint()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var mode = Mode.OFF
        set(value) {
            if (field == value) return
            field = value
            animator.cancel()
            if (value == Mode.OFF) {
                visibility = INVISIBLE
                return
            }
            if (value == Mode.DIM) {
                paint.shader = null
                paint.color = 0xFF3A3A3A.toInt()
                visibility = VISIBLE
                invalidate()
                return
            }
            buildShader()
            animator.duration = value.periodMs
            visibility = VISIBLE
            animator.start()
        }

    init { visibility = INVISIBLE }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShader()
    }

    private fun buildShader() {
        if (width == 0 || mode.colors.isEmpty()) return
        val c = mode.colors
        // One colour cycle across the bar, repeated: sliding by one width looks seamless.
        shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(c[0], c[1], c[2], c[0]), null, Shader.TileMode.REPEAT)
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.DIM) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }
        val s = shader ?: return
        matrix.setTranslate(phase * width, 0f)
        s.setLocalMatrix(matrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}

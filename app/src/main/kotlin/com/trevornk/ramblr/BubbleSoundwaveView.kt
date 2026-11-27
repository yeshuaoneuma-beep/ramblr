package com.trevornk.ramblr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Rhema Cadence Style 1 signature 3-bar soundwave view (short 42% – tall 100% – medium 66%).
 *
 * Renders calm signature bars while idle and reacts smoothly to [AudioLevelMeter] loudness samples
 * while recording, paced at [MicWaveMotion.FRAME_INTERVAL_MS] (33ms). Under reduced motion
 * (`ANIMATOR_DURATION_SCALE == 0f`) it renders statically and NEVER starts a repeating frame loop.
 */
internal class BubbleSoundwaveView(context: Context) : View(context) {

    var color: Int = DEFAULT_COBALT
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private var active = false
    private var targetLevel = 0f
    private var smoothedLevel = 0f
    private var lastFrameMs = 0L
    private var reducedMotion = false
    private var frameScheduled = false

    private val frameRunnable = Runnable {
        frameScheduled = false
        if (active && !reducedMotion) {
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        targetLevel = 0f
        smoothedLevel = 0f
        lastFrameMs = 0L
        removeCallbacks(frameRunnable)
        frameScheduled = false
        reducedMotion = MicWaveMotion.reducedMotion(context)
        if (on && !reducedMotion) {
            scheduleNextFrame()
        }
        invalidate()
    }

    fun stop() {
        active = false
        targetLevel = 0f
        smoothedLevel = 0f
        lastFrameMs = 0L
        removeCallbacks(frameRunnable)
        frameScheduled = false
        invalidate()
    }

    fun push(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    internal fun isAnimatingForTest(): Boolean = active && !reducedMotion && frameScheduled
    internal fun reducedMotionForTest(): Boolean = reducedMotion

    private fun scheduleNextFrame() {
        if (!active || reducedMotion || frameScheduled) return
        val delay = MicWaveMotion.delayUntilNextFrame(SystemClock.uptimeMillis(), lastFrameMs)
        frameScheduled = true
        postDelayed(frameRunnable, delay)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 0f || availH <= 0f) return

        val now = SystemClock.uptimeMillis()
        val seconds = if (active && !reducedMotion) {
            val dt = if (lastFrameMs == 0L) 16f else (now - lastFrameMs).coerceIn(0L, 100L).toFloat()
            lastFrameMs = now
            val tau = if (targetLevel > smoothedLevel) RISE_MS else FALL_MS
            smoothedLevel += (targetLevel - smoothedLevel) * (1f - exp(-dt / tau))
            now / 1000f
        } else {
            0f
        }

        // 3 rounded vertical bars centered inside content bounds: 42% - 100% - 66% proportions.
        val barWidth = (availW * 0.17f).coerceAtLeast(2f * resources.displayMetrics.density)
        val gap = barWidth * 0.85f
        val totalW = barWidth * 3f + gap * 2f
        val startX = paddingLeft + (availW - totalW) / 2f
        val centerY = paddingTop + availH / 2f
        val maxBarH = availH * 0.88f
        val radius = barWidth / 2f

        paint.color = color
        for (i in 0 until 3) {
            val baseRatio = BASE_RATIOS[i]
            val ratio = if (active && !reducedMotion) {
                val wave = 0.5f + 0.5f * sin(2.0 * PI * (seconds * BAR_FREQS[i] + BAR_PHASES[i])).toFloat()
                val dynamicBoost = (0.14f + smoothedLevel * 0.86f) * (0.28f + 0.72f * wave)
                (baseRatio * 0.45f + dynamicBoost * 0.75f).coerceIn(0.24f, 1.0f)
            } else {
                baseRatio
            }
            val h = (maxBarH * ratio).coerceAtLeast(barWidth)
            val left = startX + i * (barWidth + gap)
            rect.set(left, centerY - h / 2f, left + barWidth, centerY + h / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (active && !reducedMotion) {
            scheduleNextFrame()
        }
    }

    companion object {
        const val DEFAULT_COBALT = 0xFF3B82F6.toInt()
        private val BASE_RATIOS = floatArrayOf(0.42f, 1.00f, 0.66f)
        private val BAR_FREQS = floatArrayOf(1.9f, 2.5f, 2.15f)
        private val BAR_PHASES = floatArrayOf(0.0f, 0.33f, 0.67f)
        private const val RISE_MS = 95f
        private const val FALL_MS = 220f
    }
}

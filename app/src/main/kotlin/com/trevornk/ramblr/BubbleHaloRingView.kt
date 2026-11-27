package com.trevornk.ramblr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-perimeter outer halo / progress ring drawn at `overlayView` bounds (`ringSize`) so status
 * halos and the 3-second hold-to-cancel progress arc remain clearly visible outside a fingertip.
 *
 * Strictly checks [MicWaveMotion.reducedMotion] before starting any repeating animator.
 */
internal class BubbleHaloRingView(context: Context) : View(context) {

    sealed class Mode {
        data class Idle(val color: Int) : Mode()
        data class RecordingBatch(val color: Int) : Mode()
        data object RecordingLiveConnected : Mode()
        data object RecordingLiveDisconnected : Mode()
        data class HoldProgress(val fraction: Float, val color: Int) : Mode()
        data class ProcessingSweep(val cleaning: Boolean) : Mode()
        data object InsertFlash : Mode()
    }

    private var mode: Mode = Mode.Idle(COLOR_COBALT)
    private var breathingAlpha = 255
    private var sweepAngleOffset = 0f
    private var activeAnimator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
    }
    private val arcRect = RectF()
    private val density get() = resources.displayMetrics.density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun currentMode(): Mode = mode

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        val needsRestart = mode::class != newMode::class ||
            (newMode is Mode.ProcessingSweep && (mode as? Mode.ProcessingSweep)?.cleaning != newMode.cleaning)
        mode = newMode
        if (needsRestart) {
            cancelAnimations()
            when (newMode) {
                is Mode.RecordingLiveConnected -> startBreathingIfAllowed()
                is Mode.ProcessingSweep -> startSweepIfAllowed()
                else -> {
                    breathingAlpha = 255
                    sweepAngleOffset = 0f
                }
            }
        }
        invalidate()
    }

    fun cancelAnimations() {
        activeAnimator?.cancel()
        activeAnimator = null
        breathingAlpha = 255
    }

    private fun startBreathingIfAllowed() {
        if (MicWaveMotion.reducedMotion(context)) {
            breathingAlpha = 255
            return
        }
        activeAnimator = ValueAnimator.ofInt(120, 255).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                breathingAlpha = anim.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun startSweepIfAllowed() {
        if (MicWaveMotion.reducedMotion(context)) {
            sweepAngleOffset = 0f
            return
        }
        activeAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1_100L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                sweepAngleOffset = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val strokeDp = when (mode) {
            is Mode.HoldProgress -> 3.0f
            is Mode.InsertFlash -> 2.5f
            is Mode.RecordingLiveConnected, is Mode.RecordingLiveDisconnected, is Mode.ProcessingSweep -> 2.0f
            is Mode.Idle, is Mode.RecordingBatch -> 1.5f
        }
        val strokePx = (strokeDp * density).coerceAtLeast(1.5f)
        val inset = strokePx / 2f + 1f * density
        if (w <= inset * 2f || h <= inset * 2f) return
        arcRect.set(inset, inset, w - inset, h - inset)

        when (val m = mode) {
            is Mode.Idle -> {
                if (m.color == Color.TRANSPARENT) return
                ringPaint.color = m.color
                ringPaint.alpha = Color.alpha(m.color)
                ringPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, ringPaint)
            }
            is Mode.RecordingBatch -> {
                ringPaint.color = m.color
                ringPaint.alpha = 255
                ringPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, ringPaint)
            }
            is Mode.RecordingLiveConnected -> {
                ringPaint.color = COLOR_EMERALD
                ringPaint.alpha = breathingAlpha
                ringPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, ringPaint)
            }
            is Mode.RecordingLiveDisconnected -> {
                ringPaint.color = COLOR_AMBER
                ringPaint.alpha = 255
                ringPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, ringPaint)
            }
            is Mode.HoldProgress -> {
                trackPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, trackPaint)
                ringPaint.color = m.color
                ringPaint.alpha = 255
                ringPaint.strokeWidth = strokePx
                val sweep = (m.fraction.coerceIn(0f, 1f)) * 360f
                if (sweep > 0f) {
                    canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)
                }
            }
            is Mode.ProcessingSweep -> {
                trackPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, trackPaint)
                ringPaint.color = if (m.cleaning) COLOR_EMERALD else COLOR_COBALT
                ringPaint.alpha = 255
                ringPaint.strokeWidth = strokePx
                canvas.drawArc(arcRect, -90f + sweepAngleOffset, 105f, false, ringPaint)
            }
            is Mode.InsertFlash -> {
                ringPaint.color = COLOR_EMERALD
                ringPaint.alpha = 255
                ringPaint.strokeWidth = strokePx
                canvas.drawOval(arcRect, ringPaint)
            }
        }
    }

    companion object {
        const val COLOR_COBALT = 0xFF3B82F6.toInt()
        const val COLOR_EMERALD = 0xFF22C55E.toInt()
        const val COLOR_AMBER = 0xFFF59E0B.toInt()
        const val COLOR_DANGER = 0xFFEF4444.toInt()
    }
}

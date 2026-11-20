package com.directaccess.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class PulseConnectButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class VisualState { DISCONNECTED, CONNECTING, CONNECTED }

    var state: VisualState = VisualState.DISCONNECTED
        set(value) {
            if (field == value) return
            field = value
            updateAnimations()
            invalidate()
        }

    private var pulseFraction = 0f
    private var spinAngle = 0f
    private var pressScale = 1f

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulseFraction = it.animatedValue as Float
            invalidate()
        }
    }

    private val spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spinAngle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Connect toggle"
    }

    private fun updateAnimations() {
        pulseAnimator.cancel()
        spinAnimator.cancel()
        when (state) {
            VisualState.CONNECTED -> pulseAnimator.start()
            VisualState.CONNECTING -> spinAnimator.start()
            VisualState.DISCONNECTED -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        spinAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val coreRadius = maxRadius * 0.62f

        val accentColor = when (state) {
            VisualState.CONNECTED -> Color.parseColor("#10B981")
            VisualState.CONNECTING -> Color.parseColor("#F59E0B")
            VisualState.DISCONNECTED -> Color.parseColor("#38BDF8")
        }

        // 1. Soft radial ambient glow (BPB-inspired dark glassmorphic feel)
        haloPaint.shader = RadialGradient(
            cx, cy, maxRadius * 0.96f,
            intArrayOf(
                Color.argb(if (state == VisualState.CONNECTED) 95 else 45,
                    Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, maxRadius * 0.96f, haloPaint)

        // 2. Breathing ripple ring when connected
        if (state == VisualState.CONNECTED) {
            val rippleRadius = coreRadius + (maxRadius - coreRadius) * pulseFraction
            val alpha = ((1f - pulseFraction) * 110).toInt().coerceIn(0, 255)
            ringPaint.shader = null
            ringPaint.color = Color.argb(alpha, 16, 185, 129)
            ringPaint.strokeWidth = maxRadius * 0.025f
            canvas.drawCircle(cx, cy, rippleRadius, ringPaint)
        }

        // 3. Glass button body
        bodyPaint.shader = RadialGradient(
            cx, cy - coreRadius * 0.35f, coreRadius * 1.4f,
            intArrayOf(
                Color.parseColor("#1E293B"),
                Color.parseColor("#0F172A")
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius * pressScale, bodyPaint)

        // 4. Outer accent border / animated sweep arc
        ringPaint.strokeWidth = maxRadius * 0.032f
        if (state == VisualState.CONNECTING) {
            canvas.save()
            canvas.rotate(spinAngle, cx, cy)
            ringPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, accentColor, Color.parseColor("#38BDF8")),
                floatArrayOf(0f, 0.7f, 1f)
            )
            canvas.drawCircle(cx, cy, coreRadius, ringPaint)
            canvas.restore()
        } else {
            ringPaint.shader = null
            ringPaint.color = Color.argb(
                if (state == VisualState.CONNECTED) 235 else 115,
                Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)
            )
            canvas.drawCircle(cx, cy, coreRadius, ringPaint)
        }

        // 5. Crisp minimalist Power Icon
        val iconR = coreRadius * 0.34f
        iconPaint.strokeWidth = coreRadius * 0.085f
        iconPaint.color = if (state == VisualState.CONNECTED) {
            Color.parseColor("#34D399")
        } else {
            Color.parseColor("#F1F5F9")
        }
        arcRect.set(cx - iconR, cy - iconR, cx + iconR, cy + iconR)
        canvas.drawArc(arcRect, -55f, 290f, false, iconPaint)
        canvas.drawLine(cx, cy - iconR * 1.15f, cx, cy - iconR * 0.12f, iconPaint)
    }
}

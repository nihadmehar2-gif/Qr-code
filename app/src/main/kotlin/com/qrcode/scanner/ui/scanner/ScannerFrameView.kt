package com.qrcode.scanner.ui.scanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.qrcode.scanner.R

/**
 * Renders the visible scan frame: four corner brackets + a slowly moving horizontal
 * scan line. Animation is gentle (1.2s) so it doesn't drain the battery.
 */
class ScannerFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bracketColor = ContextCompat.getColor(context, R.color.scanner_frame)
    private val lineColor = ContextCompat.getColor(context, R.color.brand_primary)

    private val bracketWidth = 8f * resources.displayMetrics.density
    private val bracketLength = 36f * resources.displayMetrics.density
    private val cornerRadius = 18f * resources.displayMetrics.density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bracketColor
        strokeWidth = bracketWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 6f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var scanY: Float = 0f
    private val animator by lazy {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                scanY = it.animatedValue as Float * height
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scanY = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = bracketWidth / 2f

        // Corner brackets
        // Top-left
        canvas.drawLine(pad, pad, bracketLength, pad, framePaint)
        canvas.drawLine(pad, pad, pad, bracketLength, framePaint)
        // Top-right
        canvas.drawLine(width - bracketLength, pad, width - pad, pad, framePaint)
        canvas.drawLine(width - pad, pad, width - pad, bracketLength, framePaint)
        // Bottom-left
        canvas.drawLine(pad, height - bracketLength, pad, height - pad, framePaint)
        canvas.drawLine(pad, height - pad, bracketLength, height - pad, framePaint)
        // Bottom-right
        canvas.drawLine(width - bracketLength, height - pad, width - pad, height - pad, framePaint)
        canvas.drawLine(width - pad, height - bracketLength, width - pad, height - pad, framePaint)

        // Moving scan line
        if (height > 0) {
            canvas.drawLine(0f, scanY, width.toFloat(), scanY, linePaint)
        }
    }
}

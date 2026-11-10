package com.derricklee.ankidict

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView supporting 2-finger pinch-to-zoom, pan while zoomed, and double-tap zoom,
 * while yielding horizontal single-finger swipes to a parent ViewPager2 when at 1x scale.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 1.0f
    private var maxScale = 5.0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = getScale()
                var factor = detector.scaleFactor
                val target = (current * factor).coerceIn(minScale, maxScale)
                factor = target / current
                if (factor != 1.0f) {
                    suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    checkAndDisplayMatrix()
                }
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (getScale() > 1.05f) {
                    resetZoom()
                } else {
                    val factor = 2.5f / getScale()
                    suppMatrix.postScale(factor, factor, e.x, e.y)
                    checkAndDisplayMatrix()
                }
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    fun resetZoom() {
        suppMatrix.reset()
        checkAndDisplayMatrix()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        updateBaseMatrix(drawable)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateBaseMatrix(drawable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix(drawable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val currentScale = getScale()
        val multiTouch = event.pointerCount > 1 || scaleDetector.isInProgress
        if (multiTouch || currentScale > 1.02f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > 1.02f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging && (dx * dx + dy * dy) > 16f) {
                        isDragging = true
                    }
                    if (isDragging) {
                        suppMatrix.postTranslate(dx, dy)
                        checkAndDisplayMatrix()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (getScale() < minScale) {
                    resetZoom()
                }
            }
        }
        return true
    }

    private fun getScale(): Float {
        suppMatrix.getValues(matrixValues)
        val sx = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        return kotlin.math.sqrt((sx * sx + skewY * skewY).toDouble()).toFloat()
    }

    private fun updateBaseMatrix(d: Drawable?) {
        if (d == null) return
        val viewW = (width - paddingLeft - paddingRight).toFloat()
        val viewH = (height - paddingTop - paddingBottom).toFloat()
        val dW = d.intrinsicWidth.toFloat()
        val dH = d.intrinsicHeight.toFloat()
        if (viewW <= 0f || viewH <= 0f || dW <= 0f || dH <= 0f) return

        baseMatrix.reset()
        val scale = min(viewW / dW, viewH / dH)
        val dx = (viewW - dW * scale) * 0.5f
        val dy = (viewH - dH * scale) * 0.5f
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        suppMatrix.reset()
        checkAndDisplayMatrix()
    }

    private fun checkAndDisplayMatrix() {
        val rect = getDisplayRect(getDrawMatrix()) ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        var deltaX = 0f
        var deltaY = 0f

        if (rect.height() <= viewH) {
            deltaY = (viewH - rect.height()) * 0.5f - rect.top
        } else if (rect.top > 0) {
            deltaY = -rect.top
        } else if (rect.bottom < viewH) {
            deltaY = viewH - rect.bottom
        }

        if (rect.width() <= viewW) {
            deltaX = (viewW - rect.width()) * 0.5f - rect.left
        } else if (rect.left > 0) {
            deltaX = -rect.left
        } else if (rect.right < viewW) {
            deltaX = viewW - rect.right
        }

        suppMatrix.postTranslate(deltaX, deltaY)
        imageMatrix = getDrawMatrix()
    }

    private fun getDrawMatrix(): Matrix {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        return drawMatrix
    }

    private fun getDisplayRect(matrix: Matrix): RectF? {
        val d = drawable ?: return null
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)
        return rect
    }
}

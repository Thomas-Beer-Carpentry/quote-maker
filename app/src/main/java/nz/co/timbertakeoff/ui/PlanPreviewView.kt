package nz.co.timbertakeoff.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.drawing.DrawingRenderer

/** A read-only paper viewport; transforms the sheet, never the calculated construction geometry. */
class PlanPreviewView(context: Context) : View(context) {
    var sheet: DrawingSheet? = null
        set(value) {
            if (field?.code != value?.code || field?.widthMm != value?.widthMm) resetViewport()
            field = value
            contentDescription = value?.let { "${it.code} ${it.title}. Preliminary estimating drawing. Pinch to zoom and drag to pan." }
            invalidate()
        }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldZoom = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 10f)
            val ratio = zoom / oldZoom
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * ratio
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * ratio
            constrainPan()
            invalidate()
            return true
        }
    })

    init { isFocusable = true }

    fun resetViewport() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(222, 226, 221))
        val paper = sheet ?: return
        val fit = min((width - 24f).coerceAtLeast(1f) / paper.widthMm.toFloat(), (height - 24f).coerceAtLeast(1f) / paper.heightMm.toFloat())
        val paperWidth = paper.widthMm.toFloat() * fit
        val paperHeight = paper.heightMm.toFloat() * fit
        canvas.save()
        canvas.translate(width / 2f + panX, height / 2f + panY)
        canvas.scale(zoom, zoom)
        canvas.translate(-paperWidth / 2f, -paperHeight / 2f)
        DrawingRenderer.draw(canvas, paper, paperWidth, paperHeight)
        canvas.restore()
    }

    private fun constrainPan() {
        val boundX = width * (zoom - 1f) / 2f
        val boundY = height * (zoom - 1f) / 2f
        panX = panX.coerceIn(-boundX, boundX)
        panY = panY.coerceIn(-boundY, boundY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!pinch.isInProgress && event.pointerCount == 1) {
                    panX += event.x - lastX
                    panY += event.y - lastY
                    constrainPan()
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> { performClick(); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

package nz.co.timbertakeoff.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import nz.co.timbertakeoff.core.drawing.DrawingElement
import nz.co.timbertakeoff.core.drawing.DrawingSheet
import nz.co.timbertakeoff.core.drawing.TextAlign
import kotlin.math.min

/** A single vector renderer for screen previews, PDF pages and Android printing. */
object DrawingRenderer {
    // PdfDocument quantises glyph advances at tiny font sizes. Shape text at a normal font
    // resolution, then scale it back to paper millimetres so printed letters do not overlap.
    private const val TEXT_UNITS_PER_MM = 100f

    fun draw(canvas: Canvas, sheet: DrawingSheet, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val scale = min(widthPx / sheet.widthMm.toFloat(), heightPx / sheet.heightMm.toFloat())
        canvas.save()
        canvas.translate((widthPx - sheet.widthMm.toFloat() * scale) / 2f, (heightPx - sheet.heightMm.toFloat() * scale) / 2f)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, sheet.widthMm.toFloat(), sheet.heightMm.toFloat(), paint)
        sheet.elements.forEach { element ->
            paint.reset()
            paint.isAntiAlias = true
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.SQUARE
            when (element) {
                is DrawingElement.Line -> {
                    paint.strokeWidth = element.weightMm.toFloat()
                    if (element.dashed) paint.pathEffect = DashPathEffect(floatArrayOf(1.2f, 0.9f), 0f)
                    canvas.drawLine(element.x1.toFloat(), element.y1.toFloat(), element.x2.toFloat(), element.y2.toFloat(), paint)
                }
                is DrawingElement.Rect -> {
                    paint.strokeWidth = element.weightMm.toFloat()
                    if (element.fill) paint.style = Paint.Style.FILL
                    if (element.dashed) paint.pathEffect = DashPathEffect(floatArrayOf(1.2f, 0.9f), 0f)
                    canvas.drawRect(element.x.toFloat(), element.y.toFloat(), (element.x + element.width).toFloat(), (element.y + element.height).toFloat(), paint)
                }
                is DrawingElement.Circle -> {
                    paint.strokeWidth = element.weightMm.toFloat()
                    if (element.fill) paint.style = Paint.Style.FILL
                    canvas.drawCircle(element.x.toFloat(), element.y.toFloat(), element.radiusMm.toFloat(), paint)
                }
                is DrawingElement.Polygon -> {
                    paint.strokeWidth = element.weightMm.toFloat()
                    paint.strokeJoin = Paint.Join.MITER
                    if (element.fill) paint.style = Paint.Style.FILL
                    val path = Path()
                    element.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
                        else path.lineTo(point.x.toFloat(), point.y.toFloat())
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                is DrawingElement.Text -> {
                    paint.style = Paint.Style.FILL
                    paint.isSubpixelText = true
                    paint.isLinearText = true
                    paint.textSize = element.sizeMm.toFloat() * TEXT_UNITS_PER_MM
                    paint.typeface = Typeface.create("sans-serif", if (element.bold) Typeface.BOLD else Typeface.NORMAL)
                    paint.textAlign = when (element.align) { TextAlign.LEFT -> Paint.Align.LEFT; TextAlign.CENTER -> Paint.Align.CENTER; TextAlign.RIGHT -> Paint.Align.RIGHT }
                    canvas.save()
                    canvas.translate(element.x.toFloat(), element.y.toFloat())
                    canvas.rotate(element.rotationDegrees.toFloat())
                    canvas.scale(1f / TEXT_UNITS_PER_MM, 1f / TEXT_UNITS_PER_MM)
                    canvas.drawText(element.text, 0f, 0f, paint)
                    canvas.restore()
                }
            }
        }
        canvas.restore()
    }
}

/** Read-only fit-to-page preview; Compose may add pan/zoom around this view. */
class DrawingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var sheet: DrawingSheet? = null
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sheet?.let { DrawingRenderer.draw(canvas, it, width.toFloat(), height.toFloat()) }
    }
}

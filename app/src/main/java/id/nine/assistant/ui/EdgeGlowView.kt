package id.nine.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Cahaya berpendar di sepanjang tepi layar saat Nine mendengarkan, berpikir, atau berbicara. */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var target = 0f
    private var current = 0f
    private var level = 0f
    private var targetLevel = 0f
    private var angle = 0f
    private var spin = 40f
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()
    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(
        Color.rgb(0x35, 0xE0, 0xFF),
        Color.rgb(0x3D, 0x6B, 0xFF),
        Color.rgb(0x8B, 0x5C, 0xFF),
        Color.rgb(0xC7, 0x86, 0x1A),
        Color.rgb(0x35, 0xE0, 0xFF)
    )
    private var gradient: SweepGradient? = null

    init {
        isClickable = false
        isFocusable = false
    }

    /** intensity 0 = mati, 1 = penuh. spin derajat per detik. */
    fun setIntensity(intensity: Float, spinDegPerSec: Float) {
        target = intensity.coerceIn(0f, 1f)
        spin = spinDegPerSec
        lastFrame = 0L
        invalidate()
    }

    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = SweepGradient(w / 2f, h / 2f, colors, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        current += (target - current) * min(1f, dt * 5f)
        level += (targetLevel - level) * min(1f, dt * 10f)
        angle = (angle + spin * dt) % 360f
        if (current < 0.01f && target == 0f) {
            current = 0f
            return
        }
        val g = gradient ?: return
        matrix.setRotate(angle, width / 2f, height / 2f)
        g.setLocalMatrix(matrix)
        paint.shader = g

        // Garis berada tepat di tepi layar; separuh lebarnya terlihat ke dalam.
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val corner = 44f * density
        val boost = 1f + level * 0.8f
        for (i in LAYER_WIDTH.indices) {
            paint.strokeWidth = LAYER_WIDTH[i] * density * boost
            paint.alpha = (255 * LAYER_ALPHA[i] * current).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        // Tebal garis (dp) dan opasitas tiap lapis cahaya; dibuat sekali, bukan per frame.
        val LAYER_WIDTH = floatArrayOf(5f, 14f, 28f, 46f)
        val LAYER_ALPHA = floatArrayOf(1f, 0.45f, 0.2f, 0.08f)
    }
}

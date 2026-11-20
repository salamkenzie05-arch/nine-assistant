package id.nine.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import id.nine.assistant.audio.GuitarTuner
import id.nine.assistant.audio.GuitarTuning
import kotlin.math.abs

/**
 * Panel tuner gitar: enam senar (E A D G B E, dari tebal ke tipis), jarum selisih nada (cent), dan petunjuk singkat.
 * Mengetuk satu senar = menyetel senar itu secara manual; mengetuk lagi = kembali otomatis.
 */
class TunerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Dipanggil saat pengguna memilih senar ([number] 1..6) atau kembali otomatis (null). */
    var onChoose: ((Int?) -> Unit)? = null

    /** Dipanggil saat tombol tutup diketuk. */
    var onClose: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val chipRects = Array(6) { RectF() }
    private val closeRect = RectF()

    private val gold = Color.rgb(0xC7, 0x86, 0x1A)
    private val green = Color.rgb(0x2E, 0x9E, 0x5B)
    private val red = Color.rgb(0xE5, 0x48, 0x4D)

    /** Senar yang dituju secara manual (1..6), null = otomatis. */
    private var selected: Int? = null
    private var reading: GuitarTuner.Reading? = null
    private var readingAt = 0L
    private var needle = 0f
    private var hint = ""

    fun setSelected(number: Int?) {
        selected = number
        invalidate()
    }

    fun setHint(text: String) {
        hint = text
        invalidate()
    }

    /** Bacaan terbaru (null = senar tidak berbunyi). */
    fun setReading(r: GuitarTuner.Reading?) {
        reading = r
        if (r != null) readingAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun onSurface(): Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
    private fun surface(): Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (176f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val fg = onSurface()
        val bg = surface()
        val pad = 14f * d

        // kartu
        fillPaint.color = Color.argb(236, Color.red(bg), Color.green(bg), Color.blue(bg))
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 22f * d, 22f * d, fillPaint)
        linePaint.strokeWidth = 1f * d
        linePaint.color = Color.argb(46, Color.red(fg), Color.green(fg), Color.blue(fg))
        canvas.drawRoundRect(rect, 22f * d, 22f * d, linePaint)

        // tombol tutup
        val cs = 30f * d
        closeRect.set(w - pad - cs, pad * 0.6f, w - pad, pad * 0.6f + cs)
        linePaint.strokeWidth = 2f * d
        linePaint.color = Color.argb(150, Color.red(fg), Color.green(fg), Color.blue(fg))
        val cx0 = closeRect.centerX()
        val cy0 = closeRect.centerY()
        val q = 5f * d
        canvas.drawLine(cx0 - q, cy0 - q, cx0 + q, cy0 + q, linePaint)
        canvas.drawLine(cx0 - q, cy0 + q, cx0 + q, cy0 - q, linePaint)

        // enam senar: tebal (6) di kiri → tipis (1) di kanan
        val chipW = (w - pad * 2 - 5 * 8f * d) / 6f
        val top = 40f * d
        val chipH = 36f * d
        val active = reading?.string?.number ?: selected
        for (i in 0 until 6) {
            val number = 6 - i
            val gs = GuitarTuning.byNumber(number) ?: continue
            val x = pad + i * (chipW + 8f * d)
            chipRects[i].set(x, top, x + chipW, top + chipH)
            val on = active == number
            chipPaint.style = Paint.Style.FILL
            chipPaint.color = if (on) gold else Color.argb(28, Color.red(fg), Color.green(fg), Color.blue(fg))
            canvas.drawRoundRect(chipRects[i], 12f * d, 12f * d, chipPaint)
            if (selected == number) {
                linePaint.strokeWidth = 2f * d
                linePaint.color = gold
                canvas.drawRoundRect(chipRects[i], 12f * d, 12f * d, linePaint)
            }
            textPaint.color = if (on) Color.WHITE else fg
            textPaint.isFakeBoldText = true
            textPaint.textSize = 15f * d
            canvas.drawText(gs.note, chipRects[i].centerX(), chipRects[i].centerY() - 2f * d, textPaint)
            textPaint.isFakeBoldText = false
            textPaint.textSize = 9.5f * d
            canvas.drawText("$number", chipRects[i].centerX(), chipRects[i].centerY() + 11f * d, textPaint)
        }

        // jarum
        val now = SystemClock.uptimeMillis()
        val live = reading != null && now - readingAt < 1600L
        val target = if (live) (reading!!.cents.toFloat()).coerceIn(-50f, 50f) else 0f
        needle += (target - needle) * 0.25f
        val gy = top + chipH + 34f * d
        val gl = pad + 6f * d
        val gr = w - pad - 6f * d
        val mid = (gl + gr) / 2f
        linePaint.strokeWidth = 3f * d
        linePaint.color = Color.argb(60, Color.red(fg), Color.green(fg), Color.blue(fg))
        canvas.drawLine(gl, gy, gr, gy, linePaint)
        // zona pas (±6 cent)
        val zone = (gr - gl) * (GuitarTuning.IN_TUNE_CENTS.toFloat() / 100f)
        linePaint.strokeWidth = 6f * d
        linePaint.color = Color.argb(live.let { if (it) 150 else 70 }, Color.red(green), Color.green(green), Color.blue(green))
        canvas.drawLine(mid - zone, gy, mid + zone, gy, linePaint)
        // garis skala
        linePaint.strokeWidth = 1.5f * d
        linePaint.color = Color.argb(90, Color.red(fg), Color.green(fg), Color.blue(fg))
        for (c in -50..50 step 25) {
            val x = mid + (gr - gl) * (c / 100f)
            canvas.drawLine(x, gy - 7f * d, x, gy + 7f * d, linePaint)
        }
        if (live) {
            val x = mid + (gr - gl) * (needle / 100f)
            val ok = abs(reading!!.cents) <= GuitarTuning.IN_TUNE_CENTS
            fillPaint.color = if (ok) green else if (abs(reading!!.cents) < 25) gold else red
            canvas.drawCircle(x, gy, 9f * d, fillPaint)
            textPaint.color = fg
            textPaint.isFakeBoldText = true
            textPaint.textSize = 13f * d
            val sign = if (reading!!.cents > 0) "+" else ""
            canvas.drawText("$sign${reading!!.cents.toInt()} cent", mid, gy - 14f * d, textPaint)
            textPaint.isFakeBoldText = false
            postInvalidateOnAnimation()
        } else if (abs(needle) > 0.2f) {
            postInvalidateOnAnimation()
        }

        // petunjuk
        textPaint.color = Color.argb(190, Color.red(fg), Color.green(fg), Color.blue(fg))
        textPaint.textSize = 12.5f * d
        canvas.drawText(hint.take(60), mid, h - 14f * d, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            if (closeRect.contains(x, y) || (x > width - 56f * d && y < 44f * d)) {
                performClick()
                onClose?.invoke()
                return true
            }
            for (i in 0 until 6) {
                if (chipRects[i].contains(x, y)) {
                    val number = 6 - i
                    performClick()
                    onChoose?.invoke(if (selected == number) null else number)
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

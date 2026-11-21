package id.nine.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import id.nine.assistant.R
import kotlin.math.max
import kotlin.math.min

/**
 * Grafik batang sederhana untuk rekap keuangan: satu seri, batang tertinggi emas penuh, lainnya emas redup,
 * nilai nol tampil sebagai garis tipis. Hanya menggambar; data diisi lewat [setData].
 */
class FinBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val rect = RectF()
    private var labels: List<String> = emptyList()
    private var values: List<Long> = emptyList()
    private var gold = 0
    private var track = 0
    private var muted = 0
    private var keyDark: Boolean? = null

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun setData(labels: List<String>, values: List<Long>) {
        require(labels.size == values.size)
        this.labels = labels
        this.values = values
        invalidate()
    }

    private fun loadColors() {
        val d = dark
        if (d == keyDark) return
        keyDark = d
        gold = ContextCompat.getColor(context, R.color.nine_gold)
        track = ContextCompat.getColor(context, R.color.nine_pill)
        muted = ContextCompat.getColor(context, R.color.nine_muted)
        text.color = muted
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0 || width <= 0 || height <= 0) return
        loadColors()
        val labelH = text.textSize + 8f * dp
        val chartH = height - labelH
        if (chartH <= 0f) return
        val slot = width.toFloat() / n
        val barW = min(slot * 0.56f, 36f * dp)
        val maxV = max(values.maxOrNull() ?: 0L, 1L)
        val minH = 3f * dp
        val radius = min(barW / 2f, 8f * dp)
        for (i in 0 until n) {
            val v = values[i]
            val cx = slot * (i + 0.5f)
            val h = if (v <= 0L) minH else max(minH, chartH * (v.toFloat() / maxV))
            rect.set(cx - barW / 2f, chartH - h, cx + barW / 2f, chartH)
            bar.color = when {
                v <= 0L -> track
                v == maxV -> gold
                else -> (gold and 0x00FFFFFF) or (0x70 shl 24)
            }
            canvas.drawRoundRect(rect, radius, radius, bar)
            canvas.drawText(labels[i], cx, height - 4f * dp, text)
        }
    }
}

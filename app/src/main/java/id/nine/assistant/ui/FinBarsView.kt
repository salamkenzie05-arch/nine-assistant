package id.nine.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import id.nine.assistant.R
import id.nine.assistant.nlu.FinanceMath
import kotlin.math.max
import kotlin.math.min

/**
 * Grafik batang perbandingan untuk rekap keuangan (mis. pemasukan vs pengeluaran): tiap batang punya warna sendiri,
 * nilai di atas, dan nama di bawah. Tinggi sebanding dengan nilai terbesar ([FinanceMath.barFractions]); nilai nol
 * tampil sebagai garis tipis. Hanya menggambar; data diisi lewat [setBars].
 */
class FinBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Satu batang: [label] di bawah, [valueText] di atas, [color] warna isi. */
    data class Bar(val label: String, val value: Long, val valueText: String, val color: Int)

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * dp }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val valueSizeMax = valuePaint.textSize
    private val valueSizeMin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private var bars: List<Bar> = emptyList()
    private var fractions: List<Float> = emptyList()
    private var track = 0
    private var ink = 0
    private var muted = 0
    private var keyDark: Boolean? = null

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun setBars(bars: List<Bar>) {
        this.bars = bars
        fractions = FinanceMath.barFractions(bars.map { it.value })
        contentDescription = bars.joinToString(", ") { it.label + " " + it.valueText }
        invalidate()
    }

    private fun loadColors() {
        val d = dark
        if (d == keyDark) return
        keyDark = d
        track = ContextCompat.getColor(context, R.color.nine_outline)
        ink = ContextCompat.getColor(context, R.color.nine_on_bg)
        muted = ContextCompat.getColor(context, R.color.nine_muted)
        labelPaint.color = muted
        valuePaint.color = ink
        linePaint.color = ContextCompat.getColor(context, R.color.nine_outline_variant)
    }

    override fun onDraw(canvas: Canvas) {
        val n = bars.size
        if (n == 0 || width <= 0 || height <= 0) return
        loadColors()
        val valueH = valueSizeMax + 10f * dp
        val labelH = labelPaint.textSize + 10f * dp
        val top = valueH
        val base = height - labelH
        val chartH = base - top
        if (chartH <= 0f) return
        val slot = width.toFloat() / n
        val barW = min(slot * 0.5f, 88f * dp)
        val minH = 3f * dp
        val r = min(barW / 2f, 14f * dp)
        radii[0] = r; radii[1] = r; radii[2] = r; radii[3] = r
        for (i in 0 until n) {
            val bar = bars[i]
            val cx = slot * (i + 0.5f)
            val h = if (fractions[i] <= 0f) minH else max(minH, chartH * fractions[i])
            rect.set(cx - barW / 2f, base - h, cx + barW / 2f, base)
            barPaint.color = if (bar.value <= 0L) track else bar.color
            path.rewind()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, barPaint)
            valuePaint.textSize = fitSize(bar.valueText, slot - 8f * dp)
            canvas.drawText(bar.valueText, cx, rect.top - 6f * dp, valuePaint)
            canvas.drawText(bar.label, cx, height - 5f * dp, labelPaint)
        }
        canvas.drawLine(0f, base, width.toFloat(), base, linePaint)
    }

    /** Ukuran teks nilai terbesar yang masih muat di [maxWidth], tidak lebih kecil dari batas bawah. */
    private fun fitSize(text: String, maxWidth: Float): Float {
        valuePaint.textSize = valueSizeMax
        val w = valuePaint.measureText(text)
        if (w <= maxWidth || w <= 0f) return valueSizeMax
        return max(valueSizeMin, valueSizeMax * maxWidth / w)
    }
}

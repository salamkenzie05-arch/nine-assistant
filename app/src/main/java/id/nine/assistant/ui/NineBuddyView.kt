package id.nine.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import id.nine.assistant.core.Mood
import kotlin.math.PI
import kotlin.math.sin

/**
 * Nine utuh untuk layar Nine: badan berupa wajah bulat ([face]) dengan dua tangan dan dua kaki berupa bola kecil yang
 * melayang tanpa sambungan. Tangan dan kaki melayang pelan; mengetuk badan membuat mereka melompat kecil.
 * Animasi hanya berjalan saat tampil di layar.
 */
class NineBuddyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Wajah Nine (dipakai juga di layar utama). */
    val face = NineFaceView(context).apply {
        showRing = false
    }

    private class Limb(
        val view: SphereView,
        val baseX: Float,
        val phase: Float,
        val period: Float,
        val ampY: Float,
        val ampX: Float,
        val hop: Float
    )

    private val d = resources.displayMetrics.density
    private val limbs = ArrayList<Limb>()
    private var hopStart = 0L
    private var animator: ValueAnimator? = null

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowKey = -1

    private fun dp(v: Float) = v * d

    init {
        clipChildren = false
        clipToPadding = false

        val faceSize = dp(230f).toInt()
        addView(face, LayoutParams(faceSize, faceSize, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8f).toInt()
        })
        face.contentDescription = null
        face.setOnClickListener {
            hopStart = SystemClock.uptimeMillis()
        }

        // Tangan (kiri/kanan) di sisi badan, kaki (kiri/kanan) di bawahnya. Posisi dasar = translasi dari tengah atas.
        addLimb(46f, -110f, 150f, 0.0f, 3.4f, 5f, 3f, 20f)
        addLimb(46f, 110f, 150f, 2.1f, 3.8f, 5f, 3f, 20f)
        addLimb(52f, -42f, 238f, 1.0f, 3.1f, 4f, 2f, 8f)
        addLimb(52f, 42f, 238f, 3.3f, 3.6f, 4f, 2f, 8f)
    }

    private fun addLimb(size: Float, x: Float, centerY: Float, phase: Float, period: Float, ampY: Float, ampX: Float, hop: Float) {
        val v = SphereView(context)
        val px = dp(size).toInt()
        addView(v, LayoutParams(px, px, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (dp(centerY) - px / 2f).toInt()
        })
        limbs += Limb(v, dp(x), phase, period, dp(ampY), dp(ampX), dp(hop))
        v.translationX = dp(x)
    }

    private fun isDarkTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun dispatchDraw(canvas: Canvas) {
        if (!isDarkTheme()) {
            // Bayangan lembut di "lantai" di bawah kaki.
            val key = width
            if (key != shadowKey) {
                shadowKey = key
                shadowPaint.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(60, 11, 18, 32), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            }
            val t = SystemClock.uptimeMillis() / 1000f
            val breathe = 1f + 0.05f * sin(2f * PI.toFloat() * t / 3.4f)
            canvas.save()
            canvas.translate(width / 2f, dp(282f))
            canvas.scale(dp(78f) * breathe, dp(9f) * breathe)
            canvas.drawCircle(0f, 0f, 1f, shadowPaint)
            canvas.restore()
        }
        super.dispatchDraw(canvas)
    }

    /** Ekspresi sesaat pada wajah (mis. saat layar dibuka). */
    fun react(mood: Mood, ms: Long = 1800) = face.react(mood, ms)

    // ------------------------------------------------------------------ animasi

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val hopK = ((now - hopStart) / 650f).coerceIn(0f, 1f)
        val hopWave = if (hopStart == 0L || hopK >= 1f) 0f else sin(PI.toFloat() * hopK)
        for (l in limbs) {
            val w = 2f * PI.toFloat() * t / l.period + l.phase
            l.view.translationY = l.ampY * sin(w) - l.hop * hopWave
            l.view.translationX = l.baseX + l.ampX * sin(w * 0.77f)
        }
        if (!isDarkTheme()) invalidate() // bayangan lantai (hanya tema terang) ikut bernapas
    }

    private fun startAnim() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { step() }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnim() else stopAnim()
    }
}

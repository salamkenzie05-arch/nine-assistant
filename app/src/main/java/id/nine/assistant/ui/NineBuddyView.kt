package id.nine.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import id.nine.assistant.core.Mood
import id.nine.assistant.core.Perf
import kotlin.math.max
import kotlin.math.min

/**
 * Nine utuh untuk layar Nine: badan berupa wajah bulat ([face]) dengan dua tangan dan dua kaki berupa bola kecil yang
 * melayang tanpa sambungan. Gerak tangan dan kaki dihitung oleh [LimbMotion] (pegas, gestur acak, ikut suasana hati,
 * melompat saat diketuk). Animasi hanya berjalan saat tampil di layar.
 */
class NineBuddyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Wajah Nine (dipakai juga di layar utama). */
    val face = NineFaceView(context).apply {
        showRing = false
    }

    private val d = resources.displayMetrics.density
    private val motion = LimbMotion()
    private val limbViews = ArrayList<SphereView>()
    private val baseX = ArrayList<Float>()
    private val t0 = SystemClock.uptimeMillis()
    private var lastMs = 0L
    private var running = false
    private val taps = ArrayDeque<Long>()

    private val bodyShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val footShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowKey = -1

    private fun dp(v: Float) = v * d

    private fun nowSec(): Float = (SystemClock.uptimeMillis() - t0) / 1000f

    private fun isDarkTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    init {
        clipChildren = false
        clipToPadding = false

        val faceSize = dp(230f).toInt()
        addView(face, LayoutParams(faceSize, faceSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8f).toInt()
        })
        face.contentDescription = null
        face.setOnClickListener {
            val now = nowSec()
            motion.hop(now)
            // Diketuk berkali-kali: kesal (wajah juga ikut kesal), kaki menghentak.
            val ms = SystemClock.uptimeMillis()
            taps.addLast(ms)
            while (taps.isNotEmpty() && ms - taps.first() > 2200) taps.removeFirst()
            if (taps.size >= 4) motion.setMood(Mood.ANNOYED, now, 1800)
        }

        // Urutan harus sama dengan LimbMotion.limbs: tangan kiri, tangan kanan, kaki kiri, kaki kanan.
        // (diameter bola, x dari tengah, pusat y dari atas, semuanya dp)
        addLimb(46f, -110f, 150f)
        addLimb(46f, 110f, 150f)
        addLimb(52f, -42f, 238f)
        addLimb(52f, 42f, 238f)
    }

    private fun addLimb(diameter: Float, x: Float, centerY: Float) {
        val v = SphereView(context)
        val px = dp(diameter * SphereView.PAD).toInt()
        addView(v, LayoutParams(px, px, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (dp(centerY) - px / 2f).toInt()
        })
        limbViews += v
        baseX += dp(x)
        v.translationX = dp(x)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!isDarkTheme()) {
            // Bayangan lembut di "lantai": satu di bawah badan, satu kecil di bawah tiap kaki (mengecil saat kaki terangkat).
            if (width != shadowKey) {
                shadowKey = width
                val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(60, 11, 18, 32), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                bodyShadow.shader = g
                footShadow.shader = g
            }
            val floorY = dp(284f)
            val breathe = 1f + 0.05f * kotlin.math.sin(2f * Math.PI.toFloat() * nowSec() / 3.4f)
            bodyShadow.alpha = 150
            canvas.save()
            canvas.translate(width / 2f, floorY)
            canvas.scale(dp(78f) * breathe, dp(9f) * breathe)
            canvas.drawCircle(0f, 0f, 1f, bodyShadow)
            canvas.restore()
            for (i in 2..3) {
                val l = motion.limbs[i]
                val lift = max(0f, -l.y)
                val sc = 1f / (1f + lift / 30f)
                footShadow.alpha = (255f * min(1f, sc + 0.1f)).toInt()
                canvas.save()
                canvas.translate(width / 2f + baseX[i] + l.x * d, floorY)
                canvas.scale(dp(24f) * sc, dp(5f) * sc)
                canvas.drawCircle(0f, 0f, 1f, footShadow)
                canvas.restore()
            }
        }
        super.dispatchDraw(canvas)
    }

    /** Ekspresi sesaat pada wajah dan gerak tubuh yang sesuai (mis. saat layar dibuka). */
    fun react(mood: Mood, ms: Long = 1800) {
        face.react(mood, ms)
        motion.setMood(mood, nowSec(), ms)
    }

    // ------------------------------------------------------------------ animasi

    /** Satu langkah animasi, lalu jadwalkan berikutnya: ~60 fps saat ada gerak cepat, ~30 fps saat hanya mengayun pelan. */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            step(start)
            val pace = if (motion.lively((start - t0) / 1000f)) Perf.Pace.ACTIVE else Perf.Pace.CALM
            postOnAnimationDelayed(this, Perf.delayFor(Perf.tickMs(pace), SystemClock.uptimeMillis() - start))
        }
    }

    private fun step(nowMs: Long) {
        val dt = if (lastMs == 0L) 1f / 60f else (nowMs - lastMs) / 1000f
        lastMs = nowMs
        motion.step((nowMs - t0) / 1000f, dt)
        face.translationY = motion.bodyY * d
        for (i in limbViews.indices) {
            val l = motion.limbs[i]
            val v = limbViews[i]
            v.translationX = baseX[i] + l.x * d
            v.translationY = l.y * d
            v.scaleX = l.scaleX
            v.scaleY = l.scaleY
        }
        if (!isDarkTheme()) invalidate() // bayangan lantai (hanya tema terang) ikut bergerak
    }

    private fun startAnim() {
        if (running) return
        running = true
        lastMs = 0L
        postOnAnimation(tick)
    }

    private fun stopAnim() {
        running = false
        removeCallbacks(tick)
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

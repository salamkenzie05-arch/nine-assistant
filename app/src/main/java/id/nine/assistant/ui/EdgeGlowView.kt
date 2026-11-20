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
import id.nine.assistant.audio.BeatAnalyzer
import kotlin.math.max
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
    // Palet Nine: dominan emas (#C7861A / #F2B245 / krem), dengan satu semburat jingga dan sedikit biru-kehijauan (#35E0FF, aksen Nine).
    private val colors = intArrayOf(
        Color.rgb(0xC7, 0x86, 0x1A),
        Color.rgb(0xF2, 0xB2, 0x45),
        Color.rgb(0xFF, 0xE5, 0xAD),
        Color.rgb(0xF2, 0xB2, 0x45),
        Color.rgb(0xC7, 0x86, 0x1A),
        Color.rgb(0xF2, 0x8A, 0x3A),
        Color.rgb(0x35, 0xE0, 0xFF),
        Color.rgb(0xF2, 0xB2, 0x45),
        Color.rgb(0xC7, 0x86, 0x1A)
    )
    private val stops = floatArrayOf(0f, 0.13f, 0.27f, 0.41f, 0.55f, 0.68f, 0.80f, 0.91f, 1f)
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

    // --- reaksi musik (diisi lewat setMusic dari main thread)
    private var musicUntil = 0L
    private var musicLevel = 0f
    private var musicBass = 0f
    private var beatPulse = 0f

    /**
     * Umpan analisis musik: cahaya tepi menyala menurut bass, berdenyut pada setiap ketukan (lebih tebal,
     * lebih terang, putarannya menyentak). Berhenti sendiri bila umpan berhenti.
     */
    fun setMusic(f: BeatAnalyzer.Frame) {
        val now = SystemClock.uptimeMillis()
        if (!f.music) {
            if (now > musicUntil) musicLevel = 0f
            return
        }
        musicUntil = now + 900L
        musicLevel += (f.level.coerceIn(0f, 1f) - musicLevel) * 0.35f
        musicBass += (f.bass.coerceIn(0f, 1f) - musicBass) * 0.5f
        if (f.beat) beatPulse = 1f
        invalidate()
    }

    /** Hentikan reaksi musik segera. */
    fun clearMusic() {
        musicUntil = 0L
        musicLevel = 0f
        musicBass = 0f
        beatPulse = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = SweepGradient(w / 2f, h / 2f, colors, stops)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val music = now < musicUntil && musicLevel > 0.02f
        if (!music && musicLevel > 0f) musicLevel = max(0f, musicLevel - dt * 2f)
        beatPulse = max(0f, beatPulse - dt * 4.5f)
        val a = if (music) (musicLevel * 1.6f).coerceIn(0f, 1f) else 0f
        // Saat musik: dasar menyala menurut bass, ditambah lonjakan tiap ketukan; keadaan lain tetap berlaku.
        val eff = if (music) max(target, (0.30f + 0.40f * musicBass * a + 0.30f * beatPulse).coerceAtMost(1f)) else target
        current += (eff - current) * min(1f, dt * (if (music) 16f else 5f))
        level += (targetLevel - level) * min(1f, dt * 10f)
        angle = (angle + (spin + (if (music) 30f + 150f * beatPulse + 60f * musicBass else 0f)) * dt) % 360f
        if (current < 0.01f && target == 0f && !music) {
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
        val boost = 1f + level * 0.8f + beatPulse * 0.7f * a
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

package id.nine.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Orb animasi Nine: bola kaca bercahaya dengan pusaran warna dan cincin HUD yang berputar.
 * Bereaksi terhadap suara saat mendengarkan, berputar cepat saat berpikir, dan berdenyut saat berbicara.
 */
class NineOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var targetLevel = 0f
    private var level = 0f
    private var t = 0f
    private var ringAngle = 0f
    private var ringAlpha = 0.25f
    private var lastFrame = 0L

    /** Tingkat suara mikrofon 0..1. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    private val cyan = Color.rgb(0x35, 0xE0, 0xFF)
    private val blue = Color.rgb(0x3D, 0x6B, 0xFF)
    private val violet = Color.rgb(0x8B, 0x5C, 0xFF)
    private val gold = Color.rgb(0xFF, 0xC2, 0x4D)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val oval = RectF()

    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        val speed = when (mode) {
            Mode.IDLE -> 0.6f
            Mode.LISTENING -> 1.4f
            Mode.THINKING -> 2.6f
            Mode.SPEAKING -> 1.6f
        }
        t += dt * speed
        val wanted = when (mode) {
            Mode.IDLE -> 0f
            Mode.LISTENING -> 0.15f + targetLevel * 0.85f
            Mode.THINKING -> 0.22f
            Mode.SPEAKING -> 0.25f + 0.35f * abs(sin(t * 5.3f)) * (0.6f + 0.4f * abs(sin(t * 2.1f)))
        }
        level += (wanted - level) * min(1f, dt * 9f)
        val ringTarget = when (mode) {
            Mode.IDLE -> 0.22f
            Mode.LISTENING -> 0.85f
            Mode.THINKING -> 1f
            Mode.SPEAKING -> 0.65f
        }
        ringAlpha += (ringTarget - ringAlpha) * min(1f, dt * 4f)
        ringAngle += dt * when (mode) {
            Mode.THINKING -> 260f
            Mode.LISTENING -> 70f + level * 140f
            Mode.SPEAKING -> 90f
            Mode.IDLE -> 18f
        }

        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.56f
        val r = base * (1f + 0.035f * sin(t * 1.6f) + level * 0.16f)
        val dark = night

        // 1) Cahaya luar
        // Dibatasi ke tepi view agar cahaya memudar penuh sebelum terpotong.
        val glowR = min(r * (1.75f + level * 0.35f), min(width, height) / 2f)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(alpha(cyan, if (dark) 0.42f + level * 0.3f else 0.28f + level * 0.25f), alpha(blue, if (dark) 0.16f else 0.10f), Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)

        // 2) Bola + pusaran warna
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        basePaint.color = if (dark) Color.rgb(0x07, 0x0B, 0x14) else Color.rgb(0xE9, 0xF0, 0xFF)
        canvas.drawCircle(cx, cy, r, basePaint)
        val colors = intArrayOf(cyan, blue, violet, gold)
        for (i in 0 until 4) {
            val dir = if (i % 2 == 0) 1f else -1f
            val angle = t * (0.9f + 0.33f * i) * dir + i * 1.5708f
            val dist = r * (0.30f + 0.12f * sin(t * 1.3f + i)) * (1f + level * 0.45f)
            val bx = cx + cos(angle) * dist
            val by = cy + sin(angle) * dist
            val br = r * ((if (i == 3) 0.55f else 0.85f) + 0.15f * sin(t * 2f + i) + level * 0.3f)
            val a = if (i == 3) 0.55f else 0.9f
            blobPaint.shader = RadialGradient(
                bx, by, br,
                intArrayOf(alpha(colors[i], a), alpha(colors[i], a * 0.35f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(bx, by, br, blobPaint)
        }
        // kilau kaca di atas
        highlightPaint.shader = LinearGradient(
            cx, cy - r, cx, cy,
            intArrayOf(Color.argb(if (dark) 70 else 140, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        oval.set(cx - r * 0.72f, cy - r * 0.95f, cx + r * 0.72f, cy - r * 0.05f)
        canvas.drawOval(oval, highlightPaint)
        canvas.restore()

        // 3) Tepi bola
        rimPaint.strokeWidth = r * 0.03f
        rimPaint.color = if (dark) Color.argb(70, 255, 255, 255) else Color.argb(90, 61, 107, 255)
        canvas.drawCircle(cx, cy, r, rimPaint)

        // 4) Cincin HUD
        val ringR = r * 1.24f
        oval.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        ringPaint.strokeWidth = r * 0.045f
        ringPaint.color = alpha(gold, ringAlpha * 0.95f)
        val arc = 46f + level * 34f
        for (k in 0 until 3) canvas.drawArc(oval, ringAngle + k * 120f, arc, false, ringPaint)

        val ring2 = r * 1.40f
        oval.set(cx - ring2, cy - ring2, cx + ring2, cy + ring2)
        ringPaint.strokeWidth = r * 0.022f
        ringPaint.color = alpha(cyan, ringAlpha * 0.8f)
        for (k in 0 until 12) canvas.drawArc(oval, -ringAngle * 0.6f + k * 30f, 14f, false, ringPaint)

        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) invalidate()
    }
}

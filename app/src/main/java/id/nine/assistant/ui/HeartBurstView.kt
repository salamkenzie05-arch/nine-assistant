package id.nine.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Muncratan hati seperti di Live Instagram: hati-hati mungil menyembur dari satu titik, melayang naik sambil
 * meliuk, lalu memudar. Menumpuk di atas seluruh layar tetapi tidak menangkap sentuhan.
 */
class HeartBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Heart(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val size: Float,
        val color: Int,
        val born: Long,
        val life: Long,
        val phase: Float,
        val sway: Float
    )

    private val hearts = ArrayList<Heart>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val density = resources.displayMetrics.density
    private var originX = 0f
    private var originY = 0f
    private var spawnUntil = 0L
    private var lastFrame = 0L
    private var spawnCarry = 0f

    private val palette = intArrayOf(
        Color.rgb(0xFF, 0x4D, 0x6D), Color.rgb(0xFF, 0x6B, 0x8A), Color.rgb(0xFF, 0x3B, 0x5C),
        Color.rgb(0xFF, 0x8F, 0xA3), Color.rgb(0xF9, 0x5C, 0x8F), Color.rgb(0xFF, 0xB3, 0xC1)
    )

    /** True selama masih ada hati yang melayang atau yang akan keluar. */
    val active: Boolean get() = hearts.isNotEmpty() || SystemClock.uptimeMillis() < spawnUntil

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** Semburkan hati dari titik layar ([screenX], [screenY]) selama [durationMs]. */
    fun burst(screenX: Float, screenY: Float, durationMs: Long = 3400L) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        originX = screenX - loc[0]
        originY = screenY - loc[1]
        spawnUntil = SystemClock.uptimeMillis() + durationMs
        lastFrame = 0L
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    /** Hentikan dan bersihkan segera. */
    fun clear() {
        hearts.clear()
        spawnUntil = 0L
        invalidate()
    }

    private fun spawn(now: Long) {
        val up = (380f + Random.nextFloat() * 520f) * density
        val side = (Random.nextFloat() * 2f - 1f) * 170f * density
        hearts.add(
            Heart(
                x = originX + (Random.nextFloat() * 2f - 1f) * 18f * density,
                y = originY + (Random.nextFloat() * 2f - 1f) * 10f * density,
                vx = side,
                vy = -up,
                size = (9f + Random.nextFloat() * 15f) * density,
                color = palette[Random.nextInt(palette.size)],
                born = now,
                life = 1500L + Random.nextLong(1300L),
                phase = Random.nextFloat() * (2f * PI.toFloat()),
                sway = (14f + Random.nextFloat() * 26f) * density
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        if (now < spawnUntil && hearts.size < MAX_HEARTS) {
            // sekitar 34 hati per detik, meredup di akhir
            val remaining = (spawnUntil - now).coerceAtMost(900L) / 900f
            spawnCarry += dt * 34f * remaining.coerceAtLeast(0.25f)
            while (spawnCarry >= 1f && hearts.size < MAX_HEARTS) {
                spawn(now)
                spawnCarry -= 1f
            }
        }

        val it = hearts.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val age = now - h.born
            if (age >= h.life) {
                it.remove()
                continue
            }
            val p = age / h.life.toFloat()
            // hambatan: laju turun seiring waktu, hati melayang pelan di akhir
            val drag = 1f - 0.72f * p
            h.x += h.vx * drag * dt * 0.55f
            h.y += h.vy * drag * dt * 0.62f
            val swayX = sin(age / 1000f * 3.4f + h.phase) * h.sway * dt * 6f
            h.x += swayX
            val pop = (age / 160f).coerceAtMost(1f)
            val fade = if (p > 0.6f) 1f - (p - 0.6f) / 0.4f else 1f
            val s = h.size * (0.35f + 0.65f * pop) * (0.9f + 0.1f * sin(age / 1000f * 6f + h.phase))
            paint.color = Color.argb((fade.coerceIn(0f, 1f) * 235).toInt(), Color.red(h.color), Color.green(h.color), Color.blue(h.color))
            heartPath(path, h.x, h.y, s)
            canvas.drawPath(path, paint)
        }

        if (active) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun heartPath(p: Path, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx, cy + s * 0.95f)
        p.cubicTo(cx - s * 1.45f, cy + s * 0.05f, cx - s * 1.0f, cy - s * 1.05f, cx, cy - s * 0.38f)
        p.cubicTo(cx + s * 1.0f, cy - s * 1.05f, cx + s * 1.45f, cy + s * 0.05f, cx, cy + s * 0.95f)
        p.close()
    }

    private companion object {
        const val MAX_HEARTS = 140
    }
}

package id.nine.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Bola kecil bergaya persis badan Nine ([NineFaceView]): gradien pelat yang sama (putih bercahaya di tema terang,
 * hitam dengan tepi emas di tema gelap), tepi setipis orb, bayangan lembut, dan cahaya emas tipis di sekelilingnya.
 * Dipakai sebagai tangan dan kaki Nine.
 *
 * Tampilan dibuat [PAD] kali lebih besar dari diameter bola supaya cahayanya tidak terpotong; shader dibuat ulang
 * hanya saat ukuran atau tema berubah.
 */
class SphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Lebar tampilan dibanding diameter bola. */
        const val PAD = 1.7f

        /** Cahaya emas berukuran 1 di (0,0), sama dengan cahaya di belakang wajah Nine; dibagi semua bola. */
        private val HALO: RadialGradient by lazy {
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Color.argb(255, 0xC7, 0x86, 0x1A), Color.argb(89, 0xC7, 0x86, 0x1A), Color.TRANSPARENT),
                floatArrayOf(0.62f, 0.8f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = HALO }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var keyW = -1
    private var keyH = -1
    private var keyDark: Boolean? = null

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onDraw(canvas: Canvas) {
        val d = dark
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f / PAD
        if (r <= 0f) return
        if (width != keyW || height != keyH || d != keyDark) {
            keyW = width
            keyH = height
            keyDark = d
            body.shader = if (d) {
                RadialGradient(cx, cy - r * 0.35f, r * 1.35f, intArrayOf(Color.rgb(0x1C, 0x1E, 0x24), Color.rgb(0x08, 0x09, 0x0C)), null, Shader.TileMode.CLAMP)
            } else {
                RadialGradient(cx, cy - r * 0.35f, r * 1.4f, intArrayOf(Color.WHITE, Color.rgb(0xEC, 0xEE, 0xF3)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            }
            shadow.shader = if (d) null else RadialGradient(
                cx, cy + r * 0.05f, r * 1.12f,
                intArrayOf(Color.argb(46, 11, 18, 32), Color.TRANSPARENT), floatArrayOf(0.86f, 1f), Shader.TileMode.CLAMP
            )
            rim.strokeWidth = r * 0.025f
            rim.color = if (d) Color.argb(115, 0xC7, 0x86, 0x1A) else Color.argb(40, 11, 18, 32)
            halo.alpha = ((if (d) 0.14f else 0.10f) * 255f + 0.5f).toInt()
        }
        // Cahaya emas tipis di belakang bola, sama seperti di belakang wajah Nine.
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(r * 1.38f, r * 1.38f)
        canvas.drawCircle(0f, 0f, 1f, halo)
        canvas.restore()
        if (!d) canvas.drawCircle(cx, cy + r * 0.05f, r * 1.12f, shadow)
        canvas.drawCircle(cx, cy, r, body)
        canvas.drawCircle(cx, cy, r, rim)
    }
}

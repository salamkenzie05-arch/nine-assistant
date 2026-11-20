package id.nine.assistant.a11y

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import id.nine.assistant.AssistActivity
import id.nine.assistant.MainActivity
import id.nine.assistant.NineApp
import kotlin.math.abs

/**
 * Tombol melayang seperti AssistiveTouch di iPhone: kotak kecil tembus pandang yang bisa diseret dan menempel ke tepi
 * layar. Mengetuknya membuka panel pintasan (Nine, Home, Kembali, Aplikasi terbaru, Notifikasi, Kunci, Volume, Pengaturan).
 * Digambar lewat jendela overlay aksesibilitas, jadi tidak butuh izin "tampil di atas aplikasi lain".
 */
class AssistiveTouch(private val svc: NineAccessibilityService) {

    private val ctx: Context = svc
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val sp = ctx.getSharedPreferences("nine_touch", Context.MODE_PRIVATE)
    private val sizePx = (54 * d).toInt()

    private var button: ButtonView? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: View? = null

    private val dim = Runnable { button?.animate()?.alpha(0.45f)?.setDuration(300)?.start() }

    private fun lang() = (ctx.applicationContext as NineApp).prefs.lang

    fun show() {
        if (button != null) return
        val (w, h) = screen()
        val b = ButtonView(ctx)
        val p = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = sp.getInt("x", w - sizePx - (6 * d).toInt())
        p.y = sp.getInt("y", (h * 0.62f).toInt())
        clamp(p, w, h)
        try {
            wm.addView(b, p)
        } catch (e: Exception) {
            return
        }
        button = b
        params = p
        b.alpha = 1f
        scheduleDim()
    }

    fun hide() {
        closePanel()
        main.removeCallbacks(dim)
        button?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // sudah dilepas
            }
        }
        button = null
        params = null
    }

    /** Dipanggil saat layar berputar: pastikan tombol tetap di dalam layar. */
    fun reposition() {
        val b = button ?: return
        val p = params ?: return
        val (w, h) = screen()
        clamp(p, w, h)
        try {
            wm.updateViewLayout(b, p)
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun screen(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun clamp(p: WindowManager.LayoutParams, w: Int, h: Int) {
        p.x = p.x.coerceIn(0, (w - sizePx).coerceAtLeast(0))
        p.y = p.y.coerceIn((24 * d).toInt(), (h - sizePx - (24 * d).toInt()).coerceAtLeast(0))
    }

    private fun scheduleDim() {
        main.removeCallbacks(dim)
        main.postDelayed(dim, 2800)
    }

    // ------------------------------------------------------------------ tombol

    private inner class ButtonView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(150, 28, 28, 32)
            rect.set(0f, 0f, s, s)
            canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.05f
            paint.color = Color.argb(70, 255, 255, 255)
            rect.set(s * 0.05f, s * 0.05f, s * 0.95f, s * 0.95f)
            canvas.drawRoundRect(rect, s * 0.26f, s * 0.26f, paint)
            // lingkaran bertingkat
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawCircle(s / 2f, s / 2f, s * 0.31f, paint)
            paint.color = Color.argb(150, 40, 40, 44)
            canvas.drawCircle(s / 2f, s / 2f, s * 0.25f, paint)
            paint.color = Color.argb(235, 255, 255, 255)
            canvas.drawCircle(s / 2f, s / 2f, s * 0.17f, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(dim)
                    animate().cancel()
                    alpha = 1f
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (w, h) = screen()
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        clamp(p, w, h)
                        try {
                            wm.updateViewLayout(this, p)
                        } catch (ex: Exception) {
                            // abaikan
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snap(p)
                    } else {
                        performClick()
                        togglePanel()
                    }
                    scheduleDim()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snap(p)
                    scheduleDim()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        override fun performClick(): Boolean = super.performClick()
    }

    /** Menempel ke tepi kiri/kanan terdekat. */
    private fun snap(p: WindowManager.LayoutParams) {
        val b = button ?: return
        val (w, _) = screen()
        val margin = (6 * d).toInt()
        val target = if (p.x + sizePx / 2 < w / 2) margin else w - sizePx - margin
        val from = p.x
        ValueAnimator.ofInt(from, target).apply {
            duration = 180
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                try {
                    wm.updateViewLayout(b, p)
                } catch (ex: Exception) {
                    // abaikan
                }
            }
            start()
        }
        sp.edit().putInt("x", target).putInt("y", p.y).apply()
    }

    // ------------------------------------------------------------------ panel

    private class Item(val glyph: String, val id: String, val en: String, val action: () -> Unit)

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun closePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // sudah dilepas
            }
        }
        panel = null
    }

    private fun act(delay: Long = 160, run: () -> Unit) {
        closePanel()
        main.postDelayed({
            try {
                run()
            } catch (e: Exception) {
                // abaikan
            }
        }, delay)
    }

    private fun openPanel() {
        val l = lang()
        val items = listOf(
            Item("◉", "Nine", "Nine") { act { openNine() } },
            Item("⌂", "Home", "Home") { act { svc.goHome() } },
            Item("◀", "Kembali", "Back") { act { svc.goBack() } },
            Item("▢", "Terbaru", "Recents") { act { svc.showRecents() } },
            Item("🔔", "Notifikasi", "Notifications") { act { svc.showNotifications() } },
            Item("🔒", "Kunci", "Lock") { act { svc.lockScreen() } },
            Item("🔊", "Volume +", "Volume +") { adjustVolume(true) },
            Item("🔉", "Volume −", "Volume −") { adjustVolume(false) },
            Item("⚙", "Setelan", "Settings") { act { openSettings() } }
        )
        val cell = (78 * d).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 34, 34, 38))
                cornerRadius = 26 * d
            }
        }
        for (r in 0 until 3) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val item = items[r * 3 + c]
                val v = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    background = GradientDrawable().apply {
                        setColor(Color.argb(40, 255, 255, 255))
                        cornerRadius = 18 * d
                    }
                    (layoutParams as LinearLayout.LayoutParams).setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
                    isClickable = true
                    setOnClickListener { _ -> item.action() }
                }
                v.addView(TextView(ctx).apply {
                    text = item.glyph
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    gravity = Gravity.CENTER
                })
                v.addView(TextView(ctx).apply {
                    text = if (l == id.nine.assistant.core.AppLang.ID) item.id else item.en
                    setTextColor(Color.argb(220, 255, 255, 255))
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                row.addView(v)
            }
            card.addView(row)
        }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(96, 0, 0, 0))
            isClickable = true
            setOnClickListener { closePanel() }
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, p)
            panel = root
        } catch (e: Exception) {
            // gagal menampilkan panel
        }
    }

    private fun openNine() {
        val prefs = (ctx.applicationContext as NineApp).prefs
        val i = Intent(ctx, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, prefs.launchToken)
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun openSettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun adjustVolume(up: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            // abaikan
        }
    }
}

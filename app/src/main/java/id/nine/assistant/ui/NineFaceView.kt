package id.nine.assistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import id.nine.assistant.audio.BeatAnalyzer
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import id.nine.assistant.core.Mood
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Wajah Nine: layar bulat dengan dua mata ekspresif (terinspirasi robot meja), menggantikan orb.
 * Mata berkedip, melirik, mengikuti jari, bereaksi saat disentuh/dielus, saat HP dicas atau baterai lemah,
 * dan berubah sesuai suasana percakapan (mendengar, berpikir, berbicara, senang, bingung, ...).
 */
class NineFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                touchActivity()
                // Mulai mendengar: lupakan ekspresi lama (kecuali kaget karena baru disentuh).
                if (value == Mode.LISTENING && SystemClock.uptimeMillis() - moodStarted > 600) clearMood()
            }
            invalidate()
        }

    /** Tampilkan cincin HUD emas di sekeliling wajah. */
    var showRing: Boolean = true

    /** Reaksi terhadap sentuhan & cas. Matikan untuk tampilan statis (mis. layar alarm). */
    var interactive: Boolean = true

    private var targetLevel = 0f
    private var level = 0f

    /** Tingkat suara mikrofon 0..1. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    /**
     * Umpan analisis musik dari mikrofon. Wajah ikut bergoyang pada ketukan, membesar pada bass, dan cahayanya
     * berubah warna menurut nada (rendah = hangat, tinggi = sejuk). Berhenti sendiri bila umpan berhenti.
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
        musicMid += (f.mid.coerceIn(0f, 1f) - musicMid) * 0.5f
        musicTreble += (f.treble.coerceIn(0f, 1f) - musicTreble) * 0.5f
        musicBright += (f.brightness.coerceIn(0f, 1f) - musicBright) * 0.15f
        if (f.beat) beatPulse = 1f
        invalidate()
    }

    /** Hentikan reaksi musik segera. */
    fun clearMusic() {
        musicUntil = 0L
        musicLevel = 0f
        beatPulse = 0f
    }

    private fun musicOn(now: Long): Boolean = now < musicUntil && musicLevel > 0.02f

    // ------------------------------------------------------------------ suasana hati

    private var mood: Mood = Mood.NEUTRAL
    private var moodUntil = 0L
    private var moodStarted = 0L

    /** Tunjukkan ekspresi [m] selama [ms] milidetik, lalu kembali normal. */
    fun react(m: Mood, ms: Long = 2600) {
        mood = m
        moodStarted = SystemClock.uptimeMillis()
        moodUntil = moodStarted + ms
        touchActivity()
        if (m == Mood.SURPRISED || m == Mood.HAPPY || m == Mood.LOVE || m == Mood.WINK) hop = 1f
        if (m == Mood.SCARED) tremble = 1f
        invalidate()
    }

    private fun clearMood() {
        moodUntil = 0L
    }

    private fun activeMood(now: Long): Mood? = if (now < moodUntil) mood else null

    // ------------------------------------------------------------------ status baterai

    private var charging = false
    private var batteryPct = 100
    private var batteryKnown = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) batteryPct = lvl * 100 / scale
            if (batteryKnown && interactive && nowCharging != charging) {
                if (nowCharging) {
                    // "Ahh, listrik!" kaget sebentar lalu senang
                    react(Mood.SURPRISED, 650)
                    postDelayed({ react(Mood.HAPPY, 2600) }, 650)
                } else {
                    react(Mood.SAD, 1500)
                }
            }
            charging = nowCharging
            batteryKnown = true
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            ContextCompat.registerReceiver(context, batteryReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)?.let {
                batteryReceiver.onReceive(context, it)
            }
        } catch (e: Exception) {
            // abaikan
        }
        lastFrame = 0L
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // abaikan
        }
    }

    // ------------------------------------------------------------------ sentuhan

    private var lastActivity = SystemClock.uptimeMillis()
    private var touching = false
    private var touchX = 0f
    private var touchY = 0f
    private var petDistance = 0f
    private val tapTimes = ArrayDeque<Long>()

    /**
     * Dipanggil sekali per usapan bila jari mengusap wajah terus-menerus selama [PET_BURST_MS] (5 detik).
     * Argumen: koordinat pusat wajah di layar.
     */
    var onPetBurst: ((screenX: Float, screenY: Float) -> Unit)? = null

    private var strokeStart = 0L
    private var strokeLast = 0L
    private var strokeFired = false

    private fun trackStroke(now: Long) {
        // Usapan terputus bila jari diam > 900 ms.
        if (strokeStart == 0L || now - strokeLast > 900L) {
            strokeStart = now
            strokeFired = false
        }
        strokeLast = now
        checkStrokeBurst(now)
    }

    private fun checkStrokeBurst(now: Long) {
        if (!strokeFired && strokeStart != 0L && touching && now - strokeStart >= PET_BURST_MS && now - strokeLast <= 900L) {
            strokeFired = true
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            onPetBurst?.invoke(loc[0] + width / 2f, loc[1] + height / 2f)
        }
    }

    private fun touchActivity() {
        lastActivity = SystemClock.uptimeMillis()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            tapTimes.addLast(now)
            while (tapTimes.isNotEmpty() && now - tapTimes.first() > 2200) tapTimes.removeFirst()
            if (interactive) {
                if (tapTimes.size >= 4) {
                    react(Mood.ANNOYED, 1800)
                    shake = 1f
                } else {
                    react(Mood.SURPRISED, 380)
                }
            }
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!interactive) return false
            trackStroke(SystemClock.uptimeMillis())
            petDistance += hypot(distanceX, distanceY)
            val r = faceRadius()
            if (petDistance > r * 1.1f) {
                // Dielus: senang lalu jatuh cinta
                react(if (petDistance > r * 3f) Mood.LOVE else Mood.HAPPY, 2200)
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                petDistance = 0f
                strokeStart = 0L
                strokeFired = false
                touchActivity()
                isPressed = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                isPressed = false
            }
        }
        touchX = event.x
        touchY = event.y
        gestures.onTouchEvent(event)
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ------------------------------------------------------------------ keadaan mata

    /** Parameter satu mata (nilai saat ini dan target dihaluskan). */
    private class Eye {
        var sx = 1f
        var sy = 1f
        var lid = 0f // tutupan kelopak atas 0..1
        var tilt = 0f // derajat; + = sudut dalam lebih rendah (kesal), - = sudut luar lebih rendah (sedih)
        var happy = 0f // lengkung bawah ^ 0..1
        var heart = 0f
        var chevron = 0f
    }

    private val left = Eye()
    private val right = Eye()
    private val tl = Eye()
    private val tr = Eye()
    private var gazeX = 0f
    private var gazeY = 0f
    private var tGazeX = 0f
    private var tGazeY = 0f
    private var faceTilt = 0f
    private var tFaceTilt = 0f
    private var hop = 0f
    private var shake = 0f
    private var tremble = 0f

    // --- reaksi musik (diisi lewat setMusic)
    private var musicUntil = 0L
    private var musicLevel = 0f
    private var musicBass = 0f
    private var musicMid = 0f
    private var musicTreble = 0f
    private var musicBright = 0.5f
    private var beatPulse = 0f
    private var musicSway = 0f

    private var t = 0f
    private var lastFrame = 0L
    private var nextBlink = 0L
    private var blinkStart = -1L
    private var doubleBlink = false
    private var nextGlance = 0L
    private var glanceX = 0f
    private var glanceY = 0f
    private var ringAngle = 0f
    private var ringAlpha = 0.2f
    private var thinkFlip = 0L
    private var thinkSide = 1f

    private fun reset(e: Eye) {
        e.sx = 1f; e.sy = 1f; e.lid = 0f; e.tilt = 0f; e.happy = 0f; e.heart = 0f; e.chevron = 0f
    }

    private fun approach(cur: Eye, tgt: Eye, k: Float) {
        cur.sx += (tgt.sx - cur.sx) * k
        cur.sy += (tgt.sy - cur.sy) * k
        cur.lid += (tgt.lid - cur.lid) * k
        cur.tilt += (tgt.tilt - cur.tilt) * k
        cur.happy += (tgt.happy - cur.happy) * k
        cur.heart += (tgt.heart - cur.heart) * k
        cur.chevron += (tgt.chevron - cur.chevron) * k
    }

    private var hourCache = 12
    private var hourAt = -60_000L

    private fun sleepyNow(now: Long): Boolean {
        if (mode != Mode.IDLE || touching) return false
        val idle = now - lastActivity
        if (now - hourAt > 30_000L) {
            hourCache = LocalTime.now().hour
            hourAt = now
        }
        val h = hourCache
        val night = h >= 23 || h < 5
        return idle > (if (night) 20_000L else 90_000L)
    }

    private fun lowBattery(): Boolean = batteryKnown && !charging && batteryPct <= 15

    private fun setTargets(now: Long) {
        reset(tl); reset(tr)
        tFaceTilt = 0f
        val m = activeMood(now)
        // --- dasar menurut mode
        when (mode) {
            Mode.IDLE -> {
                if (now > nextGlance) {
                    val center = Random.nextFloat() < 0.4f
                    glanceX = if (center) 0f else Random.nextFloat() * 1.6f - 0.8f
                    glanceY = if (center) 0f else Random.nextFloat() * 0.9f - 0.45f
                    nextGlance = now + 900 + Random.nextLong(2600)
                }
                tGazeX = glanceX; tGazeY = glanceY
                if (charging) { tl.happy = 0.3f; tr.happy = 0.3f }
                if (lowBattery()) { tl.lid = 0.32f; tr.lid = 0.32f; tl.tilt = -8f; tr.tilt = -8f; tGazeY = 0.35f }
                if (sleepyNow(now)) {
                    tl.lid = 0.62f; tr.lid = 0.62f; tGazeY = 0.45f; tGazeX = 0f
                }
            }
            Mode.LISTENING -> {
                tGazeX = 0f; tGazeY = -0.25f
                val s = 1.08f + level * 0.10f
                tl.sx = s; tr.sx = s
                tl.sy = s + level * 0.22f; tr.sy = s + level * 0.22f
            }
            Mode.THINKING -> {
                if (now > thinkFlip) {
                    thinkSide = -thinkSide
                    thinkFlip = now + 700 + Random.nextLong(700)
                }
                tGazeX = 0.75f * thinkSide; tGazeY = -0.7f
                tl.lid = 0.12f; tr.lid = 0.22f
                tl.sy = 0.95f; tr.sy = 0.9f
            }
            Mode.SPEAKING -> {
                tGazeX = glanceX * 0.3f; tGazeY = -0.05f
                tl.happy = 0.22f; tr.happy = 0.22f
                val talk = 0.06f * abs(sin(t * 9.5f)) * (0.6f + 0.4f * sin(t * 2.7f))
                tl.sy = 1f + talk; tr.sy = 1f + talk
            }
        }
        if (mode == Mode.IDLE && musicOn(now) && !touching) {
            val a = (musicLevel * 1.6f).coerceIn(0f, 1f)
            tl.happy = maxOf(tl.happy, 0.25f + 0.5f * musicTreble * a); tr.happy = tl.happy
            tl.sy = 1f + 0.16f * musicBass * a; tr.sy = tl.sy
            tl.sx = 1f + 0.06f * musicMid * a; tr.sx = tl.sx
            tl.lid = 0f; tr.lid = 0f
            tGazeX = sin(musicSway) * 0.7f
            tGazeY = -0.05f - 0.25f * musicBass * a
            tFaceTilt = sin(musicSway * 0.5f) * 5f * a
        }
        if (touching && interactive) {
            val r = faceRadius()
            tGazeX = ((touchX - width / 2f) / r).coerceIn(-1f, 1f)
            tGazeY = ((touchY - height / 2f) / r).coerceIn(-1f, 1f)
        }
        // --- suasana hati sementara
        when (m) {
            Mood.HAPPY -> { tl.happy = 1f; tr.happy = 1f; tl.sy = 0.95f; tr.sy = 0.95f; if (!touching) tGazeY = -0.1f }
            Mood.LOVE -> { tl.heart = 1f; tr.heart = 1f; val p = 1f + 0.08f * sin(t * 9f); tl.sx = p; tr.sx = p; tl.sy = p; tr.sy = p }
            Mood.SURPRISED -> { tl.sx = 1.12f; tr.sx = 1.12f; tl.sy = 1.3f; tr.sy = 1.3f; tl.lid = 0f; tr.lid = 0f }
            Mood.CONFUSED -> { tl.sy = 0.72f; tl.lid = 0.18f; tr.sy = 1.1f; tFaceTilt = -9f; if (!touching) { tGazeX = 0.45f; tGazeY = -0.45f } }
            Mood.SAD -> { tl.lid = 0.38f; tr.lid = 0.38f; tl.tilt = -20f; tr.tilt = -20f; tl.sy = 0.92f; tr.sy = 0.92f; if (!touching) { tGazeX = 0f; tGazeY = 0.6f } }
            Mood.ANNOYED -> { tl.chevron = 1f; tr.chevron = 1f }
            Mood.SLEEPY -> { tl.lid = 0.6f; tr.lid = 0.6f; tGazeY = 0.4f }
            Mood.CURIOUS -> { tl.sy = 0.9f; tr.sy = 1.15f; tFaceTilt = 7f; if (!touching) { tGazeX = 0.5f; tGazeY = -0.2f } }
            Mood.SCARED -> {
                // mata lebar & sempit, alis cemas, gemetar, melirik ke bawah-samping
                tl.sx = 0.82f; tr.sx = 0.82f; tl.sy = 1.28f; tr.sy = 1.28f
                tl.tilt = -14f; tr.tilt = -14f; tl.lid = 0.06f; tr.lid = 0.06f
                if (!touching) { tGazeX = 0.35f * sin(t * 3f); tGazeY = 0.3f }
            }
            Mood.WINK -> {
                // kiri terbuka riang, kanan melengkung ^ (mengedip)
                tl.sy = 1f; tr.happy = 1f; tr.sy = 0.95f; tFaceTilt = 6f
                if (!touching) { tGazeX = 0.1f; tGazeY = -0.1f }
            }
            Mood.LAUGH -> { tl.happy = 1f; tr.happy = 1f; tl.sy = 0.95f; tr.sy = 0.95f; if (!touching) tGazeY = -0.15f }
            Mood.NEUTRAL, null -> Unit
        }
    }

    private fun blinkFactor(now: Long): Float {
        val sleepy = sleepyNow(now)
        if (blinkStart < 0 && now > nextBlink && activeMood(now) != Mood.LOVE && activeMood(now) != Mood.ANNOYED) {
            blinkStart = now
            doubleBlink = Random.nextFloat() < 0.15f
        }
        if (blinkStart < 0) return 1f
        val dur = if (sleepy) 520f else 150f
        val p = (now - blinkStart) / dur
        if (p >= 1f) {
            if (doubleBlink) {
                doubleBlink = false
                blinkStart = now + 60
                return 1f
            }
            blinkStart = -1L
            nextBlink = now + (if (sleepy) 1800L else 2200L) + Random.nextLong(3400)
            return 1f
        }
        if (p < 0f) return 1f
        // turun cepat lalu naik
        val c = if (p < 0.45f) p / 0.45f else 1f - (p - 0.45f) / 0.55f
        return 1f - 0.92f * c.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ menggambar

    private val night: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val gold = Color.rgb(0xC7, 0x86, 0x1A)
    private val goldBright = Color.rgb(0xF2, 0xB2, 0x45)
    private val heartColor = Color.rgb(0xFF, 0x4D, 0x6D)
    private val red = Color.rgb(0xE5, 0x48, 0x4D)

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val extraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    /** Garis-garis tipis di mata (tekstur layar digital), bergulir pelan. */
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanMatrix = Matrix()
    private var scanGap = 0f
    private var scanDark: Boolean? = null
    private val chevFill = Path()
    private val eyePath = Path()
    private val cutPath = Path()
    private val tmpPath = Path()
    private val rect = RectF()

    // Shader dipakai ulang: dibuat hanya saat ukuran, tema, atau warna berubah (bukan tiap frame),
    // jadi tidak ada objek baru per frame dan tampilannya tetap sama persis.
    private val hsv = FloatArray(3)
    private var glowShader: RadialGradient? = null
    private var glowShaderColor = 0
    private var faceCx = -1f
    private var faceCy = -1f
    private var faceR = -1f
    private var faceDark: Boolean? = null
    private var eyeGlowRadius = 0f

    private fun faceRadius(): Float = min(width, height) / 2f * 0.70f

    private fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        t += dt
        if (nextBlink == 0L) nextBlink = now + 1500
        level += (targetLevel * (if (mode == Mode.LISTENING) 1f else 0f) - level) * min(1f, dt * 10f)

        setTargets(now)
        val k = 1f - exp(-dt * 13f)
        approach(left, tl, k)
        approach(right, tr, k)
        val gk = 1f - exp(-dt * (if (touching) 18f else 9f))
        gazeX += (tGazeX - gazeX) * gk
        gazeY += (tGazeY - gazeY) * gk
        faceTilt += (tFaceTilt - faceTilt) * k
        hop = (hop - dt * 3.2f).coerceAtLeast(0f)
        shake = (shake - dt * 1.8f).coerceAtLeast(0f)
        beatPulse = (beatPulse - dt * 5.5f).coerceAtLeast(0f)
        musicSway += dt * (2.4f + 2.2f * musicMid)
        tremble = (tremble - dt * 0.5f).coerceAtLeast(0f)
        if (touching) checkStrokeBurst(now)
        val music = musicOn(now)
        if (!music && musicLevel > 0f) musicLevel = (musicLevel - dt * 2f).coerceAtLeast(0f)

        val dark = night
        val cx = width / 2f
        val cy = height / 2f
        val r = faceRadius()

        // --- cincin HUD
        val ringTarget = if (!showRing) 0f else when (mode) {
            Mode.IDLE -> 0.18f
            Mode.LISTENING -> 0.9f
            Mode.THINKING -> 1f
            Mode.SPEAKING -> 0.6f
        }
        ringAlpha += (ringTarget - ringAlpha) * min(1f, dt * 4f)
        ringAngle += dt * when (mode) {
            Mode.THINKING -> 240f
            Mode.LISTENING -> 60f + level * 160f
            Mode.SPEAKING -> 80f
            Mode.IDLE -> 14f
        }

        // --- cahaya di belakang wajah (bereaksi pada suara)
        val mAmt = if (music && mode == Mode.IDLE) (musicLevel * 1.5f).coerceIn(0f, 1f) else 0f
        val glowR = min(r * (1.38f + level * 0.12f + mAmt * 0.10f + beatPulse * 0.06f * mAmt), min(width, height) / 2f)
        val ga = when (mode) {
            Mode.LISTENING -> 0.30f + level * 0.35f
            Mode.THINKING -> 0.26f
            Mode.SPEAKING -> 0.22f
            Mode.IDLE -> (if (dark) 0.14f else 0.10f) + mAmt * 0.22f + beatPulse * 0.18f * mAmt
        }
        // Nada rendah → hangat (emas), nada tinggi → sejuk (biru kehijauan); hanya saat ada musik.
        // Warna dibulatkan ke kelipatan 4 (selisih tak terlihat) agar shadernya jarang dibuat ulang.
        val glowColor = if (mAmt > 0.02f) {
            hsv[0] = 36f + 160f * musicBright.coerceIn(0f, 1f)
            hsv[1] = 0.72f
            hsv[2] = 1f
            blend(gold, Color.HSVToColor(hsv), mAmt) and 0xFFFCFCFC.toInt()
        } else gold
        // Gradien berukuran 1 di titik (0,0), lalu kanvas yang menskalakannya: radius boleh berubah
        // tiap frame tanpa membuat shader baru. Opasitas diatur lewat alpha paint.
        glowPaint.shader = glowShaderFor(glowColor)
        glowPaint.alpha = (ga.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(glowR, glowR)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()

        // --- pelat wajah
        val laugh = activeMood(now) == Mood.LAUGH
        val bob = sin(t * 1.5f) * r * 0.012f - hop * sin(hop * 3.14f) * r * 0.06f -
            (if (mAmt > 0f) beatPulse * r * 0.045f * mAmt else 0f) +
            (if (laugh) abs(sin(t * 14f)) * -r * 0.035f else 0f)
        val shakeX = (if (shake > 0f) sin(t * 60f) * r * 0.05f * shake else 0f) +
            (if (tremble > 0f && activeMood(now) == Mood.SCARED) sin(t * 48f) * r * 0.014f else 0f)
        val pulse = 1f + (if (mAmt > 0f) beatPulse * 0.05f * mAmt + musicBass * 0.02f * mAmt else 0f)
        canvas.save()
        canvas.translate(shakeX, bob)
        canvas.rotate(faceTilt, cx, cy)
        if (pulse != 1f) canvas.scale(pulse, pulse, cx, cy)
        ensureFaceShaders(cx, cy, r, dark)
        if (!dark) canvas.drawCircle(cx, cy + r * 0.05f, r * 1.12f, shadowPaint)
        canvas.drawCircle(cx, cy, r, platePaint)
        rimPaint.strokeWidth = r * 0.025f
        rimPaint.color = if (dark) alpha(gold, 0.45f) else Color.argb(40, 11, 18, 32)
        canvas.drawCircle(cx, cy, r, rimPaint)

        // --- mata
        val eyeColor = if (dark) goldBright else Color.rgb(0x13, 0x15, 0x1A)
        eyePaint.color = eyeColor
        val eyeGlow = if (dark && Build.VERSION.SDK_INT >= 28) r * 0.13f else 0f
        if (eyeGlow != eyeGlowRadius) {
            if (eyeGlow > 0f) eyePaint.setShadowLayer(eyeGlow, 0f, 0f, alpha(goldBright, 0.75f))
            else eyePaint.clearShadowLayer()
            eyeGlowRadius = eyeGlow
        }
        ensureScan(r, dark)
        scanMatrix.setTranslate(0f, (t * scanGap * 0.6f) % scanGap)
        scanPaint.shader?.setLocalMatrix(scanMatrix)
        val blink = blinkFactor(now)
        val sep = r * 0.37f
        val ey = cy - r * 0.04f
        val gx = gazeX * r * 0.17f
        val gy = gazeY * r * 0.13f
        drawEye(canvas, cx - sep + gx, ey + gy, r, left, blink, isLeft = true, color = eyeColor)
        drawEye(canvas, cx + sep + gx, ey + gy, r, right, blink, isLeft = false, color = eyeColor)

        // --- tambahan: pipi merona, petir cas, baterai lemah, zzz
        val m = activeMood(now)
        if (m == Mood.LOVE || m == Mood.LAUGH || (m == Mood.HAPPY && petDistance > r)) {
            extraPaint.color = alpha(heartColor, 0.28f)
            canvas.drawOval(cx - sep - r * 0.17f + gx, ey + r * 0.30f + gy, cx - sep + r * 0.13f + gx, ey + r * 0.42f + gy, extraPaint)
            canvas.drawOval(cx + sep - r * 0.13f + gx, ey + r * 0.30f + gy, cx + sep + r * 0.17f + gx, ey + r * 0.42f + gy, extraPaint)
        }
        if (charging) {
            drawBolt(canvas, cx, cy + r * 0.56f, r * 0.15f, if (dark) goldBright else gold, 0.55f + 0.45f * abs(sin(t * 2.2f)))
            canvas.drawPath(tmpPath, scanPaint)
        }
        if (!charging && lowBattery()) drawBattery(canvas, cx, cy + r * 0.58f, r * 0.11f, if (dark) Color.rgb(0x9A, 0xA0, 0xAE) else Color.rgb(0x6B, 0x72, 0x80))
        if (m == Mood.SLEEPY || (m == null && sleepyNow(now))) drawZzz(canvas, cx + r * 0.52f, cy - r * 0.42f, r, dark)
        if (m == Mood.SAD) {
            val p = (t * 0.7f) % 1f
            drawDrop(canvas, cx - sep + gx - r * 0.02f, ey + gy + r * 0.20f + p * r * 0.28f, r * 0.055f, 0.85f * (1f - p * 0.6f))
        }
        if (m == Mood.SCARED) {
            val p = (t * 0.9f) % 1f
            drawDrop(canvas, cx + r * 0.60f, cy - r * 0.34f + p * r * 0.22f, r * 0.07f, 0.9f * (1f - p * 0.5f))
        }
        canvas.restore()

        // --- cincin HUD di luar wajah
        if (ringAlpha > 0.02f) {
            val ringR = r * 1.17f
            rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            ringPaint.strokeWidth = r * 0.04f
            ringPaint.color = alpha(gold, ringAlpha * 0.95f)
            val arc = 44f + level * 36f
            for (i in 0 until 3) canvas.drawArc(rect, ringAngle + i * 120f, arc, false, ringPaint)
            val ring2 = r * 1.30f
            rect.set(cx - ring2, cy - ring2, cx + ring2, cy + ring2)
            ringPaint.strokeWidth = r * 0.018f
            ringPaint.color = alpha(gold, ringAlpha * 0.55f)
            for (i in 0 until 12) canvas.drawArc(rect, -ringAngle * 0.6f + i * 30f, 12f, false, ringPaint)
        }

        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawEye(canvas: Canvas, ex: Float, ey: Float, r: Float, e: Eye, blink: Float, isLeft: Boolean, color: Int) {
        val baseW = r * 0.33f
        val baseH = r * 0.50f
        // Hati
        if (e.heart > 0.5f) {
            val s = baseW * 0.62f * e.sx
            heartPath(tmpPath, ex, ey, s)
            eyePaint.color = heartColor
            canvas.drawPath(tmpPath, eyePaint)
            eyePaint.color = color
            canvas.drawPath(tmpPath, scanPaint)
            return
        }
        // > <
        if (e.chevron > 0.5f) {
            strokePaint.color = color
            strokePaint.strokeWidth = baseW * 0.32f
            val w = baseW * 0.42f
            val h = baseH * 0.32f
            val dir = if (isLeft) 1f else -1f
            tmpPath.reset()
            tmpPath.moveTo(ex - w * dir, ey - h)
            tmpPath.lineTo(ex + w * dir, ey)
            tmpPath.lineTo(ex - w * dir, ey + h)
            canvas.drawPath(tmpPath, strokePaint)
            @Suppress("DEPRECATION")
            strokePaint.getFillPath(tmpPath, chevFill)
            canvas.drawPath(chevFill, scanPaint)
            return
        }
        val w = baseW * e.sx
        val h = (baseH * e.sy * blink).coerceAtLeast(baseH * 0.07f)
        rect.set(ex - w / 2f, ey - h / 2f, ex + w / 2f, ey + h / 2f)
        val rad = min(w, h) * 0.34f
        eyePath.reset()
        eyePath.addRoundRect(rect, rad, rad, Path.Direction.CW)
        var cut = false
        cutPath.reset()
        // Kelopak atas (miring: sudut dalam/luar)
        if (e.lid > 0.01f || abs(e.tilt) > 0.5f) {
            val y0 = rect.top + h * e.lid
            val slope = tan(Math.toRadians(e.tilt.toDouble())).toFloat()
            // sisi dalam = ke arah tengah wajah
            val innerX = if (isLeft) rect.right else rect.left
            val outerX = if (isLeft) rect.left else rect.right
            val yInner = y0 + slope * w * 0.5f
            val yOuter = y0 - slope * w * 0.5f
            val ext = w
            val dxOut = if (isLeft) -ext else ext
            tmpPath.reset()
            tmpPath.moveTo(outerX + dxOut, yOuter)
            tmpPath.lineTo(innerX - dxOut, yInner)
            tmpPath.lineTo(innerX - dxOut, rect.top - h)
            tmpPath.lineTo(outerX + dxOut, rect.top - h)
            tmpPath.close()
            cutPath.addPath(tmpPath)
            cut = true
        }
        // Lengkung bahagia ^
        if (e.happy > 0.01f) {
            val oy = ey + h * (1.18f - 0.62f * e.happy)
            cutPath.addOval(ex - w * 0.9f, oy - h * 0.62f, ex + w * 0.9f, oy + h * 0.62f, Path.Direction.CW)
            cut = true
        }
        if (cut) eyePath.op(cutPath, Path.Op.DIFFERENCE)
        canvas.drawPath(eyePath, eyePaint)
        // Mata yang hampir tertutup (berkedip) tidak perlu garis.
        if (h > baseH * 0.2f) canvas.drawPath(eyePath, scanPaint)
    }

    private fun glowShaderFor(color: Int): RadialGradient {
        glowShader?.let { if (color == glowShaderColor) return it }
        val s = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(alpha(color, 1f), alpha(color, 0.35f), Color.TRANSPARENT),
            floatArrayOf(0.62f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        glowShader = s
        glowShaderColor = color
        return s
    }

    /** Siapkan shader bayangan & pelat wajah (sekali per ukuran/tema). */
    private fun ensureFaceShaders(cx: Float, cy: Float, r: Float, dark: Boolean) {
        if (cx == faceCx && cy == faceCy && r == faceR && dark == faceDark) return
        faceCx = cx; faceCy = cy; faceR = r; faceDark = dark
        shadowPaint.shader = if (dark) null else RadialGradient(
            cx, cy + r * 0.05f, r * 1.12f,
            intArrayOf(Color.argb(46, 11, 18, 32), Color.TRANSPARENT), floatArrayOf(0.86f, 1f), Shader.TileMode.CLAMP
        )
        platePaint.shader = if (dark) {
            RadialGradient(cx, cy - r * 0.35f, r * 1.35f, intArrayOf(Color.rgb(0x1C, 0x1E, 0x24), Color.rgb(0x08, 0x09, 0x0C)), null, Shader.TileMode.CLAMP)
        } else {
            RadialGradient(cx, cy - r * 0.35f, r * 1.4f, intArrayOf(Color.WHITE, Color.rgb(0xEC, 0xEE, 0xF3)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        }
    }

    /** Siapkan shader garis berulang (sekali per ukuran/tema). */
    private fun ensureScan(r: Float, dark: Boolean) {
        val gap = (r * 0.052f).coerceAtLeast(3f)
        if (gap == scanGap && dark == scanDark) return
        scanGap = gap
        scanDark = dark
        val line = if (dark) Color.argb(110, 0x0B, 0x0C, 0x0F) else Color.argb(64, 255, 255, 255)
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, gap,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, line, line),
            floatArrayOf(0f, 0.62f, 0.62f, 1f),
            Shader.TileMode.REPEAT
        )
    }

    private fun blend(a: Int, b: Int, k: Float): Int {
        val f = k.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )
    }

    /** Setetes air mata / keringat dingin. [s] = lebar tetesan. */
    private fun drawDrop(canvas: Canvas, cx: Float, cy: Float, s: Float, a: Float) {
        extraPaint.color = alpha(Color.rgb(0x6F, 0xB7, 0xF5), a)
        tmpPath.reset()
        tmpPath.moveTo(cx, cy - s * 1.6f)
        tmpPath.cubicTo(cx + s * 0.2f, cy - s * 0.9f, cx + s, cy - s * 0.2f, cx + s, cy + s * 0.4f)
        tmpPath.cubicTo(cx + s, cy + s * 1.1f, cx - s, cy + s * 1.1f, cx - s, cy + s * 0.4f)
        tmpPath.cubicTo(cx - s, cy - s * 0.2f, cx - s * 0.2f, cy - s * 0.9f, cx, cy - s * 1.6f)
        tmpPath.close()
        canvas.drawPath(tmpPath, extraPaint)
    }

    private fun heartPath(p: Path, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx, cy + s * 0.95f)
        p.cubicTo(cx - s * 1.45f, cy + s * 0.05f, cx - s * 1.0f, cy - s * 1.05f, cx, cy - s * 0.38f)
        p.cubicTo(cx + s * 1.0f, cy - s * 1.05f, cx + s * 1.45f, cy + s * 0.05f, cx, cy + s * 0.95f)
        p.close()
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, a: Float) {
        extraPaint.color = alpha(color, a)
        tmpPath.reset()
        tmpPath.moveTo(cx + s * 0.15f, cy - s)
        tmpPath.lineTo(cx - s * 0.55f, cy + s * 0.12f)
        tmpPath.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        tmpPath.lineTo(cx - s * 0.2f, cy + s)
        tmpPath.lineTo(cx + s * 0.55f, cy - s * 0.18f)
        tmpPath.lineTo(cx + s * 0.02f, cy - s * 0.18f)
        tmpPath.close()
        canvas.drawPath(tmpPath, extraPaint)
    }

    private fun drawBattery(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = s * 0.16f
        rect.set(cx - s * 0.9f, cy - s * 0.45f, cx + s * 0.75f, cy + s * 0.45f)
        canvas.drawRoundRect(rect, s * 0.15f, s * 0.15f, strokePaint)
        extraPaint.color = color
        canvas.drawRect(cx + s * 0.8f, cy - s * 0.18f, cx + s * 0.95f, cy + s * 0.18f, extraPaint)
        extraPaint.color = alpha(red, 0.6f + 0.4f * abs(sin(t * 3f)))
        canvas.drawRect(cx - s * 0.75f, cy - s * 0.3f, cx - s * 0.35f, cy + s * 0.3f, extraPaint)
    }

    private fun drawZzz(canvas: Canvas, x: Float, y: Float, r: Float, dark: Boolean) {
        textPaint.color = if (dark) Color.argb(170, 0xF2, 0xB2, 0x45) else Color.argb(150, 0x4B, 0x54, 0x66)
        for (i in 0 until 3) {
            val p = ((t * 0.45f + i / 3f) % 1f)
            textPaint.textSize = r * (0.11f + 0.06f * p)
            textPaint.alpha = ((1f - p) * (if (dark) 170 else 150)).toInt()
            canvas.drawText("z", x + p * r * 0.18f, y - p * r * 0.32f, textPaint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) {
            touchActivity()
            invalidate()
        }
    }

    private companion object {
        /** Lama mengusap terus-menerus sebelum hati bermuncratan. */
        const val PET_BURST_MS = 5000L
    }
}

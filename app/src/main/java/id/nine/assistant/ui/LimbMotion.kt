package id.nine.assistant.ui

import id.nine.assistant.core.Mood
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gerak tangan dan kaki Nine (Kotlin murni, tanpa Android, agar bisa diuji). Semua jarak dalam dp, relatif ke posisi diam,
 * dengan sumbu y positif ke bawah.
 *
 * Gerak dibuat dari beberapa lapis supaya terasa hidup dan tidak berulang:
 * - ayunan diam dari dua gelombang berperiode tak sebanding (tiap bola beda periode, fase, dan besar ayunan),
 *   yang kekuatannya naik turun pelan; tangan yang terangkat juga bergeser keluar, seperti ayunan pada bahu;
 * - gestur acak: melambai, merentang, bersorak (tangan), mengetuk, menggeser, menendang (kaki), sendiri atau berpasangan;
 * - suasana hati (senang, takut, sedih, dan seterusnya) menggeser posisi dan menguatkan atau melemahkan ayunan;
 * - lompatan saat badan diketuk: badan naik, tangan dan kaki menyusul dengan tertinggal;
 * - semuanya menuju target lewat pegas teredam, jadi ada tertinggal dan memantul, bukan gerak sinus kaku.
 */
class LimbMotion(seed: Long = 9L, private val autoGestures: Boolean = true) {

    enum class Gesture { NONE, WAVE, STRETCH, CHEER, TAP, SHUFFLE, KICK }

    class Limb internal constructor(
        val arm: Boolean,
        /** -1 kiri, +1 kanan. */
        val side: Float,
        internal val phase: Float,
        internal val periodA: Float,
        internal val periodB: Float,
        internal val ampX: Float,
        internal val ampY: Float,
        internal val stiffness: Float,
        internal val damping: Float,
        internal val hopDelay: Float
    ) {
        var x = 0f
            internal set
        var y = 0f
            internal set
        var vx = 0f
            internal set
        var vy = 0f
            internal set

        /** Peregangan/penyesakan dan kesan dekat-jauh; dipakai langsung sebagai skala tampilan. */
        var scaleX = 1f
            internal set
        var scaleY = 1f
            internal set

        /** Berapa kali gestur dimulai pada bola ini (untuk uji). */
        var gestureCount = 0
            internal set

        internal var kind = Gesture.NONE
        internal var start = 0f
        internal var duration = 1f
    }

    /** Urutan: tangan kiri, tangan kanan, kaki kiri, kaki kanan. */
    val limbs: List<Limb> = listOf(
        Limb(true, -1f, 0.0f, 3.4f, 5.3f, 5.0f, 4.5f, 66f, spring(66f, 0.50f), 0.05f),
        Limb(true, 1f, 2.1f, 3.9f, 6.1f, 5.5f, 4.0f, 74f, spring(74f, 0.50f), 0.07f),
        Limb(false, -1f, 1.0f, 3.1f, 4.7f, 3.0f, 2.2f, 104f, spring(104f, 0.65f), 0.00f),
        Limb(false, 1f, 3.3f, 3.6f, 5.6f, 3.4f, 2.0f, 116f, spring(116f, 0.65f), 0.02f)
    )

    /** Pergeseran vertikal badan (negatif = naik), untuk menggerakkan wajah. */
    var bodyY = 0f
        private set

    private var bodyVy = 0f
    private val rnd = Random(seed)
    private var hopAt = -100f
    private var mood = Mood.NEUTRAL
    private var moodUntil = 0f
    private var moodW = 0f
    private var energy = 1f
    private var nextArmAt = 2f + rnd.nextFloat() * 2f
    private var nextFootAt = 4f + rnd.nextFloat() * 3f

    // Hasil sementara (dipakai ulang agar tidak membuat objek tiap frame).
    private val targetX = FloatArray(limbs.size)
    private val targetY = FloatArray(limbs.size)
    private var gx = 0f
    private var gy = 0f
    private var mx = 0f
    private var my = 0f

    /** Badan melompat kecil (diketuk). */
    fun hop(now: Float) {
        hopAt = now
    }

    /** Tunjukkan suasana hati [m] selama [ms] milidetik mulai [now] (detik). */
    fun setMood(m: Mood, now: Float, ms: Long) {
        if (m == Mood.NEUTRAL) {
            moodUntil = 0f
            return
        }
        mood = m
        moodUntil = now + ms / 1000f
    }

    /**
     * True bila ada gerak cepat yang perlu digambar mulus (lompat, gestur, suasana hati). Ayunan diam saja
     * pelan (maks. beberapa dp per detik), jadi cukup digambar ~30 fps; ini dipakai untuk memilih laju gambar.
     */
    fun lively(now: Float): Boolean {
        if (now - hopAt < HOP_T + 0.7f) return true // lompatan + tertinggalnya tangan/kaki + mengendap
        if (moodW > 0.01f) return true
        for (l in limbs) if (l.kind != Gesture.NONE) return true
        return false
    }

    /** Majukan simulasi ke waktu [now] (detik) dengan selang [dtRaw] detik. */
    fun step(now: Float, dtRaw: Float) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        if (dt <= 0f) return
        schedule(now)

        val active = mood != Mood.NEUTRAL && now < moodUntil
        moodW += ((if (active) 1f else 0f) - moodW) * (1f - exp(-dt / 0.18f))
        if (!active && moodW < 0.01f) {
            moodW = 0f
            mood = Mood.NEUTRAL
        }
        energy = 1f + (moodEnergy(mood) - 1f) * moodW

        // Badan: naik saat melompat, plus napas pelan.
        val hopK = (now - hopAt) / HOP_T
        val hopBell = if (hopK in 0f..1f) sin(PI.toFloat() * hopK) else 0f
        val bodyTarget = -HOP_H * hopBell + 0.7f * sin(TAU * now / 3.4f)

        val substeps = max(1, ceil(dt / SUB).toInt())
        val h = dt / substeps
        // Target tangan dan kaki dihitung sekali per frame, pegas dihitung dengan langkah kecil agar stabil.
        for (i in limbs.indices) target(limbs[i], now, i)
        repeat(substeps) {
            bodyVy += (BODY_K * (bodyTarget - bodyY) - BODY_C * bodyVy) * h
            bodyY += bodyVy * h
            for (i in limbs.indices) {
                val l = limbs[i]
                l.vx += (l.stiffness * (targetX[i] - l.x) - l.damping * l.vx) * h
                l.vy += (l.stiffness * (targetY[i] - l.y) - l.damping * l.vy) * h
                l.x += l.vx * h
                l.y += l.vy * h
            }
        }
        for (l in limbs) {
            // Meregang saat melesat vertikal, dan sedikit lebih besar/kecil seperti maju-mundur (kesan kedalaman).
            val stretch = (abs(l.vy) / 600f).coerceAtMost(0.12f)
            val depth = 1f + 0.035f * sin(TAU * now / l.periodA + l.phase)
            l.scaleY = (1f + stretch) * depth
            l.scaleX = (1f - stretch * 0.7f) * depth
        }
    }

    // ------------------------------------------------------------------ target

    private fun target(l: Limb, now: Float, i: Int) {
        val wa = TAU * now / l.periodA + l.phase
        val wb = TAU * now / l.periodB + l.phase * 1.7f
        val e = energy * (0.75f + 0.25f * sin(TAU * now / 11f + l.phase * 3f))
        var x = e * (l.ampX * sin(wb) + 0.35f * l.ampX * sin(wa + 1.3f))
        var y = e * (l.ampY * sin(wa) + 0.4f * l.ampY * sin(wb * 0.9f + 0.5f))
        // Tangan yang terangkat (y negatif) bergeser keluar, seperti berayun pada bahu.
        if (l.arm) x -= l.side * y * 0.4f

        gesture(l, now)
        x += gx
        y += gy

        if (moodW > 0f) {
            moodOffset(l, mood, now)
            x += mx * moodW
            y += my * moodW
        }

        val k = (now - hopAt - l.hopDelay) / HOP_T
        if (k in 0f..1f) {
            val b = sin(PI.toFloat() * k)
            if (l.arm) {
                y -= 5f * b
                x += l.side * 6f * b
            } else {
                y -= 4f * b
            }
        }
        // Mengikuti badan (yang menyusul lewat pegas sendiri).
        y += bodyY
        targetX[i] = x
        targetY[i] = y
    }

    private fun smooth(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Naik halus di awal, turun halus di akhir. */
    private fun env(p: Float): Float = smooth(p / 0.18f) * (1f - smooth((p - 0.8f) / 0.2f))

    private fun gesture(l: Limb, now: Float) {
        gx = 0f
        gy = 0f
        if (l.kind == Gesture.NONE) return
        val t = now - l.start
        if (t < 0f) return // gestur berpasangan yang baru mulai sebentar lagi
        val p = t / l.duration
        if (p >= 1f) {
            l.kind = Gesture.NONE
            return
        }
        val e = env(p)
        val s = l.side
        when (l.kind) {
            Gesture.WAVE -> {
                gx = s * 12f * e + 5f * sin(TAU * 3f * t) * e
                gy = -24f * e
            }
            Gesture.STRETCH -> {
                gx = s * 16f * e
                gy = -8f * e
            }
            Gesture.CHEER -> {
                gx = s * 8f * e
                gy = (-20f + 4f * sin(TAU * 3f * t)) * e
            }
            Gesture.TAP -> gy = -7f * max(0f, sin(TAU * 2f * p)) * e
            Gesture.SHUFFLE -> {
                val w = sin(TAU * 2f * p + (if (s > 0f) PI.toFloat() else 0f))
                gx = 6f * w * e
                gy = -2f * abs(w) * e
            }
            Gesture.KICK -> {
                val b = sin(PI.toFloat() * p)
                gx = -s * 3f * b
                gy = -9f * b * e
            }
            Gesture.NONE -> {}
        }
    }

    private fun moodEnergy(m: Mood): Float = when (m) {
        Mood.HAPPY -> 1.5f
        Mood.LOVE -> 1.2f
        Mood.LAUGH -> 1.8f
        Mood.SURPRISED -> 0.6f
        Mood.SCARED -> 0.5f
        Mood.SAD -> 0.5f
        Mood.SLEEPY -> 0.35f
        Mood.CURIOUS -> 1f
        Mood.WINK -> 1.2f
        Mood.ANNOYED -> 0.8f
        Mood.CONFUSED -> 1.1f
        Mood.NEUTRAL -> 1f
    }

    private fun moodOffset(l: Limb, m: Mood, now: Float) {
        mx = 0f
        my = 0f
        val s = l.side
        when (m) {
            Mood.HAPPY, Mood.LOVE -> if (l.arm) {
                mx = s * 3f
                my = -6f - 2.5f * abs(sin(TAU * 1.2f * now + l.phase))
            } else {
                my = -2f * abs(sin(TAU * 1.2f * now + s * 0.7f))
            }
            Mood.LAUGH -> if (l.arm) {
                mx = s * 3f
                my = -8f + 4f * sin(TAU * 7f * now + l.phase)
            } else {
                my = -3f * abs(sin(TAU * 3.5f * now + s))
            }
            Mood.SURPRISED -> if (l.arm) {
                mx = s * 8f
                my = -12f
            } else {
                my = -3f
            }
            Mood.SCARED -> {
                val tr = 1.2f * sin(TAU * 9f * now + l.phase)
                if (l.arm) {
                    mx = -s * 10f + tr
                    my = -4f
                } else {
                    mx = -s * 4f + tr
                }
            }
            Mood.SAD -> if (l.arm) {
                mx = -s * 3f
                my = 7f
            } else {
                mx = -s * 2f
            }
            Mood.SLEEPY -> if (l.arm) my = 5f
            Mood.CURIOUS -> if (l.arm && s > 0f) {
                mx = 4f
                my = -9f
            }
            Mood.WINK -> if (l.arm && s > 0f) {
                mx = 5f + 4f * sin(TAU * 3f * now)
                my = -12f
            }
            Mood.ANNOYED -> if (l.arm) {
                mx = -s * 8f
                my = 2f
            } else {
                my = -5f * max(0f, sin(TAU * 3f * now + (if (s > 0f) PI.toFloat() else 0f)))
            }
            Mood.CONFUSED -> if (l.arm) {
                mx = s * 2f
                my = if (s < 0f) -6f else 3f
            }
            Mood.NEUTRAL -> {}
        }
    }

    // ------------------------------------------------------------------ gestur acak

    private fun busy(l: Limb): Boolean = l.kind != Gesture.NONE

    private fun begin(l: Limb, kind: Gesture, at: Float, duration: Float) {
        l.kind = kind
        l.start = at
        l.duration = duration
        l.gestureCount++
    }

    private fun schedule(now: Float) {
        if (!autoGestures) return
        if (moodW > 0.3f) {
            // Saat suasana hati sedang menonjol, tunda gestur.
            if (now >= nextArmAt) nextArmAt = now + 1f
            if (now >= nextFootAt) nextFootAt = now + 1f
            return
        }
        if (now >= nextArmAt) {
            nextArmAt = now + 2.5f + rnd.nextFloat() * 4f
            armGesture(now)
        }
        if (now >= nextFootAt) {
            nextFootAt = now + 4f + rnd.nextFloat() * 5f
            footGesture(now)
        }
    }

    private fun armGesture(now: Float) {
        val left = limbs[0]
        val right = limbs[1]
        if (busy(left) || busy(right)) return
        val r = rnd.nextFloat()
        val first = if (rnd.nextBoolean()) left else right
        val second = if (first === left) right else left
        when {
            r < 0.45f -> {
                begin(first, Gesture.WAVE, now, 2.0f)
                if (rnd.nextFloat() < 0.25f) begin(second, Gesture.WAVE, now + 0.18f, 2.0f)
            }
            r < 0.75f -> {
                begin(first, Gesture.STRETCH, now, 1.6f)
                begin(second, Gesture.STRETCH, now + 0.06f, 1.6f)
            }
            else -> {
                begin(first, Gesture.CHEER, now, 1.5f)
                begin(second, Gesture.CHEER, now + 0.08f, 1.5f)
            }
        }
    }

    private fun footGesture(now: Float) {
        val left = limbs[2]
        val right = limbs[3]
        if (busy(left) || busy(right)) return
        val r = rnd.nextFloat()
        val first = if (rnd.nextBoolean()) left else right
        val second = if (first === left) right else left
        when {
            r < 0.5f -> {
                begin(first, Gesture.TAP, now, 1.2f)
                begin(second, Gesture.TAP, now + 0.25f, 1.2f)
            }
            r < 0.8f -> {
                begin(left, Gesture.SHUFFLE, now, 1.6f)
                begin(right, Gesture.SHUFFLE, now, 1.6f)
            }
            else -> begin(first, Gesture.KICK, now, 0.9f)
        }
    }

    private companion object {
        val TAU = (2.0 * PI).toFloat()
        const val HOP_T = 0.55f
        const val HOP_H = 10f
        const val SUB = 1f / 120f
        const val BODY_K = 140f
        val BODY_C = 2f * 0.55f * sqrt(BODY_K)

        /** Redaman pegas untuk kekakuan [k] dan rasio redaman [zeta]. */
        fun spring(k: Float, zeta: Float): Float = 2f * zeta * sqrt(k)
    }
}

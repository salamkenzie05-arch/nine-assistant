package id.nine.assistant.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Suara Nine: "bahasa" droid berupa bip, kicau, siulan, dan trill elektronik yang disintesis sendiri (orisinal,
 * bukan rekaman atau tiruan karakter mana pun). Setiap kata menghasilkan pola bunyi yang tetap (kata yang sama
 * selalu terdengar sama, jadi terasa seperti bahasa), dan tanda baca menentukan nada akhir kalimat:
 * "?" naik, "!" bergetar riang, "." turun.
 *
 * Kotlin murni agar mudah diuji. Hasilnya PCM 16-bit mono pada [SAMPLE_RATE].
 */
object DroidVoice {
    const val SAMPLE_RATE = 22050

    /** Satu bunyi: meluncur dari [f0] ke [f1] Hz selama [ms], dengan getar (vibrato) dan modulasi FM. */
    private class Chirp(
        val f0: Double,
        val f1: Double,
        val ms: Int,
        val vibRate: Double = 0.0,
        val vibDepth: Double = 0.0,
        val fmRatio: Double = 2.0,
        val fmIndex: Double = 1.5,
        val gain: Double = 1.0
    )

    /** Pengacak deterministik (LCG) supaya hasil selalu sama untuk teks yang sama. */
    private class Rng(seed: Int) {
        private var s = seed.toLong() * 6364136223846793005L + 1442695040888963407L

        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33).toInt() and 0x7FFFFFFF) / 2147483648.0
        }

        fun range(a: Double, b: Double): Double = a + (b - a) * next()
    }

    private val wordRegex = Regex("[\\p{L}\\p{N}]+")

    /** Ubah [text] menjadi bunyi droid. Selalu mengembalikan setidaknya satu bip pendek. */
    fun render(text: String): ShortArray {
        val words = wordRegex.findAll(text).map { it.value.lowercase() }.toList()
        val mark = text.trimEnd().lastOrNull { !it.isWhitespace() && it != '"' && it != '\'' && it != ')' } ?: '.'
        val chirps = ArrayList<Any>() // Chirp atau Int (jeda dalam ms)
        if (words.isEmpty()) {
            chirps.add(Chirp(880.0, 1320.0, 90))
        } else {
            val n = (words.size * 0.7).roundToInt().coerceIn(2, 9)
            val slope = when (mark) {
                '?' -> 0.25
                '!' -> 0.06
                else -> -0.15
            }
            for (i in 0 until n) {
                val word = words[(i * words.size / n).coerceAtMost(words.size - 1)]
                val rng = Rng(word.hashCode() xor (word.length * 7919))
                val last = i == n - 1
                val contour = 1.0 + slope * (if (n > 1) i.toDouble() / (n - 1) else 0.0)
                val base = rng.range(520.0, 1420.0) * contour
                if (last) {
                    chirps.addAll(ending(mark, base, rng))
                } else {
                    chirps.addAll(chirpsFor(abs(word.hashCode()) % 6, base, rng))
                }
                if (!last) {
                    // jeda singkat antar bunyi; sesekali jeda panjang seperti jeda frasa
                    chirps.add(if (rng.next() < 0.15) rng.range(80.0, 120.0).toInt() else rng.range(20.0, 46.0).toInt())
                }
            }
        }
        return synthesize(chirps)
    }

    private fun chirpsFor(kind: Int, base: Double, rng: Rng): List<Any> = when (kind) {
        0 -> listOf(Chirp(base * 0.7, base * 1.6, rng.range(70.0, 140.0).toInt(), fmIndex = rng.range(1.0, 2.5)))
        1 -> listOf(Chirp(base * 1.5, base * 0.65, rng.range(80.0, 160.0).toInt(), fmIndex = rng.range(1.0, 2.5)))
        2 -> listOf(
            Chirp(
                base, base * rng.range(0.95, 1.1), rng.range(110.0, 210.0).toInt(),
                vibRate = rng.range(14.0, 34.0), vibDepth = rng.range(0.08, 0.22), fmIndex = rng.range(0.6, 1.6)
            )
        )
        3 -> {
            val f = rng.range(1500.0, 2400.0)
            listOf(
                Chirp(
                    f, f * rng.range(0.85, 1.3), rng.range(120.0, 260.0).toInt(),
                    vibRate = rng.range(5.0, 8.0), vibDepth = 0.02, fmIndex = 0.0, gain = 0.8
                )
            )
        }
        4 -> listOf(
            Chirp(base, base * 1.2, 55),
            18,
            Chirp(base * 1.5, base * 1.5, 60, fmIndex = 1.0)
        )
        else -> listOf(
            Chirp(
                rng.range(150.0, 270.0), rng.range(150.0, 270.0), rng.range(90.0, 140.0).toInt(),
                vibRate = rng.range(30.0, 45.0), vibDepth = 0.05, fmRatio = 1.5, fmIndex = 4.0, gain = 0.7
            )
        )
    }

    /** Bunyi penutup kalimat sesuai tanda baca. */
    private fun ending(mark: Char, base: Double, rng: Rng): List<Any> = when (mark) {
        '?' -> listOf(Chirp(base, base * 2.1, 170, fmIndex = 1.5))
        '!' -> listOf(
            Chirp(base, base * 1.15, 90, fmIndex = 1.2),
            14,
            Chirp(base * 1.3, base * 1.3, 190, vibRate = 24.0, vibDepth = 0.2, fmIndex = 1.2)
        )
        else -> listOf(Chirp(base * 1.2, base * 0.55, rng.range(130.0, 190.0).toInt(), fmIndex = 1.8))
    }

    private fun synthesize(items: List<Any>): ShortArray {
        var total = 0
        for (item in items) total += when (item) {
            is Chirp -> item.ms * SAMPLE_RATE / 1000
            is Int -> item * SAMPLE_RATE / 1000
            else -> 0
        }
        val out = ShortArray(total + SAMPLE_RATE / 50) // sedikit ekor hening di akhir
        var pos = 0
        for (item in items) {
            when (item) {
                is Int -> pos += item * SAMPLE_RATE / 1000
                is Chirp -> pos = writeChirp(item, out, pos)
            }
        }
        return out
    }

    private fun writeChirp(c: Chirp, out: ShortArray, start: Int): Int {
        val n = c.ms * SAMPLE_RATE / 1000
        val attack = minOf((0.006 * SAMPLE_RATE).toInt(), n / 4).coerceAtLeast(1)
        val release = minOf((0.03 * SAMPLE_RATE).toInt(), n / 2).coerceAtLeast(1)
        val ratio = c.f1 / c.f0
        var phase = 0.0
        var mod = 0.0
        val twoPi = 2.0 * PI
        for (k in 0 until n) {
            val t = k.toDouble() / n
            var f = c.f0 * ratio.pow(t)
            if (c.vibRate > 0.0) f *= 1.0 + c.vibDepth * sin(twoPi * c.vibRate * k / SAMPLE_RATE)
            phase += twoPi * f / SAMPLE_RATE
            mod += twoPi * f * c.fmRatio / SAMPLE_RATE
            val v = 0.8 * sin(phase + c.fmIndex * (1.0 - t) * sin(mod)) + 0.2 * sin(2.0 * phase)
            val env = when {
                k < attack -> k.toDouble() / attack
                k > n - release -> (n - k).toDouble() / release
                else -> 1.0
            } * (1.0 - 0.25 * t)
            val idx = start + k
            if (idx < out.size) out[idx] = (v * env * c.gain * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        return start + n
    }
}

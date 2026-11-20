package id.nine.assistant.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pendeteksi nada dasar (algoritma YIN) untuk tuner gitar. Kotlin murni.
 * Cocok untuk senar gitar standar (±70–400 Hz) pada sampling 16 kHz dengan jendela ≥ 2048 sampel.
 */
object PitchDetector {

    data class Result(val frequency: Double, val confidence: Double)

    /** Kekuatan sinyal (RMS, 0..1) dari [pcm]. */
    fun rms(pcm: ShortArray, n: Int = pcm.size): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = pcm[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n)
    }

    /**
     * Cari nada dasar pada [pcm] ([n] sampel pertama). Mengembalikan null bila terlalu pelan atau tidak bernada.
     * [minRms] = ambang sinyal; [threshold] = ambang YIN (makin kecil makin ketat).
     */
    fun detect(
        pcm: ShortArray,
        sampleRate: Int,
        n: Int = pcm.size,
        minHz: Double = 65.0,
        maxHz: Double = 700.0,
        minRms: Double = 0.01,
        threshold: Double = 0.15
    ): Result? {
        if (n < 1024 || rms(pcm, n) < minRms) return null
        val tauMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / minHz).toInt()
        val w = n - tauMax
        if (w < tauMax) return null
        val x = DoubleArray(n) { pcm[it] / 32768.0 }
        // buang komponen DC
        var mean = 0.0
        for (v in x) mean += v
        mean /= n
        for (i in 0 until n) x[i] -= mean

        val d = DoubleArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0.0
            for (i in 0 until w) {
                val diff = x[i] - x[i + tau]
                sum += diff * diff
            }
            d[tau] = sum
        }
        // selisih rata-rata kumulatif ternormalisasi
        val cmnd = DoubleArray(tauMax + 1)
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += d[tau]
            cmnd[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
        }
        // lembah pertama di bawah ambang
        var tauEst = -1
        var tau = tauMin
        while (tau < tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 < tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEst = tau
                break
            }
            tau++
        }
        if (tauEst < 0) {
            // tanpa lembah jelas: ambil yang terendah bila cukup meyakinkan
            var best = tauMin
            for (t in tauMin..tauMax) if (cmnd[t] < cmnd[best]) best = t
            if (cmnd[best] > 0.35) return null
            tauEst = best
        }
        // interpolasi parabola untuk ketelitian sub-sampel
        val better = if (tauEst in 2 until tauMax) {
            val s0 = cmnd[tauEst - 1]
            val s1 = cmnd[tauEst]
            val s2 = cmnd[tauEst + 1]
            val denom = 2.0 * (2.0 * s1 - s2 - s0)
            if (abs(denom) < 1e-12) tauEst.toDouble() else tauEst + (s2 - s0) / denom
        } else {
            tauEst.toDouble()
        }
        val f = sampleRate / better
        if (f < minHz || f > maxHz) return null
        return Result(f, (1.0 - cmnd[tauEst]).coerceIn(0.0, 1.0))
    }
}

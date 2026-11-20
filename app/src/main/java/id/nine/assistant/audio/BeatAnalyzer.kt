package id.nine.assistant.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analisis musik sederhana dari mikrofon: energi bass/mid/treble, deteksi ketukan (beat), dan "terang"-nya nada.
 * Dipakai agar wajah Nine bergoyang mengikuti musik. Kotlin murni; panggil [process] untuk setiap blok sampel.
 */
class BeatAnalyzer(private val sampleRate: Int = 16000) {

    /** Hasil satu blok. Semua nilai 0..1 (sudah dinormalkan terhadap volume musik saat ini). */
    data class Frame(
        val bass: Float,
        val mid: Float,
        val treble: Float,
        /** Keras-pelannya suara keseluruhan. */
        val level: Float,
        /** Terang nada: 0 = dominan rendah (gelap/dalam), 1 = dominan tinggi (cerah). */
        val brightness: Float,
        val beat: Boolean,
        /** Perkiraan tempo (BPM), 0 bila belum diketahui. */
        val bpm: Int,
        /** True bila terdengar musik (bukan senyap/derau). */
        val music: Boolean
    )

    private val lpLow = 1f - exp(-2f * Math.PI.toFloat() * 150f / sampleRate)
    private val lpHigh = 1f - exp(-2f * Math.PI.toFloat() * 2200f / sampleRate)
    private var low1 = 0f
    private var low2 = 0f
    private var mid1 = 0f

    private val history = FloatArray(HISTORY)
    private var hCount = 0
    private var hPos = 0
    private var lastBeat = -10_000L
    private val intervals = ArrayDeque<Long>()
    private var peak = 0.02f
    private var floor = 0.004f
    private var loudFrames = 0
    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothTreble = 0f

    fun reset() {
        low1 = 0f; low2 = 0f; mid1 = 0f
        hCount = 0; hPos = 0
        lastBeat = -10_000L
        intervals.clear()
        peak = 0.02f; floor = 0.004f
        loudFrames = 0
        smoothBass = 0f; smoothMid = 0f; smoothTreble = 0f
    }

    /** Proses [n] sampel 16-bit pada waktu [nowMs]. */
    fun process(pcm: ShortArray, n: Int, nowMs: Long): Frame {
        var eLow = 0f
        var eMid = 0f
        var eHigh = 0f
        var eAll = 0f
        val count = min(n, pcm.size)
        for (i in 0 until count) {
            val x = pcm[i] / 32768f
            // bass: dua kali low-pass 150 Hz
            low1 += lpLow * (x - low1)
            low2 += lpLow * (low1 - low2)
            // treble: sinyal dikurangi low-pass 2,2 kHz
            mid1 += lpHigh * (x - mid1)
            val high = x - mid1
            val midBand = mid1 - low2
            eLow += low2 * low2
            eMid += midBand * midBand
            eHigh += high * high
            eAll += x * x
        }
        if (count == 0) return Frame(0f, 0f, 0f, 0f, 0f, false, 0, false)
        val rLow = sqrt(eLow / count)
        val rMid = sqrt(eMid / count)
        val rHigh = sqrt(eHigh / count)
        val rAll = sqrt(eAll / count)

        // lantai derau mengikuti pelan ke bawah, peak mengikuti cepat ke atas dan pelan ke bawah
        // (batas atas FLOOR_MAX: musik yang stabil tidak boleh dianggap derau latar setelah beberapa detik)
        floor = if (rAll < floor) rAll else min(floor + (rAll - floor) * 0.002f, FLOOR_MAX)
        peak = if (rAll > peak) rAll else peak + (rAll - peak) * 0.01f
        peak = max(peak, 0.02f)
        val musicNow = rAll > max(0.012f, floor * 2.2f)
        loudFrames = if (musicNow) min(loudFrames + 1, 200) else max(loudFrames - 2, 0)
        val music = loudFrames > 12

        val norm = max(peak, 0.02f)
        val bass = (rLow * 3.2f / norm).coerceIn(0f, 1f)
        val mid = (rMid * 3.2f / norm).coerceIn(0f, 1f)
        val treble = (rHigh * 4.5f / norm).coerceIn(0f, 1f)
        smoothBass += (bass - smoothBass) * 0.6f
        smoothMid += (mid - smoothMid) * 0.5f
        smoothTreble += (treble - smoothTreble) * 0.5f

        // ketukan: energi bass (dan sedikit mid) melonjak di atas rata-rata ±1 detik terakhir
        val flux = rLow + 0.35f * rMid
        var avg = 0f
        val have = min(hCount, HISTORY)
        for (i in 0 until have) avg += history[i]
        avg = if (have > 0) avg / have else flux
        history[hPos] = flux
        hPos = (hPos + 1) % HISTORY
        hCount++
        var beat = false
        if (music && have >= 8 && flux > avg * 1.38f + 0.004f && nowMs - lastBeat > 230) {
            beat = true
            if (lastBeat > 0 && nowMs - lastBeat < 1500) {
                intervals.addLast(nowMs - lastBeat)
                while (intervals.size > 8) intervals.removeFirst()
            }
            lastBeat = nowMs
        }
        if (nowMs - lastBeat > 2500) intervals.clear()

        val total = rLow + rMid + rHigh + 1e-6f
        val brightness = ((rMid * 0.5f + rHigh) / total * 2.2f).coerceIn(0f, 1f)
        val bpm = if (intervals.size >= 3) {
            val sorted = intervals.sorted()
            val median = sorted[sorted.size / 2].toFloat()
            var b = 60000f / median
            while (b > 190f) b /= 2f
            while (b < 60f) b *= 2f
            b.toInt()
        } else 0

        return Frame(
            bass = smoothBass,
            mid = smoothMid,
            treble = smoothTreble,
            level = (rAll / norm).coerceIn(0f, 1f),
            brightness = brightness,
            beat = beat,
            bpm = bpm,
            music = music
        )
    }

    private companion object {
        /** Lantai derau latar tertinggi yang mungkin (RMS 0..1). */
        const val FLOOR_MAX = 0.01f
        /** ±1 detik pada blok ±32 ms. */
        const val HISTORY = 30
    }
}

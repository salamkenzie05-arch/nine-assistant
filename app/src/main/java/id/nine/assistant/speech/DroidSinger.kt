package id.nine.assistant.speech

import id.nine.assistant.audio.GuitarTuning
import id.nine.assistant.audio.Songs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Nine menyanyi dengan suara droid: nada-nada bersuara "vokal" elektronik (a-o-i) dengan getar halus dan sedikit
 * kicau di akhir. Tidak ada lirik atau suara manusia; hanya melodi. Kotlin murni, keluaran PCM 16-bit mono.
 */
object DroidSinger {
    const val SAMPLE_RATE = DroidVoice.SAMPLE_RATE

    /** Hasil nyanyian: judul untuk ditampilkan dan sinyal PCM. [known] false = melodi karangan sendiri. */
    class Performance(val title: String, val known: Boolean, val pcm: ShortArray)

    private const val MAX_SECONDS = 32.0

    /** Nyanyikan [query] bila dikenal; bila tidak, karang melodi sendiri. */
    fun perform(query: String?, lang: id.nine.assistant.core.AppLang): Performance {
        val song = Songs.find(query)
        if (song != null) {
            return Performance(song.titleId.takeIf { lang == id.nine.assistant.core.AppLang.ID } ?: song.titleEn, true, render(song.notes, song.bpm))
        }
        val seed = (query ?: "nine").hashCode().let { if (query.isNullOrBlank()) (System.nanoTime() % 1000).toInt() else it }
        val title = lang.t("melodi karangan Nine", "a tune of my own")
        return Performance(title, false, render(Songs.improvise(seed), 108))
    }

    // formant vokal (Hz): F1, F2
    private val VOWELS = arrayOf(doubleArrayOf(800.0, 1250.0), doubleArrayOf(520.0, 950.0), doubleArrayOf(320.0, 2250.0))

    /** Render daftar nada pada tempo [bpm] (ketukan per menit). */
    fun render(notes: List<Songs.Note>, bpm: Int): ShortArray {
        val spb = 60.0 / bpm.coerceIn(40, 220)
        var total = 0.0
        val used = ArrayList<Songs.Note>()
        for (n in notes) {
            if (total + n.beats * spb > MAX_SECONDS) break
            used.add(n)
            total += n.beats * spb
        }
        val tail = 0.7
        val out = DoubleArray(((total + tail) * SAMPLE_RATE).toInt() + 1)
        var pos = 0.0
        var prevFreq = 0.0
        val phase = DoubleArray(HARMONICS)
        for ((idx, n) in used.withIndex()) {
            val startS = (pos * SAMPLE_RATE).toInt()
            val lenS = (n.beats * spb * SAMPLE_RATE).toInt()
            if (!n.rest) {
                val f = GuitarTuning.frequencyOfMidi(n.midi)
                val vowel = if (n.beats >= 2.0) VOWELS[1] else if (idx % 4 == 3) VOWELS[2] else VOWELS[0]
                writeNote(out, startS, lenS, f, if (prevFreq > 0) prevFreq else f, vowel, phase)
                prevFreq = f
            } else {
                prevFreq = 0.0
            }
            pos += n.beats * spb
        }
        // kicau penutup ala droid
        val endS = (pos * SAMPLE_RATE).toInt()
        chirp(out, endS + (0.08 * SAMPLE_RATE).toInt(), 700.0, 1900.0, 0.16)
        chirp(out, endS + (0.30 * SAMPLE_RATE).toInt(), 1500.0, 2300.0, 0.12)

        var peak = 0.0
        for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val g = if (peak > 0) 0.8 / peak else 1.0
        return ShortArray(out.size) { (out[it] * g * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private const val HARMONICS = 24

    private fun writeNote(out: DoubleArray, start: Int, len: Int, f: Double, fromF: Double, formants: DoubleArray, phase: DoubleArray) {
        if (len <= 0) return
        val attack = (0.012 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val release = (0.07 * SAMPLE_RATE).toInt().coerceAtMost(len / 2).coerceAtLeast(1)
        val glide = (0.045 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        // amplitudo tiap harmonik mengikuti puncak formant (dihitung dari nada target)
        val amps = DoubleArray(HARMONICS) { k ->
            val hf = f * (k + 1)
            if (hf > 7000.0) 0.0 else {
                var a = 0.0
                for (fm in formants) {
                    val d = (hf - fm) / (fm * 0.32)
                    a += exp(-d * d)
                }
                a / (k + 1).toDouble().pow(0.35)
            }
        }
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= out.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val glideMix = if (i < glide) i.toDouble() / glide else 1.0
            val base = fromF + (f - fromF) * glideMix
            val vib = 1.0 + 0.004 * sin(2.0 * PI * 5.6 * t) * (if (t > 0.15) 1.0 else t / 0.15)
            val fi = base * vib
            var s = 0.0
            for (k in 0 until HARMONICS) {
                val a = amps[k]
                if (a == 0.0) continue
                phase[k] += 2.0 * PI * fi * (k + 1) / SAMPLE_RATE
                if (phase[k] > 2.0 * PI * 1000) phase[k] -= 2.0 * PI * 1000
                s += a * sin(phase[k])
            }
            val env = when {
                i < attack -> i.toDouble() / attack
                i > len - release -> (len - i).toDouble() / release
                else -> 1.0
            }
            out[idx] += s * env * 0.22
        }
    }

    private fun chirp(out: DoubleArray, start: Int, f0: Double, f1: Double, seconds: Double) {
        val n = (seconds * SAMPLE_RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = start + i
            if (idx < 0 || idx >= out.size) continue
            val p = i.toDouble() / n
            val f = f0 + (f1 - f0) * p
            ph += 2.0 * PI * f / SAMPLE_RATE
            val env = sin(PI * p)
            out[idx] += (sin(ph) + 0.35 * sin(2.0 * ph)) * env * 0.12
        }
    }

    /** Nada murni [freq] Hz selama [ms] (acuan tuner), dengan pemudaran halus. */
    fun tone(freq: Double, ms: Int = 1800): ShortArray {
        val n = ms * SAMPLE_RATE / 1000
        val fade = (0.03 * SAMPLE_RATE).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade * 3 -> (n - i).toDouble() / (fade * 3)
                else -> 1.0
            } * (0.85 + 0.15 * exp(-t * 2.0))
            val s = sin(2.0 * PI * freq * t) + 0.25 * sin(4.0 * PI * freq * t) + 0.08 * sin(6.0 * PI * freq * t)
            (s * env * 0.55 * 32767.0 / 1.33).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}

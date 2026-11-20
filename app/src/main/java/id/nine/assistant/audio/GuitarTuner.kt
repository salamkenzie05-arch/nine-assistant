package id.nine.assistant.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Tuner gitar: mendengarkan mikrofon, mengenali senar yang dipetik (otomatis) atau memantau senar tertentu (manual),
 * dan melaporkan selisih nada dalam cent. Callback dipanggil di main thread.
 */
class GuitarTuner(context: Context, private val listener: Listener) {

    data class Reading(val string: GuitarTuning.GString, val cents: Double, val frequency: Double) {
        val inTune: Boolean get() = GuitarTuning.isInTune(cents)
    }

    interface Listener {
        /** Nada terdeteksi (diperbarui terus selama senar berbunyi). */
        fun onReading(reading: Reading)

        /** Senar sudah stabil terbaca (sekali per petikan): saat yang tepat untuk memberi saran. */
        fun onSettled(reading: Reading)

        /** Senar tidak lagi berbunyi. */
        fun onSilence()

        /** Ada bunyi bernada tetapi di luar jangkauan senar gitar. */
        fun onUnrecognized()
    }

    private val main = Handler(Looper.getMainLooper())
    private val mic = MicMonitor(context.applicationContext, SAMPLE_RATE, WINDOW, HOP) { pcm, n -> analyze(pcm, n) }

    /** Nomor senar yang dituju (1..6), null = otomatis. */
    @Volatile
    var target: Int? = null

    val isRunning: Boolean get() = mic.isRunning

    // keadaan analisis (hanya thread mic)
    private var lastRms = 0.0
    private var silentFrames = 0
    private var stable = 0
    private var settledSent = false
    private var lastString = -1
    private var lastCents = 0.0
    private val freqs = ArrayDeque<Double>()

    fun start(): Boolean {
        resetState()
        return mic.start()
    }

    fun stop() {
        mic.stop()
    }

    private fun resetState() {
        lastRms = 0.0
        silentFrames = 0
        stable = 0
        settledSent = false
        lastString = -1
        freqs.clear()
    }

    private fun analyze(pcm: ShortArray, n: Int) {
        val rms = PitchDetector.rms(pcm, n)
        val res = PitchDetector.detect(pcm, SAMPLE_RATE, n)
        if (res == null || res.confidence < 0.6) {
            silentFrames++
            if (rms < 0.012 && silentFrames >= 5) {
                if (stable > 0 || settledSent || freqs.isNotEmpty()) {
                    resetState()
                    main.post { listener.onSilence() }
                }
            } else if (rms >= 0.03 && silentFrames == 3) {
                main.post { listener.onUnrecognized() }
            }
            lastRms = rms
            return
        }
        silentFrames = 0
        // petikan baru: energi melonjak
        if (lastRms > 0 && rms > lastRms * 2.4 && rms > 0.03) {
            stable = 0
            settledSent = false
            freqs.clear()
        }
        lastRms = rms
        freqs.addLast(res.frequency)
        while (freqs.size > 5) freqs.removeFirst()
        val f = freqs.sorted()[freqs.size / 2]

        val tgt = target?.let { GuitarTuning.byNumber(it) }
        val reading = if (tgt != null) {
            val c = GuitarTuning.cents(f, tgt.frequency)
            if (abs(c) > 700) {
                main.post { listener.onUnrecognized() }
                return
            }
            Reading(tgt, c, f)
        } else {
            val near = GuitarTuning.nearest(f)
            if (near == null) {
                main.post { listener.onUnrecognized() }
                return
            }
            Reading(near.first, near.second, f)
        }
        if (reading.string.number == lastString && abs(reading.cents - lastCents) < 15.0) stable++ else stable = 1
        lastString = reading.string.number
        lastCents = reading.cents
        main.post { listener.onReading(reading) }
        if (stable >= STABLE_FRAMES && !settledSent) {
            settledSent = true
            main.post { listener.onSettled(reading) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW = 2048
        const val HOP = 1024
        const val STABLE_FRAMES = 5
    }
}

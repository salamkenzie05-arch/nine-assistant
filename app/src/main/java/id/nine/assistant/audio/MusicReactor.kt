package id.nine.assistant.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Mendengarkan musik lewat mikrofon dan melaporkan bass/mid/treble/ketukan ke main thread untuk wajah Nine. */
class MusicReactor(context: Context, private val onFrame: (BeatAnalyzer.Frame) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val analyzer = BeatAnalyzer(SAMPLE_RATE)
    private val mic = MicMonitor(context.applicationContext, SAMPLE_RATE, BLOCK, BLOCK, preferUnprocessed = false) { pcm, n ->
        val frame = analyzer.process(pcm, n, SystemClock.uptimeMillis())
        main.post { onFrame(frame) }
    }

    val isRunning: Boolean get() = mic.isRunning

    fun start(): Boolean {
        analyzer.reset()
        return mic.start()
    }

    fun stop() {
        mic.stop()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val BLOCK = 512
    }
}

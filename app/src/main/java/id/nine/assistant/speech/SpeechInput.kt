package id.nine.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import id.nine.assistant.core.AppLang

/**
 * Pengenalan suara memakai recognizer ON-DEVICE bawaan Android.
 *
 * - Android 13+ (API 33): memakai SpeechRecognizer.createOnDeviceSpeechRecognizer, yang
 *   dijamin berjalan di perangkat (tanpa server). Paket bahasa offline (Indonesia/Inggris)
 *   harus terpasang; bila belum, sistem diminta mengunduhnya (butuh internet sekali).
 * - Android 8–12: memakai recognizer bawaan dengan EXTRA_PREFER_OFFLINE. Ini preferensi,
 *   bukan jaminan; pastikan paket bahasa offline sudah diunduh di aplikasi Google.
 *
 * Seluruh kelas ini harus dipakai dari main thread.
 */
class SpeechInput(private val context: Context, private val cb: Callback) {

    interface Callback {
        fun onListening()
        fun onPartial(text: String)
        /** Tingkat suara mikrofon 0..1 (untuk animasi orb). */
        fun onLevel(level: Float)
        /** Daftar hipotesis, yang terbaik lebih dulu. */
        fun onResult(alternatives: List<String>)
        fun onError(error: Error)
    }

    enum class Error {
        NO_SPEECH, NO_RECOGNIZER, LANGUAGE_UNAVAILABLE, LANGUAGE_NOT_SUPPORTED,
        NEEDS_NETWORK, PERMISSION, BUSY, OTHER
    }

    private var recognizer: SpeechRecognizer? = null
    private var lastIntent: Intent? = null

    /** True bila recognizer yang dipakai dijamin on-device. */
    var isGuaranteedOnDevice: Boolean = false
        private set

    fun isAvailable(): Boolean =
        (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) ||
            SpeechRecognizer.isRecognitionAvailable(context)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = cb.onListening()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB kira-kira -2..10
            cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) cb.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }.orEmpty()
            if (list.isEmpty()) cb.onError(Error.NO_SPEECH) else cb.onResult(list)
        }

        override fun onError(error: Int) {
            val mapped = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Error.NO_SPEECH
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Error.PERMISSION
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Error.BUSY
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    Error.NEEDS_NETWORK
                ERROR_LANGUAGE_NOT_SUPPORTED -> Error.LANGUAGE_NOT_SUPPORTED
                ERROR_LANGUAGE_UNAVAILABLE -> Error.LANGUAGE_UNAVAILABLE
                else -> Error.OTHER
            }
            // Setelah galat selain "tidak ada ucapan", buat ulang recognizer agar bersih.
            if (mapped != Error.NO_SPEECH) resetRecognizer()
            cb.onError(mapped)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        var r: SpeechRecognizer? = null
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                isGuaranteedOnDevice = true
            } catch (e: Exception) {
                r = null
            }
        }
        if (r == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            r = SpeechRecognizer.createSpeechRecognizer(context)
            isGuaranteedOnDevice = false
        }
        r?.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun buildIntent(lang: AppLang): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    fun start(lang: AppLang) {
        val r = ensureRecognizer()
        if (r == null) {
            cb.onError(Error.NO_RECOGNIZER)
            return
        }
        val intent = buildIntent(lang)
        lastIntent = intent
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            resetRecognizer()
            cb.onError(Error.OTHER)
        }
    }

    /** Berhenti merekam dan proses apa yang sudah terdengar. */
    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            // abaikan
        }
    }

    /** Batalkan tanpa hasil. */
    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // abaikan
        }
    }

    /** Minta sistem mengunduh paket bahasa offline (Android 13+). Hanya mengunduh bila ada internet. */
    fun requestLanguagePackDownload(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val r = recognizer ?: return false
        val intent = lastIntent ?: return false
        return try {
            r.triggerModelDownload(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun destroy() {
        resetRecognizer()
    }

    private fun resetRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // abaikan
        }
        recognizer = null
    }

    private companion object {
        // Konstanta ini baru ada di API 31; nilainya dipakai langsung agar kompatibel dengan minSdk 26.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

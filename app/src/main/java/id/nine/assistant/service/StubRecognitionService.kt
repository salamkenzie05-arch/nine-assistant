package id.nine.assistant.service

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Framework mewajibkan layanan asisten menyebut sebuah RecognitionService. Aplikasi ini memakai
 * pengenal suara bawaan sistem (lihat SpeechInput), jadi kelas ini hanya pelengkap dan sengaja
 * tidak mengiklankan diri lewat intent-filter agar tidak terpilih sebagai pengenal suara sistem.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            // abaikan
        }
    }

    override fun onStopListening(listener: Callback?) {}

    override fun onCancel(listener: Callback?) {}
}

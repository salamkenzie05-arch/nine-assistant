package id.nine.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Mendaftarkan Nine sebagai "Aplikasi asisten digital". Saat pengguna memanggil asisten (tekan lama tombol
 * samping, geser dari sudut layar) atau wake word terdeteksi, sistem membuat sesi lewat [AssistSessionService].
 */
class AssistService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: AssistService? = null

        /** Tampilkan Nine lewat jalur asisten resmi. Hanya berhasil bila Nine adalah asisten digital default. */
        fun showFromWakeWord(): Boolean {
            val s = instance ?: return false
            return try {
                s.showSession(Bundle().apply { putBoolean("wake", true) }, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

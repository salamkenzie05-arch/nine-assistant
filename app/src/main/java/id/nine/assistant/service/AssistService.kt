package id.nine.assistant.service

import android.service.voice.VoiceInteractionService

/**
 * Mendaftarkan aplikasi sebagai "Aplikasi asisten digital". Tidak perlu logika tambahan:
 * saat pengguna memanggil asisten (tekan lama tombol power/home, geser dari sudut layar),
 * sistem membuat sesi lewat [AssistSessionService].
 */
class AssistService : VoiceInteractionService()

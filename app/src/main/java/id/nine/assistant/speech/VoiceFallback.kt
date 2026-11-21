package id.nine.assistant.speech

import id.nine.assistant.llm.FailKind
import id.nine.assistant.llm.Failure

/** Kapan suara Wanita/Pria (Gemini) turun ke suara Droid, bukan ke suara bawaan HP. */
object VoiceFallback {

    /** Panjang paling banyak teks yang "dikicaukan" Droid per potongan saat menggantikan Gemini. */
    const val DROID_MAX_CHARS = 160

    /**
     * True bila kegagalan Gemini TTS karena kuota/batas pemakaian habis (per menit atau per hari): suara pindah otomatis ke
     * Droid. Kegagalan lain (offline, kunci salah, galat server) tetap turun ke suara HP seperti biasa.
     */
    fun toDroid(failure: Failure?): Boolean = failure != null && (failure.kind == FailKind.RATE_LIMIT || failure.kind == FailKind.QUOTA_DAILY)
}

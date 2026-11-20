package id.nine.assistant.core

/**
 * Tindakan yang dijalankan di sisi layar/pengontrol setelah sebuah perintah, bukan lewat Intent:
 * mengubah tema, keluar dari app, tuner gitar, nyanyian, nada acuan.
 */
sealed class UiAction {
    /** Terapkan tema (sudah disimpan di Prefs); dijalankan setelah jawaban selesai diucapkan. */
    data class SetTheme(val theme: Prefs.Theme) : UiAction()

    /** Tutup aplikasi (setelah jawaban selesai diucapkan). */
    data object ExitApp : UiAction()

    /** Mulai ([start] true) atau hentikan tuner gitar. [string] 1..6 = senar tertentu, null = otomatis. */
    data class Tuner(val start: Boolean, val string: Int?, val toneHz: Double? = null) : UiAction()

    /** Wake word dinyalakan/dimatikan lewat perintah. */
    data class WakeChanged(val on: Boolean) : UiAction()

    /** Pengaturan tombol melayang / reaksi musik berubah: layar menyesuaikan. */
    data object TouchChanged : UiAction()
    data object MusicReactChanged : UiAction()

    /** Nine menyanyi ([query] null = pilih sendiri). Dijalankan pengontrol lewat DroidSinger. */
    data class Sing(val query: String?) : UiAction()

    /** Bunyikan nada acuan [frequency] Hz (tuner). */
    data class Tone(val frequency: Double) : UiAction()
}

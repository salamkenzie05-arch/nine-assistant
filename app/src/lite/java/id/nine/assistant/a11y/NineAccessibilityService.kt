package id.nine.assistant.a11y

import android.content.Context
import id.nine.assistant.nlu.SettingsTarget

/**
 * Edisi ringan (Nine.apk): TANPA layanan aksesibilitas. Google Play Protect memblokir pemasangan APK sideload yang
 * mendeklarasikan layanan aksesibilitas, jadi edisi ini tidak membawanya supaya bisa dipasang langsung.
 * Kelas ini hanya pengganti kosong dengan bentuk yang sama seperti di edisi Full, agar kode lain tidak perlu bercabang.
 */
class NineAccessibilityService private constructor() {

    enum class Outcome { DONE, ALREADY, CLICKED, NOT_FOUND, BUSY, FAILED }

    @Suppress("UNUSED_PARAMETER")
    fun toggle(target: SettingsTarget, on: Boolean, done: (Outcome) -> Unit) {
        done(Outcome.FAILED)
    }

    @Suppress("UNUSED_PARAMETER")
    fun playFirstSong(query: String = "") {}

    /** Selalu kosong di edisi ringan. */
    val lastStage: String = ""

    companion object {
        /** False di edisi ringan: fitur yang butuh aksesibilitas hanya ada di Nine-Full.apk. */
        const val AVAILABLE = false

        val instance: NineAccessibilityService? = null

        @Suppress("UNUSED_PARAMETER")
        fun isEnabled(ctx: Context): Boolean = false

        @Suppress("UNUSED_PARAMETER")
        fun applyTouch(ctx: Context): Boolean = true
    }
}

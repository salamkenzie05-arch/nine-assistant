package id.nine.assistant.core

import android.view.View

/**
 * Penanda beban: saat otak AI memuat model atau sedang menjawab, animasi layar dibatasi sekitar 30 fps
 * supaya tidak berebut CPU/GPU dengan inferensi (animasi tetap mulus karena dihitung dari waktu, bukan dari jumlah frame).
 */
object Perf {
    @Volatile
    var heavy: Boolean = false

    private const val HEAVY_FRAME_MS = 33L

    /** Jadwalkan frame berikutnya untuk [v]: tiap vsync normalnya, tiap ~33 ms saat [heavy]. */
    fun next(v: View) {
        if (heavy) v.postInvalidateDelayed(HEAVY_FRAME_MS) else v.postInvalidateOnAnimation()
    }
}

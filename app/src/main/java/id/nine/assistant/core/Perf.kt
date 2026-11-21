package id.nine.assistant.core

import android.view.View

/**
 * Pengatur laju gambar untuk semua animasi layar. Tujuannya satu: tidak menggambar lebih sering
 * daripada yang terlihat oleh mata, supaya CPU/GPU cepat kembali tidur (baterai awet, HP tidak hangat).
 *
 * Laju dipilih menurut keperluan ([Pace]), lalu dinaikkan batas bawahnya bila:
 *  - otak AI sedang memuat/menjawab ([heavy]) agar tidak berebut CPU/GPU dengan inferensi,
 *  - HP mulai panas ([thermal]), atau
 *  - mode Hemat Daya menyala ([saver]).
 *
 * Animasi tetap mulus karena semuanya dihitung dari waktu, bukan dari jumlah frame.
 */
object Perf {
    /** Seberapa sering sebuah tampilan perlu digambar. Angka = jeda minimal antar-awal-frame (ms). */
    enum class Pace(val ms: Long) {
        /** Ada gerakan yang sedang diamati: ~60 fps (di layar 120 Hz pun hanya 60 fps; di 90 Hz tetap 90). */
        ACTIVE(10),

        /** Diam menunggu, tetapi masih hidup (bernapas, melirik): ~30 fps. */
        CALM(25),

        /** Mengantuk / layar statis: ~20 fps. */
        SLEEP(42)
    }

    @Volatile
    var heavy: Boolean = false

    /** Tingkat panas: 0 = normal, 1 = hangat (MODERATE), 2 = panas (SEVERE ke atas). */
    @Volatile
    var thermal: Int = 0

    /** Mode Hemat Daya sistem menyala. */
    @Volatile
    var saver: Boolean = false

    /** Jeda minimal antar-awal-frame untuk keadaan ini. Fungsi murni, diuji di unit test. */
    fun intervalFor(pace: Pace, heavy: Boolean, thermal: Int, saver: Boolean): Long {
        var ms = pace.ms
        if (heavy) ms = maxOf(ms, Pace.CALM.ms)
        if (saver) ms = maxOf(ms, Pace.CALM.ms)
        ms = when {
            thermal >= 2 -> maxOf(ms, Pace.SLEEP.ms)
            thermal == 1 -> maxOf(ms, Pace.CALM.ms)
            else -> ms
        }
        return ms
    }

    /** Ubah status termal sistem (PowerManager.THERMAL_STATUS_*) menjadi [thermal]: MODERATE → 1, SEVERE ke atas → 2. */
    fun thermalLevel(status: Int): Int = when {
        status >= 3 -> 2
        status == 2 -> 1
        else -> 0
    }

    /**
     * Sisa jeda sebelum frame berikutnya, dihitung dari AWAL frame yang sedang digambar
     * (bukan dari akhirnya) supaya lamanya menggambar tidak ikut memperlambat laju.
     * 0 berarti langsung jadwalkan di vsync berikutnya.
     */
    fun delayFor(interval: Long, elapsedInFrame: Long): Long = (interval - elapsedInFrame).coerceAtLeast(0L)

    /**
     * Jadwalkan frame berikutnya untuk [v]. [frameStart] = `SystemClock.uptimeMillis()` saat onDraw dimulai.
     */
    fun next(v: View, pace: Pace, frameStart: Long) {
        val interval = intervalFor(pace, heavy, thermal, saver)
        val wait = delayFor(interval, android.os.SystemClock.uptimeMillis() - frameStart)
        if (wait <= 0L) v.postInvalidateOnAnimation() else v.postInvalidateDelayed(wait)
    }

    /** Jeda yang dipakai animasi berbasis handler (mis. gerak tangan/kaki) untuk [pace] saat ini. */
    fun tickMs(pace: Pace): Long = intervalFor(pace, heavy, thermal, saver)
}

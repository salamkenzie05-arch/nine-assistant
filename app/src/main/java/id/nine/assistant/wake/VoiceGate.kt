package id.nine.assistant.wake

import kotlin.math.sqrt

/**
 * Gerbang suara untuk pendengar kata pemanggil (Kotlin murni, tanpa Android, agar bisa diuji).
 *
 * Mendekode audio dengan Vosk itu mahal bila dilakukan terus-menerus, padahal sebagian besar waktu HP berada di ruangan
 * yang hening. Gerbang ini mengukur tenaga (RMS) tiap bingkai ~20 ms dan hanya meneruskan audio ke pengenal saat ada suara:
 *  - pengenal tidur selama hening, jadi CPU ikut tidur (hemat baterai, tidak hangat);
 *  - 400 ms audio sebelum suara terdeteksi ikut dikirim ("pre-roll") supaya awal kata (mis. "h" pada "hey") tidak terpotong;
 *  - setelah suara berhenti, gerbang masih terbuka 800 ms ("hangover") agar jeda antarkata tidak memotong frasa;
 *  - ambang buka mengikuti derau ruangan: [openFactor] kali tingkat derau, dibatasi [minOpenRms]..[maxOpenRms];
 *  - di tempat yang sangat bising gerbang justru terus terbuka, yaitu perilaku lama (tidak lebih buruk dari sebelumnya).
 */
class VoiceGate(
    /** Jumlah sampel per bingkai (320 = 20 ms pada 16 kHz). Bingkai masukan boleh lebih pendek, tidak boleh lebih panjang tanpa dialokasi ulang. */
    val frameSamples: Int = 320,
    private val preRollFrames: Int = 20,
    private val hangoverFrames: Int = 40,
    private val minOpenRms: Float = 180f,
    private val maxOpenRms: Float = 1800f,
    private val openFactor: Float = 3.5f,
    private val closeRatio: Float = 0.7f,
    /** Setelah terbuka selama ini (bingkai), anggap terus-menerus bising: derau mulai dipelajari ulang. */
    private val adaptAfterFrames: Int = 250
) {
    interface Sink {
        /** Potongan audio untuk didekode, selalu berurutan; awal ucapan didahului pre-roll. */
        fun onAudio(buf: ShortArray, len: Int)

        /** Ucapan selesai (hening melewati hangover). Saatnya meminta hasil akhir dari pengenal. */
        fun onUtteranceEnd()
    }

    var isOpen: Boolean = false
        private set

    /** Perkiraan tingkat derau latar (RMS). */
    var noiseFloor: Float = 40f
        private set

    private val ring = Array(preRollFrames) { ShortArray(frameSamples) }
    private val ringLen = IntArray(preRollFrames)
    private var ringHead = 0
    private var ringCount = 0
    private var quiet = 0
    private var openFrames = 0

    /** Ambang RMS untuk membuka gerbang saat ini. */
    fun openThreshold(): Float = (noiseFloor * openFactor).coerceIn(minOpenRms, maxOpenRms)

    /** Masukkan satu bingkai audio [frame] sepanjang [len] sampel. */
    fun feed(frame: ShortArray, len: Int, sink: Sink) {
        if (len <= 0) return
        val rms = rmsOf(frame, len)
        val thr = openThreshold()
        if (!isOpen) {
            if (rms >= thr) {
                isOpen = true
                quiet = 0
                openFrames = 0
                flushPreRoll(sink)
                sink.onAudio(frame, len)
            } else {
                learn(rms, 0.1f, 0.01f)
                keep(frame, len)
            }
            return
        }
        sink.onAudio(frame, len)
        openFrames++
        if (rms >= thr * closeRatio) {
            quiet = 0
            // Terbuka sangat lama oleh bunyi yang rata: itu derau latar, pelan-pelan jadikan acuan agar bisa menutup lagi.
            if (openFrames > adaptAfterFrames) learn(rms, 0f, 0.004f)
        } else {
            quiet++
            if (quiet >= hangoverFrames) {
                isOpen = false
                quiet = 0
                openFrames = 0
                ringCount = 0
                sink.onUtteranceEnd()
            }
        }
    }

    /** Kosongkan keadaan (mis. saat mikrofon dimulai ulang), derau dipelajari ulang. */
    fun reset() {
        isOpen = false
        quiet = 0
        openFrames = 0
        ringCount = 0
        ringHead = 0
        noiseFloor = 40f
    }

    private fun learn(rms: Float, fall: Float, rise: Float) {
        val k = if (rms < noiseFloor) fall else rise
        noiseFloor += (rms - noiseFloor) * k
        if (noiseFloor < 5f) noiseFloor = 5f
    }

    private fun keep(frame: ShortArray, len: Int) {
        if (preRollFrames == 0) return
        if (ring[ringHead].size < len) ring[ringHead] = ShortArray(len)
        System.arraycopy(frame, 0, ring[ringHead], 0, len)
        ringLen[ringHead] = len
        ringHead = (ringHead + 1) % preRollFrames
        if (ringCount < preRollFrames) ringCount++
    }

    private fun flushPreRoll(sink: Sink) {
        if (ringCount == 0) return
        var i = (ringHead - ringCount + preRollFrames) % preRollFrames
        repeat(ringCount) {
            sink.onAudio(ring[i], ringLen[i])
            i = (i + 1) % preRollFrames
        }
        ringCount = 0
    }

    companion object {
        fun rmsOf(buf: ShortArray, len: Int): Float {
            if (len <= 0) return 0f
            var sum = 0.0
            for (i in 0 until len) {
                val s = buf[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / len).toFloat()
        }
    }
}

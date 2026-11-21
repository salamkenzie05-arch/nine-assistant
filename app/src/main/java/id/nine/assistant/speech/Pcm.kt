package id.nine.assistant.speech

/** Audio PCM 16-bit mono. */
class Pcm(val samples: ShortArray, val rate: Int)

/** Membaca audio hasil Gemini TTS: berkas WAV (RIFF) atau PCM mentah 16-bit little-endian mono. */
object WavPcm {
    const val DEFAULT_RATE = 24_000

    /** Laju sampel dari tipe MIME seperti "audio/L16;codec=pcm;rate=24000"; null bila tidak ada. */
    fun rateFromMime(mime: String?): Int? {
        val m = Regex("rate=(\\d{4,6})", RegexOption.IGNORE_CASE).find(mime ?: return null) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 8_000..96_000 }
    }

    /** Null bila [bytes] bukan audio yang bisa diputar (kosong, bukan 16-bit, atau WAV rusak). */
    fun decode(bytes: ByteArray, rateHint: Int = DEFAULT_RATE): Pcm? {
        if (bytes.size < 2) return null
        val isWav = bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
        if (!isWav) return pcm(bytes, 0, bytes.size, 1, rateHint)
        var pos = 12
        var channels = 1
        var rate = rateHint
        var bits = 16
        var format = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (body + 16 > bytes.size) return null
                format = le16(bytes, body)
                channels = le16(bytes, body + 2)
                rate = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
            } else if (id == "data") {
                // Ukuran 0 atau 0xFFFFFFFF dipakai untuk aliran: anggap sisa berkas.
                val end = if (size <= 0 || size > bytes.size - body) bytes.size else body + size
                if (bits != 16 || (format != 1 && format != 0xFFFE) || channels !in 1..2 || rate !in 8_000..96_000) return null
                return pcm(bytes, body, end, channels, rate)
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    private fun pcm(b: ByteArray, from: Int, to: Int, channels: Int, rate: Int): Pcm? {
        val frames = (to - from) / (2 * channels)
        if (frames <= 0) return null
        val out = ShortArray(frames)
        var p = from
        for (i in 0 until frames) {
            if (channels == 1) {
                out[i] = le16s(b, p)
                p += 2
            } else {
                out[i] = ((le16s(b, p) + le16s(b, p + 2)) / 2).toShort()
                p += 4
            }
        }
        return Pcm(out, rate)
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16s(b: ByteArray, i: Int): Short = le16(b, i).toShort()
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
}

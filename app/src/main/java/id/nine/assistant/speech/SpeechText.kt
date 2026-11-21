package id.nine.assistant.speech

import id.nine.assistant.core.Mood

/** Pembantu teks untuk suara: pemotongan di batas kalimat dan gaya bicara dari ekspresi Nine. Murni Kotlin (bisa diuji). */
object SpeechText {
    private val sentenceEnds = charArrayOf('.', '!', '?', '…', '。')

    /** Potong [text] sampai paling banyak [max] huruf, lebih disukai di akhir kalimat, lalu di spasi; tidak pernah di tengah kata bila bisa dihindari. */
    fun cap(text: String, max: Int): String {
        val t = text.trim()
        if (max <= 0) return ""
        if (t.length <= max) return t
        val head = t.substring(0, max)
        var end = -1
        for (i in head.indices.reversed()) {
            if (head[i] in sentenceEnds && (i == head.lastIndex || head[i + 1].isWhitespace())) {
                end = i
                break
            }
        }
        if (end >= max / 3) return head.substring(0, end + 1)
        // Batas kalimat jauh: bila potongan jatuh tepat di akhir kata, pakai apa adanya; bila tidak, mundur ke spasi terakhir.
        if (t[max].isWhitespace()) return head.trimEnd()
        val sp = head.lastIndexOf(' ')
        if (sp >= max / 2) return head.substring(0, sp).trimEnd()
        return head
    }

    /** Gaya bicara (bahasa Inggris, sesuai petunjuk gaya Gemini TTS) untuk ekspresi [mood]; null = suara biasa. */
    fun style(mood: Mood?): String? = when (mood) {
        null, Mood.NEUTRAL -> null
        Mood.HAPPY -> "warm and cheerful"
        Mood.LOVE -> "soft and affectionate"
        Mood.SURPRISED -> "surprised and excited"
        Mood.CONFUSED -> "puzzled and hesitant"
        Mood.SAD -> "gentle and a little sad"
        Mood.ANNOYED -> "dry and slightly annoyed"
        Mood.SLEEPY -> "sleepy and slow"
        Mood.CURIOUS -> "curious and interested"
        Mood.SCARED -> "nervous and worried"
        Mood.WINK -> "playful and teasing"
        Mood.LAUGH -> "amused, with a light laugh in the voice"
    }
}

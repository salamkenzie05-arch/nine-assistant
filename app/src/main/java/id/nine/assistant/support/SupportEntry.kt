package id.nine.assistant.support

import id.nine.assistant.core.AppLang

/**
 * Menilai apakah sebuah ucapan yang BUKAN perintah adalah curhat, sehingga Nine boleh masuk mode teman curhat sendiri
 * tanpa kata "aku mau curhat". Sengaja konservatif: lebih baik terlewat (pengguna masih bisa bilang "aku mau curhat")
 * daripada membajak pertanyaan atau percakapan biasa. Hanya dipakai bila parser perintah menghasilkan Unknown.
 * Kotlin murni, tanpa Android.
 */
object SupportEntry {

    private val FIRST = Regex("""\b(?:aku|akuu|gue|gua|saya|ku|i|im|my|me|myself)\b|\b\w+ku\b""")
    private val THIRD_OR_YOU = Regex(
        """\b(?:dia|mereka|kamu|anda|kalian|nine|you|he|she|they|his|her|their|ibu|ayah|bapak|mama|papa|teman|temanku|sahabat|pacar|mantan|bos|atasan|suami|istri|kakak|adik|anak|nenek|kakek)\b"""
    )
    /** Permintaan informasi/tindakan: bukan curhat. */
    private val REQUEST = Regex(
        """\b(?:tolong|carikan|cari|buka|bukakan|putar|putarkan|nyalakan|matikan|hitung|jam berapa|tanggal berapa|cuaca|ingatkan|catat|setel|atur|kirim|telepon|telpon|hubungi|resep|rekomendasi|rekomendasikan|jelaskan|ceritakan|apa itu|apa arti|cara|tips|bagaimana cara|gimana cara|how to|how do|how can|what is|what are|define|explain|tell me|please|can you|could you|search|open|play|call|remind|set)\b"""
    )
    private val QUESTION_START = Regex("""^(?:apa|apakah|siapa|kapan|dimana|di mana|berapa|bolehkah|what|who|when|where|which|is|are|does|do)\b""")
    /** Konteks latihan fisik: "capek" atau "kesal" di sini bukan curhat. */
    private val FITNESS = Regex("""\b(?:gym|latihan|workout|olahraga|angkat beban|lari|jogging|nge gym|ngegym|sit up|push up|squat|deadlift|bench|cardio|workout|training|exercise|running)\b""")
    private val SUBJECTLESS = Regex(
        """^(?:lagi |sedang |lagi lagi |rasanya |hari ini |akhir akhir ini |belakangan ini )*(?:sedih|cemas|takut|khawatir|kesepian|sepi|stres|stress|tertekan|kewalahan|putus asa|hancur|galau|down|sad|anxious|lonely|stressed|overwhelmed|hopeless)\b"""
    )
    private val HEAVY = setOf(Feelings.Emotion.GRIEF, Feelings.Emotion.HOPELESS, Feelings.Emotion.NUMB, Feelings.Emotion.SELF_WORTH)

    fun shouldOpen(raw: String): Boolean {
        val t = SupportText.norm(raw)
        if (t.isEmpty()) return false
        val rd = Feelings.read(raw)
        if (rd.words < 3 || rd.words > 200) return false
        if (SupportIntent.targetsOtherPerson(raw)) return false
        val negative = rd.emotions.filter { it != Feelings.Emotion.HAPPY }
        if (negative.isEmpty()) return false
        if (REQUEST.containsMatchIn(t)) return false
        if (QUESTION_START.containsMatchIn(t) && !rd.asksDiagnosis) return false
        val first = FIRST.containsMatchIn(t)
        val other = THIRD_OR_YOU.containsMatchIn(t)
        // Tanpa "aku": hanya kalimat pendek yang langsung menyebut perasaan ("lagi sedih banget"), tanpa orang lain.
        if (!first && !(SUBJECTLESS.containsMatchIn(t) && !other && rd.words <= 12)) return false
        val emo = negative.first()
        val primaryOnly = negative.size == 1
        val heavy = negative.any { it in HEAVY }
        val topic = rd.topic
        // Marah ke Nine/orang yang diajak bicara bukan curhat.
        if (emo == Feelings.Emotion.ANGRY && Regex("""\b(?:kamu|anda|nine|you)\b""").containsMatchIn(t)) return false
        if (FITNESS.containsMatchIn(t) && !heavy) return false
        if (heavy) return true
        if (primaryOnly) when (emo) {
            Feelings.Emotion.TIRED -> return (rd.intense || rd.persistent) && (topic != null || rd.words >= 6) && topic != Feelings.Topic.HEALTH && topic != Feelings.Topic.SLEEP
            Feelings.Emotion.CONFUSED -> return topic != null && rd.words >= 6
            Feelings.Emotion.ANGRY -> return (topic in setOf(Feelings.Topic.WORK, Feelings.Topic.FAMILY, Feelings.Topic.LOVE, Feelings.Topic.FRIENDS, Feelings.Topic.STUDY)) || rd.words >= 7
            else -> Unit
        }
        return true
    }

    /** Catatan singkat satu kali di balasan pertama agar pengguna tahu mode apa yang sedang berjalan. */
    fun note(lang: AppLang): String = lang.t(
        "(Aku dengarkan ya. Obrolan ini tidak disimpan. Bilang \"selesai\" kapan saja untuk kembali ke mode biasa.)",
        "(I'm listening. This chat isn't saved. Say \"done\" any time to go back to normal.)"
    )
}

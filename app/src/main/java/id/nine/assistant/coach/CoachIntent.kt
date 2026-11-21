package id.nine.assistant.coach

/** Maksud pengguna terkait mentor bahasa Inggris. */
sealed class CoachIntent {
    /** "jadi mentor bahasa Inggris saya", "let's practice English". */
    data object Start : CoachIntent()

    /** "stop English practice", "selesai latihan". */
    data object Stop : CoachIntent()

    /** "correct my sentence: ..." — periksa satu kalimat tanpa memulai sesi. */
    data class Correct(val sentence: String) : CoachIntent()

    /** "another word for good". */
    data class BetterWord(val word: String) : CoachIntent()

    /** Hanya di dalam sesi: "next question". */
    data object NextTopic : CoachIntent()

    /** Hanya di dalam sesi: "how am I doing". */
    data object Progress : CoachIntent()
}

/**
 * Mengenali permintaan mentor bahasa Inggris dari ucapan (Indonesia + English). Kotlin murni; dipakai oleh parser
 * perintah (di luar sesi) dan oleh sesi latihan (di dalam sesi). Pola sengaja ketat agar tidak membajak perintah lain
 * seperti "putar video belajar bahasa Inggris" atau "ganti bahasa ke Inggris".
 */
object CoachIntents {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val ENG = rx("""\b(?:inggris|english)\b""")
    private val START_WORDS = rx(
        """\b(?:mentor|tutor|tutoring|coach|coaching|teacher|teach|guru|pelatih|latihan|berlatih|belajar|learn|study|improve|ajari|ajarin|ajar|mengajari|""" +
            """practice|practise|practicing|practising|lesson|ngobrol|obrol|chat|conversation|percakapan)\b"""
    )
    private val NOT_START = rx(
        """\b(?:video|youtube|lagu|song|musik|music|putar|play|cari|search|buka|open|terjemah\w*|translate|artinya|arti|kamus|dictionary|ganti|switch|""" +
            """ubah|change|setting|pengaturan|cara|how\s+to|tips|kursus|course|aplikasi|app|download|unduh|beli|buy|harga|price|jadwal|schedule|alarm|""" +
            """ingatkan|remind\w*|catat|note|kirim|send|wa|whatsapp|sms|pesan|message|telepon|call|film|movie|buku|book|podcast|""" +
            """tugas|todo|to-do|tambah\w*|add|buat|bikin|create|hapus|delete|jam|pukul|besok|tomorrow|nanti|later|agenda|event|acara|kalender|calendar|""" +
            """rapat|meeting|catatan|reminder|timer)\b"""
    )
    private val MODE_ONLY = rx("""^(?:mode\s+(?:mentor|coach|tutor)|(?:mentor|coach|tutor)\s+mode)$""")
    private val START_SIMPLE = rx(
        """^(?:(?:correct|check|fix)\s+(?:my|me)\s+(?:grammar|english|sentences?|mistakes?|speaking)|""" +
            """(?:koreksi|perbaiki|periksa|cek)\s+(?:grammar|bahasa\s+inggris|kalimat|kesalahan)\s*(?:saya|ku|aku)?)$"""
    )

    private val STOP_VERBS = rx("""\b(?:stop|berhenti|selesai|keluar|akhiri|end|exit|quit|finish|cukup|hentikan|matikan|turn\s+off|disable|nonaktifkan)\b""")
    private val STOP_TARGET = rx("""\b(?:latihan|mentor|practice|practise|practicing|lesson|coach|coaching|tutor|tutoring|english|inggris|belajar|mode)\b""")
    private val STOP_ALONE = rx("""^(?:stop|exit|quit|cancel|enough|done|finish|end|that's\s+enough|selesai|berhenti|keluar|cukup|udah|sudah)(?:\s+(?:now|dong|ya|aja|deh|please))?$""")

    private const val SEP = """\s*[:,\-–—]?\s*"""
    private val CORRECT_PATTERNS = listOf(
        rx("""^(?:correct|check|fix|proofread|improve|review|edit)\s+(?:my|this|the|that)\s+(?:sentence|grammar|english|text|writing|phrase|message)$SEP(.+)$"""),
        rx("""^(?:is\s+it\s+(?:correct|right)\s+to\s+say|can\s+(?:i|we)\s+say|(?:is|are)\s+these\s+words\s+correct)$SEP(.+)$"""),
        rx("""^is\s+(?:this|that|it)\s+(?:sentence\s+)?(?:correct|right|ok|okay|good|grammatical)(?:\s+english|\s+grammar)?$SEP(.+)$"""),
        rx("""^how\s+(?:do|can|should)\s+i\s+say\s+(?:this|it)\s+(?:better|correctly|properly)$SEP(.+)$"""),
        rx("""^say\s+this\s+better$SEP(.+)$"""),
        rx("""^what(?:'s|\s+is)\s+wrong\s+with\s+(?:this|my)\s+sentence$SEP(.+)$"""),
        rx("""^(?:koreksi|perbaiki|benarkan|periksa|cek|check)\s+(?:kalimat|grammar|tata\s+bahasa|tulisan|bahasa\s+inggris)(?:\s+(?:ini|saya|ku|aku|inggris))*+$SEP(.+)$"""),
        rx("""^(?:apakah|apa)\s+(?:kalimat\s+)?(?:ini|itu)\s+(?:sudah\s+)?(?:benar|betul)(?:\s+(?:grammar|secara\s+grammar|dalam\s+bahasa\s+inggris))?$SEP(.+)$"""),
        rx("""^(?:benar|betul)\s+tidak(?:\s+kalimat\s+ini)?$SEP(.+)$""")
    )

    private val BETTER_EN = listOf(
        rx("""^what(?:'s|\s+is)\s+(?:(?:a|the)\s+)?(?:better|different|stronger|nicer|fancier|good|another)\s+(?:word|way\s+to\s+say)\s+(?:for|than|instead\s+of)\s+(.+)$"""),
        rx("""^(?:another|a\s+better|a\s+different|a\s+stronger|other)\s+(?:word|way\s+to\s+say)\s+(?:for|than|instead\s+of)\s+(.+)$"""),
        rx("""^(?:give\s+me\s+|show\s+me\s+)?(?:a\s+)?synonyms?\s+(?:for|of)\s+(.+)$"""),
        rx("""^(?:better|stronger|fancier)\s+word\s+(?:for|than)\s+(.+)$"""),
        rx("""^how\s+(?:else\s+)?(?:can|do)\s+i\s+say\s+(.+?)\s+(?:better|in\s+a\s+better\s+way)$""")
    )
    private val BETTER_ID = listOf(
        rx("""^(?:kata|sinonim)\s+(?:lain|yang\s+lebih\s+(?:bagus|baik|keren))\s+(?:dari|untuk|pengganti)\s+(.+)$"""),
        rx("""^(?:sinonim|padanan\s+kata)\s+(?:dari|untuk|kata)?\s*(.+)$""")
    )

    private val NEXT = rx(
        """^(?:(?:next|another|new)\s+(?:question|topic|one|prompt)|ask\s+me\s+(?:something|a\s+question|another(?:\s+one)?)|give\s+me\s+(?:a\s+)?(?:topic|question)|""" +
            """(?:pertanyaan|topik)\s+(?:lain|berikutnya|baru|selanjutnya)|tanya\s+(?:aku|saya)\s+(?:sesuatu|lagi)|lanjut|skip)$"""
    )
    private val PROGRESS = rx(
        """^(?:how\s+am\s+i\s+doing|(?:show\s+)?my\s+(?:progress|score|stats)|progress|(?:nilai|skor|progres)\s+(?:saya|aku|ku)|bagaimana\s+(?:latihan|progres)\s+(?:saya|aku))$"""
    )

    /** Sisa kalimat yang bukan isi kalimat untuk diperiksa ("check my grammar please"). */
    private val NOT_PAYLOAD = setOf("please", "now", "saya", "aku", "ku", "ini", "itu", "dong", "deh", "ya", "again", "tolong", "sekarang")

    private fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun payload(raw: String): String =
        raw.trim().trim('"', '\'', '“', '”', '‘', '’', '«', '»').trim().ifEmpty { raw.trim() }

    /** Maksud di luar sesi (atau di dalam, selain perintah khusus sesi); null bila bukan urusan mentor. */
    fun detect(input: String): CoachIntent? {
        val t = input.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';', ' ')
        if (t.isEmpty()) return null
        if (START_SIMPLE.matches(t)) return CoachIntent.Start
        for (p in CORRECT_PATTERNS) {
            val m = p.find(t) ?: continue
            val s = payload(m.groupValues[1])
            if (s.length >= 3 && s.lowercase() !in NOT_PAYLOAD) return CoachIntent.Correct(s)
        }
        for (p in BETTER_EN) {
            val m = p.find(t) ?: continue
            return CoachIntent.BetterWord(payload(m.groupValues[1]))
        }
        for (p in BETTER_ID) {
            val m = p.find(t) ?: continue
            val w = payload(m.groupValues[1])
            // Pertanyaan sinonim bahasa Indonesia tetap untuk otak AI: hanya ambil bila kata Inggrisnya ada di tabel.
            if (WordChoice.alternatives(w) != null) return CoachIntent.BetterWord(w)
        }
        if (isStop(t)) return CoachIntent.Stop
        if (MODE_ONLY.matches(t)) return CoachIntent.Start
        if (words(t) <= 12 && ENG.containsMatchIn(t) && START_WORDS.containsMatchIn(t) && !NOT_START.containsMatchIn(t)) return CoachIntent.Start
        return null
    }

    /** Di dalam sesi: tambahan kata tunggal seperti "stop", "next", "progress". */
    fun detectInSession(input: String): CoachIntent? {
        val t = input.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';', ' ')
        if (STOP_ALONE.matches(t)) return CoachIntent.Stop
        if (NEXT.matches(t)) return CoachIntent.NextTopic
        if (PROGRESS.matches(t)) return CoachIntent.Progress
        return detect(input)
    }

    private fun isStop(t: String): Boolean =
        words(t) <= 8 && STOP_VERBS.containsMatchIn(t) && STOP_TARGET.containsMatchIn(t)
}

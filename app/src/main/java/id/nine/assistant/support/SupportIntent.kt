package id.nine.assistant.support

/**
 * Mengenali permintaan masuk/keluar mode curhat dan permintaan latihan menenangkan diri. Kotlin murni.
 * Sengaja ketat: kalimat perintah biasa ("ingatkan aku...", "cari psikolog", "ceritakan dongeng") tidak boleh
 * membuka mode curhat.
 */
object SupportIntent {

    enum class Exercise { BREATHING, GROUNDING, WRITE_DOWN, SELF_COMPASSION }

    class Intent(val on: Boolean, val exercise: Exercise? = null)

    private val BLOCK = Regex(
        """\b(?:ingatkan|alarm|jadwal|catat|kalender|terjemah\w*|video|youtube|lagu|putar|cari|carikan|search|google|translate|remind\w*|calendar|schedule|music|play|nomor|alamat|biaya|harga|tarif|terdekat|apa itu|ceritakan|ceritain|dongeng|cerita (?:lucu|pendek|horor|rakyat|anak)|kisah|artikel|berita|tips|kutipan|quote|story)\b|\bcurhat (?:ke|kepada|sama|pada|dengan) (?!nine\b|kamu\b|kau\b|lu\b|anda\b|dirimu\b|mu\b)\w+"""
    )

    private val WANT = Regex("""\b(?:mau|ingin|butuh|perlu|bisa|boleh|yuk|ayo|dong|mulai|mulailah|buka|bukakan|aktifkan|mode|sesi|temani|bantu|saatnya|waktunya|let s|lets|want|need|can i|could i|start|begin)\b""")

    private val START_PATTERNS: List<Regex> = listOf(
        // "aku mau curhat", "boleh curhat?", "mode curhat", "aku butuh konseling"
        Regex("""\b(?:curhat\w*|konseling|counseling|counselling)\b"""),
        // "kamu jadi psikologku", "jadilah teman curhatku"
        Regex("""\b(?:jadi|jadilah|menjadi|bertindak sebagai|sebagai) (?:seorang )?(?:psikolog|konselor|terapis|teman curhat|teman bicara|pendengar|sahabat)\w*"""),
        // "temani aku", "dengarkan aku", "dengerin ceritaku"
        Regex("""^(?:nine )?(?:temani|temenin|menemani|dengarkan|dengerin|dengarin|dengarkanlah)\w* (?:aku|ceritaku|curhatanku|keluhanku)(?: (?:dong|ya|sebentar|nine|deh|sekarang|please))*$"""),
        // "aku butuh teman bicara", "aku ingin didengarkan"
        Regex("""\baku (?:butuh|perlu|ingin|mau) (?:teman (?:bicara|cerita|curhat|ngobrol)|didengarkan|didengar|seseorang (?:untuk )?(?:bicara|mendengar|mendengarkan)|bicara dengan seseorang|ditemani|pendengar|tempat (?:curhat|cerita))\b"""),
        // "aku mau cerita" (tanpa objek lain)
        Regex("""^(?:nine )?aku (?:mau|ingin) (?:cerita|bercerita)(?: (?:dong|ya|nih|sesuatu|masalah|tentang (?:perasaan|masalah)\w*|soal (?:perasaan|masalah)\w*|nine|sama kamu|ke kamu))*$"""),
        // Inggris
        Regex("""\bi (?:need|want|would like) to (?:vent|open up|be heard)\b|\bi need to talk\b|\bsomeone to (?:talk to|listen)\b|\b(?:be my|act as my|become my|as my) (?:counselor|counsellor|therapist|psychologist|listener)\b|^listen to me\b|\b(?:let me|can i|could i) vent\b""")
    )

    private val EXPLICIT_STOP = Regex(
        """\b(?:(?:stop|selesai|akhiri|akhir|keluar dari|tutup|end|exit) (?:mode |sesi |session )?(?:curhat|konseling|counseling|counselling|venting)|(?:curhat|konseling|sesi curhat|sesi konseling) (?:selesai|cukup|sudah)|keluar dari mode curhat)\b"""
    )

    /** Kalimat yang berarti "sudah cukup" bila diucapkan DI DALAM sesi curhat. */
    private val SESSION_STOP = Regex(
        """^(?:(?:aku |saya )?(?:mau|ingin) )?(?:selesai|sudah selesai|udahan|udah dulu|sudah dulu|cukup|sudah cukup|cukup dulu|stop|berhenti|keluar|end|done|bye|dadah|sampai jumpa|goodbye|that is all|i am done|that is enough|enough for now|thats all)(?: (?:dulu|ya|deh|saja|nine|makasih|terima kasih|thanks|curhat|konseling|sesi))*$|^(?:terima kasih|makasih|thanks|thank you)(?: banyak| ya| nine)* (?:sudah|udah|for) (?:mendengarkan|dengerin|mendengar|menemani|listening|being there|your help)\b|^(?:terima kasih|makasih|thanks|thank you)(?: banyak| ya| nine)* (?:sudah|udah|cukup) (?:dulu|cukup|selesai)$"""
    )

    private val EXERCISE_PATTERNS: List<Pair<Exercise, Regex>> = listOf(
        Exercise.BREATHING to Regex("""\b(?:latihan (?:napas|nafas|pernapasan|pernafasan)|(?:ajari|ajarin|bimbing|temani) (?:aku )?(?:napas|nafas|bernapas|bernafas)|napas dalam|nafas dalam|tarik napas bersama|breathing exercise|breathe with me|help me breathe|deep breath\w*)\b"""),
        Exercise.GROUNDING to Regex("""\b(?:grounding|5 4 3 2 1|54321|teknik (?:5 4 3 2 1|54321|grounding)|grounding exercise)\b"""),
        Exercise.WRITE_DOWN to Regex("""\b(?:latihan menulis|tulis perasaan|menulis perasaan|journaling|jurnal perasaan|write (?:it|my feelings) down)\b"""),
        Exercise.SELF_COMPASSION to Regex("""\b(?:self compassion|belas kasih diri|latihan (?:menyayangi|mengasihi) diri)\b""")
    )

    private val CALM = Regex(
        """\b(?:tenangkan (?:aku|pikiranku|diriku)|bantu aku (?:tenang|rileks|relaks|menenangkan diri)|calm me down|help me calm down|help me relax)\b"""
    )

    private val TO_OTHER = Regex("""\bcurhat\w* (?:ke|kepada|sama|pada|dengan) (?!nine\b|kamu\b|kau\b|lu\b|anda\b|dirimu\b|mu\b)\w+""")

    /**
     * "curhat ke ibu" / "curhat sama teman": curhat kepada orang lain, bukan kepada Nine. Dipakai juga oleh parser
     * kalimat berantakan agar kata "ke" yang dibuang tidak mengubah artinya menjadi ajakan curhat kepada Nine.
     */
    fun targetsOtherPerson(raw: String): Boolean = TO_OTHER.containsMatchIn(SupportText.norm(raw))

    /** Parser untuk percakapan biasa (di luar sesi): hanya perintah yang jelas. */
    fun parse(raw: String): Intent? {
        val t = SupportText.norm(raw)
        if (t.isEmpty()) return null
        if (EXPLICIT_STOP.containsMatchIn(t)) return Intent(false)
        // latihan menenangkan diri tidak perlu dikunci oleh kata "cari/video/lagu" dsb., tapi tetap tidak boleh
        // bercampur dengan perintah lain.
        for ((ex, re) in EXERCISE_PATTERNS) if (re.containsMatchIn(t) && !BLOCK.containsMatchIn(t)) return Intent(true, ex)
        if (CALM.containsMatchIn(t) && !BLOCK.containsMatchIn(t)) return Intent(true, Exercise.BREATHING)
        if (BLOCK.containsMatchIn(t)) return null
        // "curhat"/"konseling" saja belum cukup; harus ada kata ajakan/keinginan ("aku mau curhat").
        if (START_PATTERNS[0].containsMatchIn(t) && (WANT.containsMatchIn(t) || SupportText.words(t) <= 2)) return Intent(true)
        for (i in 1 until START_PATTERNS.size) if (START_PATTERNS[i].containsMatchIn(t)) return Intent(true)
        return null
    }

    /** Di dalam sesi: apakah ucapan ini berarti mengakhiri sesi? */
    fun isStop(raw: String): Boolean {
        val t = SupportText.norm(raw)
        if (t.isEmpty()) return false
        return EXPLICIT_STOP.containsMatchIn(t) || (SupportText.words(t) <= 9 && SESSION_STOP.containsMatchIn(t))
    }

    /** Di dalam sesi: permintaan latihan yang diucapkan bebas ("latihan napas", "tenangkan aku"). */
    fun exerciseRequest(raw: String): Exercise? {
        val t = SupportText.norm(raw)
        for ((ex, re) in EXERCISE_PATTERNS) if (re.containsMatchIn(t)) return ex
        if (CALM.containsMatchIn(t)) return Exercise.BREATHING
        return null
    }
}

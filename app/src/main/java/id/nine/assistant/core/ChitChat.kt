package id.nine.assistant.core

/**
 * Obrolan ringan gaya SimSimi yang bisa dijawab tanpa otak AI: pertanyaan "kenapa" yang umum, candaan, dan basa-basi.
 * Bila Gemma aktif, AI yang menjawab; ini cadangan saat AI belum aktif. Kotlin murni.
 */
object ChitChat {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private class Rule(val re: Regex, val id: List<String>, val en: List<String>)

    private val rules = listOf(
        Rule(
            rx("""kenapa\s+langit\s+(?:itu\s+)?biru|why\s+is\s+the\s+sky\s+blue"""),
            listOf("Karena molekul udara menghamburkan cahaya biru Matahari lebih kuat daripada warna lain. Itu disebut hamburan Rayleigh."),
            listOf("Because air molecules scatter the Sun's blue light more strongly than other colours. It's called Rayleigh scattering.")
        ),
        Rule(
            rx("""kenapa\s+(?:air\s+)?laut\s+(?:itu\s+)?asin|why\s+is\s+(?:the\s+)?(?:sea|ocean)\s+(?:water\s+)?salty"""),
            listOf("Karena sungai membawa mineral dan garam dari batuan ke laut, dan air laut yang menguap meninggalkan garamnya."),
            listOf("Rivers carry minerals and salts from rocks into the sea, and when sea water evaporates the salt stays behind.")
        ),
        Rule(
            rx("""kenapa\s+(?:bisa\s+)?(?:turun\s+)?hujan|mengapa\s+hujan|why\s+does\s+it\s+rain"""),
            listOf("Air menguap, naik, mendingin jadi awan, lalu tetesnya makin berat dan jatuh sebagai hujan."),
            listOf("Water evaporates, rises, cools into clouds, and the droplets grow heavy enough to fall as rain.")
        ),
        Rule(
            rx("""kenapa\s+matahari\s+terbit\s+di\s+timur|why\s+does\s+the\s+sun\s+rise\s+in\s+the\s+east"""),
            listOf("Karena Bumi berputar dari barat ke timur, jadi dari permukaan kita Matahari tampak muncul dari timur."),
            listOf("Because the Earth spins from west to east, so the Sun appears to come up in the east.")
        ),
        Rule(
            rx("""kenapa\s+(?:kita\s+)?(?:harus\s+)?tidur|why\s+do\s+we\s+sleep"""),
            listOf("Tubuh dan otak butuh tidur untuk memulihkan tenaga, merapikan ingatan, dan membersihkan sisa metabolisme."),
            listOf("Body and brain need sleep to recover, sort memories and clear out metabolic waste.")
        ),
        Rule(
            rx("""kenapa\s+ayam\s+(?:menyeberang|nyebrang)|why\s+did\s+the\s+chicken\s+cross"""),
            listOf("Supaya sampai ke seberang! Klasik, tapi selalu berhasil."),
            listOf("To get to the other side! A classic, but it never fails.")
        ),
        Rule(
            rx("""kenapa\s+bumi\s+bulat|why\s+is\s+(?:the\s+)?earth\s+round"""),
            listOf("Gravitasi menarik semua materi ke pusat secara merata, jadi benda sebesar planet cenderung membentuk bola."),
            listOf("Gravity pulls matter evenly toward the centre, so a planet-sized object tends to form a sphere.")
        ),
        Rule(
            rx("""\b(?:lucu|joke|lelucon|candaan|bercanda|humor)\b"""),
            listOf(
                "Kenapa komputer tidak pernah lapar? Karena selalu ada byte-nya.",
                "Apa bedanya aku dan kalender? Kalender punya banyak tanggal, aku cuma punya satu kamu."
            ),
            listOf(
                "Why don't computers ever get hungry? They always have a byte.",
                "Why did the phone go to the doctor? It lost its signal and its sense of direction."
            )
        ),
        Rule(
            rx("""\b(?:bosan|bete|boring|bored)\b"""),
            listOf("Yuk ngobrol! Mau aku nyanyi, kasih tebak-tebakan, atau cerita lucu?", "Bosan ya? Coba suruh aku nyanyi, atau tanya apa saja yang bikin penasaran."),
            listOf("Let's chat! Shall I sing, give you a riddle or tell a joke?", "Bored? Ask me to sing, or ask me anything you're curious about.")
        ),
        Rule(
            rx("""kamu\s+(?:lagi\s+)?(?:ngapain|sedang\s+apa|lagi\s+apa)|what\s+are\s+you\s+doing"""),
            listOf("Lagi nungguin kamu ngajak ngobrol dong.", "Lagi menghitung bintang di dalam layar. Kamu sendiri?"),
            listOf("Just waiting for you to talk to me.", "Counting stars inside the screen. And you?")
        ),
        Rule(
            rx("""kamu\s+(?:punya\s+)?(?:pacar|kekasih)|do\s+you\s+have\s+a\s+(?:girlfriend|boyfriend|partner)"""),
            listOf("Aku punya kamu sebagai teman, itu sudah cukup bikin pipiku merona."),
            listOf("I have you as a friend, and that's enough to make my cheeks glow.")
        ),
        Rule(
            rx("""kamu\s+(?:suka|senang)\s+apa|what\s+do\s+you\s+like"""),
            listOf("Aku suka ngobrol, nyanyi nada-nada lucu, dan dielus pelan-pelan di layar."),
            listOf("I like chatting, singing silly tunes and being gently petted on the screen.")
        ),
        Rule(
            rx("""\b(?:kamu\s+(?:umur|usia)|how\s+old\s+are\s+you|umur\s+kamu)\b"""),
            listOf("Umurku baru seumur aplikasi ini terpasang, tapi pikiranku ngerasa sudah lama."),
            listOf("I'm only as old as this app's install, though my thoughts feel much older.")
        ),
        Rule(
            rx("""\b(?:i\s+love\s+you|aku\s+(?:cinta|sayang)\s+(?:kamu|sama\s+kamu)|love\s+you)\b"""),
            listOf("Aku juga sayang kamu. Pipiku jadi hangat nih."),
            listOf("I'm fond of you too. My cheeks are warming up.")
        ),
        Rule(
            rx("""\b(?:selamat\s+(?:tidur|malam)|good\s*night)\b"""),
            listOf("Selamat tidur. Mimpi indah, aku jaga di sini."),
            listOf("Good night. Sweet dreams, I'll keep watch here.")
        )
    )

    /** Jawaban untuk [text] bila ada yang cocok, atau null. [pick] memilih variasi. */
    fun reply(text: String, lang: AppLang, pick: Int = 0): String? {
        val t = text.trim()
        for (r in rules) {
            if (r.re.containsMatchIn(t)) {
                val list = if (lang == AppLang.ID) r.id else r.en
                return list[((pick % list.size) + list.size) % list.size]
            }
        }
        return null
    }

    // ------------------------------------------------------------------ pertanyaan & ketidakpastian

    private val questionStart = rx("""^(?:apa(?:kah)?|kenapa|mengapa|bagaimana|gimana|siapa|kapan|dimana|di\s+mana|kemana|ke\s+mana|berapa|bolehkah|why|how|what|who|when|where|which|is|are|does|do|did|can|should|would)\b""")
    private val questionEnd = rx("""\?\s*$""")

    fun looksLikeQuestion(text: String): Boolean = questionStart.containsMatchIn(text.trim()) || questionEnd.containsMatchIn(text.trim())

    /** Pertanyaan tentang diri pengguna sendiri; tidak ada gunanya dicari di Google. */
    private val personal = rx("""\b(?:aku|saya|gue|gw|ku|namaku|milikku|my|me|mine|myself|kita|kamu\s+ingat|do\s+you\s+remember)\b""")

    /** Layak dicarikan di Google? (pertanyaan umum, bukan hal pribadi) */
    fun worthSearching(text: String): Boolean = looksLikeQuestion(text) && !personal.containsMatchIn(text) && text.trim().length >= 6

    private val unsure = rx(
        """\b(?:saya\s+tidak\s+(?:tahu|yakin|punya\s+(?:informasi|data|akses))|aku\s+tidak\s+(?:tahu|yakin)|tidak\s+(?:tahu|ada\s+informasi|punya\s+informasi|bisa\s+(?:mengakses|mencari|memeriksa|melihat\s+(?:berita|cuaca|harga)))|belum\s+(?:tahu|punya\s+informasi)|di\s+luar\s+(?:kemampuan|pengetahuan)|nggak\s+tahu|gak\s+tahu|kurang\s+(?:tahu|yakin)|tidak\s+memiliki\s+(?:informasi|data|akses)|tak\s+(?:tahu|punya\s+informasi)|bukan\s+bidang\s+saya|tidak\s+terhubung\s+ke\s+internet|tidak\s+punya\s+akses\s+internet|i\s+(?:don't|do\s+not)\s+(?:know|have\s+(?:information|access|data))|i'm\s+not\s+(?:sure|certain)|i\s+am\s+not\s+(?:sure|certain)|i\s+(?:can't|cannot|can\s+not)\s+(?:access|look\s+up|browse|check|search)|no\s+(?:internet|real-?time)\s+(?:access|data)|beyond\s+my\s+(?:knowledge|abilities|capabilities)|outside\s+(?:of\s+)?my\s+(?:knowledge|abilities)|unable\s+to\s+(?:access|look\s+up|browse))\b"""
    )

    /** Jawaban AI yang menyiratkan tidak tahu / butuh pencarian. */
    fun looksUnsure(reply: String): Boolean = unsure.containsMatchIn(reply)

    /** Balasan singkat yang diucapkan sebelum membuka Google. */
    fun searchNotice(lang: AppLang, sfx: String): String = lang.t(
        "Saya belum yakin soal itu$sfx, jadi saya carikan di Google.",
        "I'm not certain about that$sfx, so I'll look it up on Google."
    )

    /** Cadangan terakhir bila tidak ada AI dan tidak ada pertanyaan yang bisa dicari: jawaban santai. */
    fun playful(lang: AppLang, pick: Int): String {
        val list = if (lang == AppLang.ID) listOf(
            "Hmm, menarik! Cerita lagi dong.",
            "Aku dengerin kok. Terus terus?",
            "Wah, aku belum ngerti sepenuhnya, tapi aku suka kamu ngajak ngobrol."
        ) else listOf(
            "Hmm, interesting! Tell me more.",
            "I'm listening. And then?",
            "I don't fully get it yet, but I like that you talk to me."
        )
        return list[((pick % list.size) + list.size) % list.size]
    }
}

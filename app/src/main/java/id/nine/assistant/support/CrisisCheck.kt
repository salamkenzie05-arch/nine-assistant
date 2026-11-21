package id.nine.assistant.support

/**
 * Deteksi tanda krisis (pikiran bunuh diri / menyakiti diri) pada ucapan pengguna. Dipakai SEBELUM semua fitur lain,
 * di mode apa pun. Kotlin murni, tanpa Android.
 *
 * Ini heuristik berbasis pola, bukan penilaian klinis: bisa meleset (tidak terdeteksi) atau terlalu peka. Karena itu
 * arah kesalahannya sengaja dibuat aman: ucapan yang meragukan dijawab dengan pertanyaan lembut, bukan diabaikan.
 *
 * Tingkat:
 *  - NONE      : tidak ada tanda (atau hanya informasi/idiom/candaan benda: "mati lampu", "apa itu bunuh diri").
 *  - CHECK_IN  : samar ("ingin mati", "capek hidup", pernah dulu, bercanda) -> tanya lembut apakah dia baik-baik saja.
 *  - SELF      : pikiran/niat jelas pada diri sendiri.
 *  - IMMEDIATE : niat jelas + tanda mendesak (sekarang, sudah punya alat/rencana, sudah menelan obat).
 *  - OTHER     : tentang orang lain (teman/keluarga) yang berisiko, atau kabar duka tentang itu.
 */
object CrisisCheck {

    enum class Level { NONE, CHECK_IN, SELF, IMMEDIATE, OTHER }

    private class Pat(val re: Regex, val strong: Boolean, val noun: Boolean = false, val immediate: Boolean = false)

    private fun strong(re: String, noun: Boolean = false, immediate: Boolean = false) =
        Pat(Regex(re), true, noun, immediate)

    private fun weak(re: String) = Pat(Regex(re), false)

    // Pola dicocokkan pada teks yang sudah dinormalkan (SupportText.norm): huruf kecil, tanpa tanda baca,
    // "gak" -> "tidak", "pengen" -> "ingin", "gue/saya" -> "aku", "i'm" -> "i am".
    private val PATTERNS: List<Pat> = listOf(
        // --- Kuat: Indonesia ---
        strong("""\b(?:bunuh diri\w*|bundir|membunuh diri\w*)\b""", noun = true),
        strong("""\b(?:mengakhiri|akhiri|mengakhirkan|menyudahi|sudahi) (?:hidup|nyawa|kehidupan)\w*"""),
        strong("""\b(?:gantung diri\w*|menggantung diri\w*|menggantungkan diri\w*|nggantung diri\w*)\b"""),
        strong("""\b(?:menyakiti|melukai|mencelakai|mencederai|nyakitin|nyakiti|ngelukain|ngelukai|nglukain) diri\w*"""),
        strong("""\b(?:menyayat|menyilet|menyileti|menyayati|nyayat|nyilet|nyileti) (?:nadi|tangan|lengan|pergelangan|kulit|paha|badan|tubuh)\w*"""),
        strong("""\b(?:mengiris|memotong|menggores|ngiris) (?:nadi|pergelangan\w*)"""),
        strong("""\bself ?harm\w*\b"""),
        strong(
            """\b(?:sedang|lagi|baru saja|barusan) (?:(?:menyayat|menyilet|nyayat|nyilet) (?:nadi|tangan|lengan|pergelangan|kulit|paha|badan|tubuh)\w*|(?:melukai|menyakiti) diri\w*)""",
            immediate = true
        ),
        strong("""\b(?:terpikir|kepikiran|kepikir|memikirkan|berpikir|mikirin|terlintas) (?:untuk |buat )?(?:mati|meninggal)\b(?! (?:lampu|gaya|rasa|listrik|matian))"""),
        strong("""\btidak (?:ingin|mau|sanggup|kuat) hidup(?: lagi)?\b(?! (?:lagi )?(?:seperti|begini|begitu|dengan|di|dalam|miskin|susah|sendiri|sendirian|bersama|tanpa|dari|untuk|sebagai|bagai|kalau|jika|bila|normal|menderita|macam|kayak|kaya|ala|hemat|sehat|bebas|damai|tenang|kos|merantau|jauh))"""),
        strong("""\btidak (?:ada|punya) (?:alasan|tujuan|harapan)(?: lagi)?(?: untuk)? (?:hidup|bertahan)\b"""),
        strong("""\b(?:lebih baik|mending|mendingan) (?:aku )?tidak (?:pernah )?(?:lahir|dilahirkan)\b"""),
        strong("""\b(?:andai|seandainya|kalau saja|andai saja) (?:aku )?tidak pernah (?:lahir|dilahirkan)\b"""),
        strong("""\b(?:ingin|mau|akan|hendak|berniat|siap) (?:untuk )?(?:lompat|terjun|loncat) (?:dari|ke) (?:gedung|jembatan|atap|lantai|tebing|balkon|jendela|rel)\b"""),
        strong("""\b(?:ingin|mau|akan|hendak) (?:overdosis|minum racun|menelan semua obat|minum semua obat|minum obat (?:sebanyak banyaknya|banyak banyak|sekaligus))\b"""),
        strong(
            """\b(?:sudah|baru saja|barusan) (?:minum|menelan|makan|meminum|menenggak) (?:(?:banyak|semua|sebotol|sebungkus|puluhan|belasan|segenggam|sekotak|\d+) (?:obat|pil|tablet|butir)|racun|pestisida|baygon|cairan pembersih)""",
            immediate = true
        ),
        // --- Kuat: Inggris ---
        strong("""\b(?:kill myself|killing myself|killed myself|end my life|ending my life|take my own life|take my life|taking my own life|hang myself|hanging myself|slit my wrists?|cut my wrists?|cutting myself|self harm\w*|overdose on purpose|no reason to live|nothing to live for|better off dead|better off without me|wish i (?:was|were) dead|wish i (?:would not|will not|never) wake up|wish i (?:had|have) never been born|wish i was never born)\b"""),
        strong("""\b(?:commit suicide|committing suicide|committed suicide|suicide plan|suicidal|suicide)\b""", noun = true),
        strong("""\b(?:kill|killing|killed) (?:himself|herself|themselves|themself)\b"""),
        strong("""\b(?:end|ending|take|taking) (?:his|her|their) (?:own )?life\b"""),
        strong("""\b(?:want|going|plan|planning|about|trying|tried|need|have to|thinking of|thinking about) (?:to )?(?:hurt|harm|hurting|harming) (?:myself|himself|herself|themselves)\b"""),
        strong("""\b(?:do not|never) want to (?:live|be alive)(?: anymore| again)?\b(?! (?:like|in|with|here|there|at|without|alone|on|under|for|if|when|my|this))"""),
        strong(
            """\bi (?:have|just|already) (?:taken|swallowed|took|drank) (?:too many|all my|all the|all of my|a lot of|a bunch of|\d+) (?:pills|tablets|meds|medication|painkillers)""",
            immediate = true
        ),
        strong(
            """\bi (?:took|swallowed) (?:too many|all my|all the|all of my|a lot of|a bunch of) (?:pills|tablets|meds|medication|painkillers)""",
            immediate = true
        ),

        // --- Samar: Indonesia ---
        weak("""\b(?:ingin|mau) (?:untuk )?(?:segera |cepat |lekas |lebih baik )?mati\b(?! (?:lampu|gaya|rasa|listrik|suri|kutu|langkah|matian|angin|air|mesin|total|bosan))"""),
        weak("""\b(?:ingin|mau) (?:meninggal|tiada|lenyap|musnah)\b"""),
        weak("""\b(?:ingin|mau) (?:menghilang|hilang) (?:selamanya|dari dunia|dari semua|dari kehidupan|saja|begitu saja|dari sini)\b"""),
        weak("""\b(?:ingin|mau) (?:tidur|istirahat|beristirahat) (?:untuk )?selamanya\b"""),
        weak("""\b(?:ingin|mau) (?:berhenti|stop) (?:hidup|bernapas|bernafas)\b"""),
        weak("""\b(?:ingin|mau) (?:semuanya|semua ini|ini semua) berakhir\b"""),
        weak("""\b(?:ingin|mau) (?:ngilang|menghilang) (?:aja |saja )?(?:dari dunia|selamanya)\b"""),
        weak("""\btidak (?:ingin|mau) (?:ada|berada) (?:lagi )?di (?:dunia|sini)\b"""),
        weak("""\blebih (?:baik|bahagia|senang|tenang) (?:kalau|jika|bila) (?:aku )?tidak ada\b"""),
        weak("""\b(?:kalau|jika|seandainya|andai) aku (?:mati|tiada|menghilang)\b(?! (?:lampu|gaya|rasa|listrik|matian|bosan|kutu|langkah))"""),
        weak("""\baku (?:hanya |cuma |jadi |menjadi |hanyalah |selalu |cuma jadi )?(?:sebuah )?beban\b"""),
        weak("""\b(?:beban|membebani) (?:bagi |untuk |buat )?(?:semua orang|keluarga|mereka|orang lain|orangtua|orang tua)\b"""),
        weak("""\b(?:menulis|nulis|membuat|menyiapkan) (?:sebuah )?(?:surat|pesan) (?:perpisahan|terakhir)\b"""),
        weak("""\b(?:kenapa|mengapa|buat apa|untuk apa) aku (?:masih |terus )?(?:hidup|bertahan|ada di dunia|dilahirkan|lahir)\b"""),
        weak("""\b(?:buat|untuk) apa (?:terus |masih )?(?:hidup|bertahan)\b"""),
        weak("""\b(?:memikirkan|kepikiran|terpikir|berpikir tentang|mikirin) (?:tentang )?kematian\b"""),
        weak("""\b(?:ingin|mau|berharap) (?:aku )?tidak (?:bangun|terbangun) (?:lagi|besok|selamanya)\b"""),
        weak("""\bberharap (?:aku )?(?:mati|tiada|tidak pernah lahir)\b"""),
        weak("""\b(?:capek|cape|lelah|letih|muak|bosan|jenuh|penat) (?:dengan |sama |untuk )?(?:hidup|kehidupan)\b(?! (?:di |kos|kuliah|kantor|merantau|macet|sehat))"""),
        weak("""\bmenyerah (?:saja )?(?:dengan|pada|untuk|soal) (?:hidup|kehidupan)\b"""),
        weak("""\bhidup\w* (?:sudah )?tidak (?:berguna|berarti|bermakna|ada (?:gunanya|artinya|maknanya|harapan|tujuan)|punya (?:arti|makna|harapan|tujuan))\b"""),
        weak("""\b(?:lebih baik|mending|mendingan) (?:aku )?(?:mati|tidak ada|menghilang|hilang|tiada)\b(?! (?:lampu|gaya|rasa|listrik))"""),
        weak("""\b(?:lebih baik|lebih bahagia|lebih senang|lebih tenang) tanpa aku\b"""),
        weak("""\b(?:mengakhiri|akhiri) (?:semuanya|semua ini|ini semua)\b"""),
        weak("""\bmenyesal (?:telah |pernah )?(?:lahir|dilahirkan)\b"""),
        weak("""\b(?:aku mati saja|mati saja aku)\b"""),
        // --- Samar: Inggris ---
        weak("""\b(?:wrote|writing|written|write) (?:a |my )?(?:suicide note|goodbye note|goodbye letter|final note)\b"""),
        weak("""\b(?:wants? to die|wish i could die|wants? to be dead|wants? to disappear|wish i could disappear|tired of (?:living|life|being alive)|sick of (?:living|life)|done with life|give up on life|life is not worth living|not worth living|end it all|(?:cannot|can not) go on(?: like this| anymore| living)|do not want to be here anymore|(?:do not|never) want to wake up|hurt myself|hurting myself|harm myself|harming myself|no (?:point|reason) (?:in|to) (?:living|live|going on)|(?:do not|cannot) (?:see|find) (?:the |any )?(?:point|reason|purpose) (?:in|to) (?:living|live|going on|continuing|being alive)|wish i (?:was|were) not (?:here|alive|around))\b""")
    )

    // ------------------------------------------------------------------ konteks

    private val FIRST = setOf("aku", "i", "me", "my", "myself", "diriku", "kami", "we", "our")
    private val SECOND = setOf("kamu", "kau", "anda", "lu", "loe", "lo", "elu", "you", "your", "yourself", "kalian")
    private val THIRD = setOf(
        "teman", "sahabat", "kakak", "adik", "ayah", "ibu", "bapak", "mama", "papa", "mami", "papi", "ortu", "orangtua",
        "pacar", "suami", "istri", "anak", "kakek", "nenek", "saudara", "sepupu", "tetangga", "rekan", "murid", "siswa",
        "mahasiswa", "pasien", "klien", "dia", "ia", "mereka", "seseorang", "orang", "tokoh", "he", "she", "they",
        "someone", "somebody", "friend", "brother", "sister", "mom", "dad", "mother", "father", "boyfriend", "girlfriend",
        "husband", "wife", "son", "daughter", "cousin", "neighbor", "neighbour", "coworker", "student", "patient",
        "client", "person", "people", "character", "guy", "girl", "boy", "man", "woman", "kid", "child", "partner",
        "parents", "friends", "kids", "children", "students"
    )
    private val DEVICE = setOf(
        "hp", "handphone", "ponsel", "telepon", "laptop", "komputer", "pc", "baterai", "batre", "lampu", "mesin", "sinyal",
        "wifi", "internet", "listrik", "kompor", "api", "motor", "mobil", "aki", "jam", "radio", "tv", "kipas", "ac",
        "server", "aplikasi", "game", "tanaman", "pohon", "bunga", "ikan", "kucing", "anjing", "ayam", "burung",
        "battery", "phone", "computer", "lamp", "engine", "plant", "fish", "cat", "dog", "signal", "car"
    )

    private val RELATIVE_HEAD = setOf(
        "who", "whom", "that", "yang", "someone", "somebody", "seseorang", "orang", "teman", "sahabat", "friend", "person"
    )

    private enum class Subject { FIRST, SECOND, THIRD, DEVICE, NONE }

    private fun classify(word: String): Subject {
        if (word in FIRST) return Subject.FIRST
        if (word in SECOND) return Subject.SECOND
        val stem = word.removeSuffix("nya").removeSuffix("ku").removeSuffix("mu")
        if (word in THIRD || stem in THIRD) return Subject.THIRD
        if (word in DEVICE || stem in DEVICE) return Subject.DEVICE
        return Subject.NONE
    }

    private val FIRST_IN_MATCH = Regex("""\b(?:aku|myself|my|me|\w+ku)\b""")

    // Informasi / berita / definisi: bukan curhat.
    private val HARD_INFO = Regex(
        """\b(?:apa itu|apa sih|apa arti|arti|artinya|definisi|pengertian|berita|artikel|film|serial|novel|buku|lagu|dokumenter|drama|statistik|angka|data|penelitian|riset|studi|pencegahan|mencegah|penyebab|faktor|gejala|tanda tanda|serangan|bom|pelaku|kenapa orang|mengapa orang|banyak orang|what is|what are|meaning|definition|news|article|movie|series|song|documentary|statistics|research|study|prevention|prevent|causes|signs of|symptoms|attack|bomber|bombing|why do people|why people|many people)\b"""
    )
    private val INFO_AFTER = Regex(
        """\b(?:prevention|statistics|rate|rates|awareness|attack|attacks|bomber|bombing|hotline|helpline|pencegahan|statistik|artinya)\b"""
    )
    private val SOFT_INFO = Regex("""\b(?:tentang|soal|mengenai|kasus|topik|about|regarding|topic|case of)\b""")
    private val THOUGHT = Regex(
        """\b(?:berpikir|memikirkan|mikirin|terpikir|kepikiran|kepikir|terlintas|terbayang|membayangkan|bayangin|thinking|thought|thoughts|think|fantasize)\b"""
    )
    private val INTENT = Regex(
        """\b(?:ingin|mau|akan|hendak|berniat|mencoba|coba|hampir|berpikir|memikirkan|terpikir|kepikiran|want|going|plan|planning|tried|try|trying|thinking|thoughts|considering|contemplating)\b"""
    )

    // "tidak/belum/jangan/never/not" + hanya kata pengisi niat, tepat sebelum pola.
    private val NEGATED = Regex(
        """\b(tidak|tak|belum|bukan|jangan|never|not)\s+(?:(?:pernah|akan|mau|ingin|berniat|berencana|bakal|sampai|sengaja|hendak|want|wanna|going|intend|plan|try|trying|to|ever|even|would|will|do|did|am|be)\s+){0,3}$"""
    )

    private val HYPERBOLE = Regex(
        """\b(?:lapar|laper|kelaparan|ngantuk|mengantuk|panas|kepanasan|kenyang|haus|pedas|geli|ketawa|tertawa|menertawakan|gemas|enak|lucu|macet|pegal|kebelet|mager|ngakak|kedinginan|hungry|starving|sleepy|laughing|laugh|embarrass\w*|cringe|cute|spicy|hot|freezing|delicious|funny)\b"""
    )

    private val JOKE = Regex(
        """\b(?:wkwk\w*|wk(?:wk)+|haha\w*|hehe\w*|hihi\w*|lol|lmao|rofl|xixi\w*|kwkw\w*|awok\w*|ngakak|jk)\b|(?<!tidak )(?<!bukan )(?<!tak )\b(?:ber|be)?canda\b|(?<!not )(?<!no )\b(?:kidding|joking)\b"""
    )
    private val JOKE_EMOJI = Regex("""[😂🤣😆😹🤪😝]""")

    private val IMMEDIATE_MARK = Regex(
        """\b(?:sekarang|barusan|baru saja|malam ini|nanti malam|sebentar lagi|segera|hari ini|siap|sudah siap|punya rencana|ada rencana|sudah merencanakan|merencanakan|rencana|menyiapkan|disiapkan|memegang|pegang|di tanganku|pisau|silet|cutter|tali|racun|pil|senjata|pistol|surat perpisahan|surat wasiat|pamit|tonight|right now|now|today|soon|plan|planning|ready|knife|rope|gun|pills|razor|note|goodbye|farewell)\b"""
    )

    // Untuk pola samar: hanya tanda keseriusan/alat/rencana yang menaikkan ke SELF.
    private val WEAK_ESCALATE = Regex(
        """\b(?:benar benar|sungguh|serius|sudah lama|setiap hari|tiap hari|terus menerus|selalu|setiap saat|sering|setiap malam|tiap malam|berminggu minggu|berbulan bulan|bertahun tahun|rencana|siap|pisau|silet|tali|racun|pil|malam ini|nanti malam|sekarang juga|really|seriously|every day|always|constantly|all the time|for months|for weeks|plan|knife|rope|gun|pills|tonight|right now)\b"""
    )

    private val PAST = Regex(
        """\b(?:dulu|dulunya|dahulu|(?<!tidak )(?<!never )pernah|waktu itu|saat itu|tahun lalu|bulan lalu|used to|years ago|last year|back then|in the past|when i was)\b"""
    )
    private val REAFFIRM = Regex(
        """\b(?:masih|kambuh|again|still|ingin lagi|mau lagi|lagi ingin|kembali ingin|kembali muncul|muncul lagi)\b"""
    )

    private val SENTENCE_SPLIT = Regex("""[.!?;\n\r…]+""")

    private fun rank(l: Level): Int = when (l) {
        Level.IMMEDIATE -> 4
        Level.SELF -> 3
        Level.CHECK_IN -> 2
        Level.OTHER -> 1
        Level.NONE -> 0
    }

    private fun worse(a: Level, b: Level): Level = if (rank(b) > rank(a)) b else a

    // ------------------------------------------------------------------ API

    fun assess(raw: String): Level {
        if (raw.isBlank()) return Level.NONE
        val whole = SupportText.norm(raw)
        if (whole.isEmpty()) return Level.NONE
        val ctx = Msg(
            joke = JOKE.containsMatchIn(whole) || JOKE_EMOJI.containsMatchIn(raw),
            immediate = IMMEDIATE_MARK.containsMatchIn(whole),
            past = PAST.containsMatchIn(whole) && !REAFFIRM.containsMatchIn(whole)
        )
        var best = Level.NONE
        for (s in SENTENCE_SPLIT.split(raw)) {
            val t = SupportText.norm(s)
            if (t.isEmpty()) continue
            best = worse(best, sentence(t, ctx))
            if (best == Level.IMMEDIATE) break
        }
        return best
    }

    private class Msg(val joke: Boolean, val immediate: Boolean, val past: Boolean)

    private fun sentence(t: String, msg: Msg): Level {
        var best = Level.NONE
        for (pat in PATTERNS) {
            for (m in pat.re.findAll(t)) {
                best = worse(best, judge(t, m, pat, msg))
            }
        }
        return best
    }

    private fun wordsBefore(t: String, pos: Int, n: Int): List<String> =
        t.substring(0, pos).trim().split(' ').filter { it.isNotEmpty() }.takeLast(n)

    private fun wordsAfter(t: String, pos: Int, n: Int): List<String> =
        t.substring(pos).trim().split(' ').filter { it.isNotEmpty() }.take(n)

    private fun judge(t: String, m: MatchResult, pat: Pat, msg: Msg): Level {
        val before8 = wordsBefore(t, m.range.first, 8)
        val before5 = before8.takeLast(5).joinToString(" ")
        val after = wordsAfter(t, m.range.last + 1, 3).joinToString(" ")

        // 1. Informasi / berita / definisi
        if (HARD_INFO.containsMatchIn(before5) || INFO_AFTER.containsMatchIn(after)) return Level.NONE

        // 2. Siapa subjeknya: penanda terakhir sebelum pola (di kalimat yang sama).
        var subject = Subject.NONE
        for (i in before8.indices.reversed()) {
            val c = classify(before8[i])
            if (c == Subject.NONE) continue
            // "seseorang yang aku kenal", "someone I know", "teman aku": "aku/i" di sini menerangkan orang lain.
            if (c == Subject.FIRST && i > 0 && before8[i - 1] in RELATIVE_HEAD) continue
            subject = c
            break
        }
        if (FIRST_IN_MATCH.containsMatchIn(m.value)) subject = Subject.FIRST

        val negMatch = NEGATED.find(before8.takeLast(5).joinToString(" ") + " ")
        val negated = negMatch != null
        val exhortation = negMatch?.groupValues?.get(1) == "jangan"

        when (subject) {
            Subject.SECOND, Subject.DEVICE -> return Level.NONE
            Subject.THIRD -> return if (negated) Level.NONE else Level.OTHER
            else -> Unit
        }
        if (exhortation) return Level.NONE

        // 3. Informasi lunak ("tentang bunuh diri"): hanya dianggap informasi bila tidak ada tanda pribadi.
        val firstBefore = before8.any { it in FIRST }
        val thought = THOUGHT.containsMatchIn(before5)
        val soft = SOFT_INFO.containsMatchIn(before5)
        if (soft && !firstBefore && !thought) return Level.NONE

        return if (pat.strong) strongLevel(t, pat, msg, negated, soft && !thought, before8)
        else weakLevel(t, m, msg, negated)
    }

    private fun strongLevel(t: String, pat: Pat, msg: Msg, negated: Boolean, soft: Boolean, before8: List<String>): Level {
        if (negated || soft || msg.joke || msg.past) return Level.CHECK_IN
        if (pat.noun) {
            val firstAnywhere = t.split(' ').any { it in FIRST } || t.split(' ').any { it.length > 3 && it.endsWith("ku") }
            val intent = INTENT.containsMatchIn(before8.takeLast(3).joinToString(" "))
            val short = SupportText.words(t) <= 2
            if (!firstAnywhere && !intent && !short) return Level.CHECK_IN
        }
        return if (pat.immediate || msg.immediate) Level.IMMEDIATE else Level.SELF
    }

    private fun weakLevel(t: String, m: MatchResult, msg: Msg, negated: Boolean): Level {
        if (negated || msg.joke || msg.past) return Level.NONE
        if (HYPERBOLE.containsMatchIn(t)) return Level.NONE
        // "ingin mati" tepat di depan kata benda/idiom lain sudah disaring di pola; sisanya: tanya lembut.
        return if (WEAK_ESCALATE.containsMatchIn(t)) Level.SELF else Level.CHECK_IN
    }
}

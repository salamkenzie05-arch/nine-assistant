package id.nine.assistant.support

/**
 * Membaca ucapan curhat secara kasar: emosi apa yang disebut, topiknya apa, seberapa kuat, apakah minta saran,
 * bertanya soal diagnosis, dll. Berbasis kata kunci pada teks yang sudah dinormalkan (SupportText.norm).
 * Hanya untuk memilih jawaban pendengar yang pantas; BUKAN penilaian klinis. Kotlin murni.
 */
object Feelings {

    /** Urutan = prioritas bila beberapa emosi muncul sekaligus. */
    enum class Emotion { GRIEF, HOPELESS, NUMB, SELF_WORTH, LONELY, GUILT, OVERWHELMED, ANXIOUS, ANGRY, SAD, TIRED, CONFUSED, HAPPY }

    enum class Topic { WORK, STUDY, FAMILY, LOVE, FRIENDS, MONEY, HEALTH, SLEEP, FUTURE }

    class Reading(
        val emotions: List<Emotion>,
        val topic: Topic?,
        val intense: Boolean,
        val persistent: Boolean,
        val wantsAdvice: Boolean,
        val asksDiagnosis: Boolean,
        val selfLabel: Boolean,
        val asksIdentity: Boolean,
        val thanks: Boolean,
        val yes: Boolean,
        val no: Boolean,
        val unsure: Boolean,
        val mentionsFeeling: Boolean,
        val words: Int
    ) {
        val primary: Emotion? get() = emotions.firstOrNull()
        val negative: Boolean get() = emotions.any { it != Emotion.HAPPY }
        val positiveOnly: Boolean get() = emotions.isNotEmpty() && emotions.all { it == Emotion.HAPPY }
        val short: Boolean get() = words <= 3
        val long: Boolean get() = words >= 40
    }

    private fun r(s: String) = Regex("""\b(?:$s)\b""")

    private val LEX: List<Pair<Emotion, Regex>> = listOf(
        Emotion.GRIEF to r("kehilangan|meninggal|berduka|almarhum\\w*|kepergian|pemakaman|grief|grieving|passed away|bereaved|funeral"),
        Emotion.HOPELESS to r("putus asa|tidak ada harapan|tidak ada gunanya|sia sia|buntu|tidak ada jalan(?: keluar)?|tidak akan berubah|menyerah|hopeless|pointless|give up|no way out|nothing will change"),
        Emotion.NUMB to r("mati rasa|hampa|kosong|hambar|tidak merasakan apa apa|tidak merasa apa apa|numb|empty inside|feel empty|feel nothing"),
        Emotion.SELF_WORTH to r("tidak berguna|tidak berharga|tidak cukup baik|tidak pantas|gagal total|merasa gagal|selalu gagal|pecundang|bodoh|benci (?:diri|diriku)|merasa buruk|memalukan|payah|aku (?:hanya |cuma |jadi |merasa )?(?:menjadi )?beban|beban (?:bagi|untuk|buat) (?:semua|keluarga|orang)\\w*|worthless|useless|not good enough|failure|stupid|hate myself|loser|burden"),
        Emotion.LONELY to r("kesepian|sendirian|sepi|tidak ada teman|tidak ada yang peduli|tidak ada yang mengerti|diabaikan|dikucilkan|ditinggalkan|rindu|kangen|lonely|alone|isolated|no one cares|no one understands|left out|miss (?:him|her|them|home)"),
        Emotion.GUILT to r("merasa bersalah|bersalah|menyesal|salahku|aku salah|malu|guilty|regret|ashamed|my fault|shame|embarrassed"),
        Emotion.OVERWHELMED to r("kewalahan|tertekan|stres|stress\\w*|tekanan|terlalu banyak|tidak sanggup|tidak kuat|tidak tahan|beban|overwhelm\\w*|too much|pressure|cannot handle|can not handle|burnout|burned out"),
        Emotion.ANXIOUS to r("cemas|khawatir|takut|panik|gelisah|was was|deg degan|gugup|overthink\\w*|kepikiran|anxi\\w*|worried|worry|scared|nervous|panic\\w*|afraid|dread"),
        Emotion.ANGRY to r("marah|kesal|jengkel|sebal|benci|emosi|dongkol|geram|angry|furious|mad|annoyed|frustrat\\w*|hate|pissed|irritated"),
        Emotion.SAD to r("sedih|menangis|nangis|air mata|kecewa|patah hati|sakit hati|terluka|hancur|murung|galau|sendu|gagal|tidak dihargai|tidak dianggap|tidak dipedulikan|diremehkan|direndahkan|dimarahi|dibentak|dihina|dipermalukan|depresi|tidak baik baik saja|tidak oke|down|sad|cry|crying|upset|heartbroken|hurt|depressed|not okay|not ok|not fine"),
        Emotion.TIRED to r("capek|cape|lelah|letih|kelelahan|penat|lesu|tired|exhausted|drained|worn out"),
        Emotion.CONFUSED to r("bingung|ragu|bimbang|tidak yakin|confused|unsure|not sure"),
        Emotion.HAPPY to r("senang|bahagia|lega|bersyukur|gembira|semangat|bangga|lebih baik|lebih tenang|lebih ringan|mendingan|happy|glad|relieved|grateful|proud|better|calmer|lighter")
    )

    private val TOPICS: List<Pair<Topic, Regex>> = listOf(
        Topic.WORK to r("kerja|kerjaan|bekerja|kantor|bos|atasan|rekan kerja|pekerjaan|lembur|karier|karir|deadline|klien|work|job|boss|office|coworker|colleague|career|workplace|overtime"),
        Topic.STUDY to r("kuliah|sekolah|skripsi|tesis|tugas|ujian|dosen|guru|nilai|kampus|belajar|study|exam|school|university|thesis|homework|college|teacher|grades"),
        Topic.FAMILY to r("ayah|ibu|orang tua|orangtua|ortu|keluarga|kakak|adik|mama|papa|bapak|mertua|family|parents|mom|dad|brother|sister|mother|father"),
        Topic.LOVE to r("pacar|pasangan|mantan|putus|cinta|suami|istri|nikah|menikah|gebetan|selingkuh|boyfriend|girlfriend|partner|ex|breakup|relationship|wife|husband|divorce|cerai"),
        Topic.FRIENDS to r("teman\\w*|sahabat\\w*|geng|pertemanan|friend\\w*"),
        Topic.MONEY to r("uang|utang|hutang|gaji|biaya|bayar|tagihan|keuangan|bangkrut|cicilan|money|debt|salary|bills|broke|rent"),
        Topic.HEALTH to r("sakit(?! hati)|dokter|rumah sakit|penyakit|nyeri|kesehatan|sick|doctor|hospital|illness|pain|health"),
        Topic.SLEEP to r("tidur|insomnia|begadang|mimpi buruk|sleep|nightmare|sleepless"),
        Topic.FUTURE to r("masa depan|cita cita|tujuan hidup|future|goals")
    )

    private val HEAVY = setOf(Emotion.GRIEF, Emotion.HOPELESS, Emotion.NUMB, Emotion.SELF_WORTH)

    private val NEGATORS = setOf("tidak", "bukan", "belum", "bukannya", "tanpa", "not", "no", "never", "dont")

    private val INTENSE = Regex("""\b(?:banget|sekali|sangat|parah|hancur|tidak tahan|really|extremely|very|terrible|awful|unbearable|so (?:sad|tired|scared|angry|alone|lonely|stressed))\b""")
    private val PERSISTENT = Regex("""\b(?:berminggu minggu|berbulan bulan|bertahun tahun|setiap hari|tiap hari|terus menerus|selalu|sudah lama|lama sekali|all the time|every day|for weeks|for months|for years|always|constantly)\b""")
    private val ADVICE = Regex("""\b(?:harus (?:bagaimana|gimana|apa)|apa yang harus|harus ngapain|gimana (?:ya|dong|caranya)|bagaimana (?:caranya|ya|sebaiknya)|enaknya (?:gimana|bagaimana)|kasih saran|beri saran|minta saran|butuh saran|saran(?:mu| dong)?|what should i do|any advice|any tips|your advice|what do i do|how do i (?:deal|cope|handle))\b""")

    private const val DISORDER =
        "depresi|depression|anxiety|gangguan kecemasan|gangguan mental|gangguan jiwa|bipolar|adhd|ocd|ptsd|skizofrenia|sakit jiwa|gila|mental illness|mentally ill|trauma"
    private val DIAGNOSIS = Regex(
        """\b(?:(?:apakah|apa) (?:aku |ini |itu )?(?:mengalami |punya |terkena |kena |menderita |termasuk )?(?:$DISORDER)|aku (?:$DISORDER) (?:ya|kah|bukan)|diagnos\w+|(?:am i|do i have|do i suffer from|is this|is it|could i have|might i have|am i suffering from) (?:depressed|$DISORDER)|apakah aku (?:sakit mental|tidak normal|normal)|am i (?:normal|crazy|sick in the head))\b"""
    )
    private val SELF_LABEL = Regex("""\b(?:$DISORDER)\b""")
    private val IDENTITY = Regex(
        """\b(?:kamu|anda|nine) (?:itu |ini |adalah |seorang |sebenarnya )?(?:psikolog|konselor|terapis|psikiater|dokter|manusia|robot|asli|beneran|bot|ai)\b|\b(?:are you|you are|you re) (?:a |an |really |actually )?(?:real |professional |licensed )?(?:psychologist|therapist|counselor|counsellor|psychiatrist|doctor|human|robot|real|ai|bot)\b|\bsiapa kamu\b|\bwho are you\b"""
    )
    private val THANKS = Regex("""\b(?:terima kasih|makasih|mksh|thanks|thank you|thx|tengkyu|terimakasih)\b""")
    private val YES = Regex("""^(?:iya|ya|yes|yup|yep|betul|benar|boleh|mau|ok|oke|okay|baik|sure|please|ayo|yuk|ada|pernah|sedikit|sudah|kadang|kadang kadang|sometimes|a little)\b""")
    private val NO = Regex(
        """^(?:tidak|bukan|no|nope|nah|belum|jangan|nanti saja|nanti dulu|tidak usah|tidak perlu|aman|aku aman|baik baik saja|aku baik baik saja|aku tidak apa apa|i am fine|i am okay|i am ok|i am safe|just kidding|cuma bercanda|hanya bercanda|bercanda|canda|never mind|nevermind|skip)\b"""
    )
    private val FEELING_WORD = Regex("""\b(?:merasa|perasaan|perasaanku|rasanya|terasa|feel|feeling|felt|feels)\b""")
    private val UNSURE = Regex("""^(?:hmm+|hm+|entahlah|entah|tidak tahu|nggak tau|tidak tau|mungkin|i do not know|dunno|maybe|ah|eh|yah|umm+|uh+)\b""")

    private fun negated(t: String, start: Int): Boolean {
        val before = t.substring(0, start).trim().split(' ').filter { it.isNotEmpty() }.takeLast(2)
        return before.any { it in NEGATORS }
    }

    fun read(raw: String): Reading {
        val t = SupportText.norm(raw)
        val found = ArrayList<Emotion>()
        val firstPos = HashMap<Emotion, Int>()
        for ((emo, re) in LEX) {
            val hit = re.findAll(t).firstOrNull { m -> m.value.startsWith("tidak") || !negated(t, m.range.first) }
            if (hit != null) { found.add(emo); firstPos[emo] = hit.range.first }
        }
        // Tema berat selalu didahulukan; sisanya menurut urutan disebut (mis. "capek ... bos marah" -> lelah dulu).
        found.sortWith(compareBy<Emotion>({ if (it in HEAVY) 0 else 1 }, { if (it in HEAVY) it.ordinal else firstPos[it] ?: 0 }))
        // "lebih baik" juga muncul di "lebih baik aku mati/tidak ada": itu bukan perasaan positif.
        if (Emotion.HAPPY in found && Regex("""\blebih baik (?:aku )?(?:mati|tidak|menghilang)""").containsMatchIn(t)) {
            val others = found.filter { it != Emotion.HAPPY }
            found.clear(); found.addAll(others)
        }
        var topic: Topic? = null
        var best = 0
        for ((tp, re) in TOPICS) {
            val n = re.findAll(t).count()
            if (n > best) { best = n; topic = tp }
        }
        return Reading(
            emotions = found,
            topic = topic,
            intense = INTENSE.containsMatchIn(t),
            persistent = PERSISTENT.containsMatchIn(t),
            wantsAdvice = ADVICE.containsMatchIn(t),
            asksDiagnosis = DIAGNOSIS.containsMatchIn(t),
            selfLabel = SELF_LABEL.containsMatchIn(t),
            asksIdentity = IDENTITY.containsMatchIn(t),
            thanks = THANKS.containsMatchIn(t),
            yes = YES.containsMatchIn(t),
            no = NO.containsMatchIn(t),
            unsure = UNSURE.containsMatchIn(t),
            mentionsFeeling = FEELING_WORD.containsMatchIn(t),
            words = SupportText.words(raw)
        )
    }
}

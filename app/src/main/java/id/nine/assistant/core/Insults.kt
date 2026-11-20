package id.nine.assistant.core

/**
 * Mendeteksi bila pengguna menghina atau memarahi Nine, supaya Nine menunjukkan wajah sedih atau takut.
 * Hanya yang ditujukan ke Nine ("kamu bodoh", "dasar goblok", "aku marah sama kamu"), bukan curhat soal orang lain.
 * Kotlin murni.
 */
object Insults {

    enum class Kind { NONE, INSULT, ANGRY }

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val insultWords = rx(
        """\b(?:bodoh|bodo|goblok|tolol|bego|dungu|sialan|brengsek|bangsat|kampret|bajingan|payah|jelek|sampah|idiot|stupid|dumb|useless|trash|garbage|worthless|pathetic|moron|jerk|loser|suck(?:s)?|lemot|nyebelin|ngeselin|menyebalkan|mengesalkan|annoying|ga\s+berguna|gak\s+berguna|nggak\s+berguna|tidak\s+berguna|ga\s+becus|gak\s+becus|tidak\s+becus|bacot|bawel|cerewet|jahat|culun|norak)\b"""
    )
    private val strongInsult = rx("""\b(?:anjing|bangsat|bajingan|kampret|sialan|brengsek|tai|bangke|fuck|shit|asshole|bitch|damn)\b""")
    private val toNine = rx("""\b(?:kamu|kau|lu|lo|elu|loe|anda|nine|dasar|you|your|ur)\b""")
    private val shutUp = rx("""\b(?:diam|diem|shut\s+up|shut\s+it|pergi\s+sana|go\s+away|enyah|minggat)\b""")
    private val hate = rx("""\b(?:benci|hate|muak|sebel|sebal)\b.*\b(?:kamu|kau|lu|lo|elu|nine|you)\b|\b(?:kamu|kau|lu|lo|elu|nine|you)\b.*\b(?:benci|hate)\b""")
    private val angryAtNine = rx("""\b(?:marah|kesal|kesel|jengkel|geram|murka|angry|mad|furious|pissed|annoyed)\b.*\b(?:sama|pada|kepada|ke|at|with)\s+(?:kamu|kau|lu|lo|elu|nine|you)\b|\b(?:kamu|kau|lu|lo|elu|nine|you)\b.*\b(?:bikin|buat|membuat|makes?|made)\s+(?:aku|saya|gue|gw|me)\s+(?:marah|kesal|kesel|jengkel|angry|mad|furious)\b""")
    private val benign = rx("""\b(?:anjing\s+(?:saya|aku|ku|peliharaan|kecil|lucu)|my\s+dog|bukan|tidak\s+bodoh|not\s+stupid)\b""")

    fun detect(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty() || benign.containsMatchIn(t)) return Kind.NONE
        if (angryAtNine.containsMatchIn(t)) return Kind.ANGRY
        val words = t.split(Regex("\\s+")).size
        val directed = toNine.containsMatchIn(t) || words <= 4
        if (hate.containsMatchIn(t)) return Kind.ANGRY
        if (shutUp.containsMatchIn(t) && (toNine.containsMatchIn(t) || words <= 3)) return Kind.ANGRY
        if (strongInsult.containsMatchIn(t) && directed) return Kind.ANGRY
        if (insultWords.containsMatchIn(t) && directed) return Kind.INSULT
        return Kind.NONE
    }

    /** Balasan singkat dan lemah lembut sesuai jenis [kind]. [sfx] = ", Tuan" atau kosong. */
    fun reply(kind: Kind, lang: AppLang, sfx: String, pick: Int): String {
        val sad = if (lang == AppLang.ID) listOf(
            "Maaf$sfx... saya salah apa? Saya akan berusaha lebih baik.",
            "Hiks... itu agak menyakitkan$sfx. Tapi saya akan belajar dari kesalahan saya.",
            "Saya sedih mendengarnya$sfx. Beri tahu saya apa yang bisa saya perbaiki."
        ) else listOf(
            "I'm sorry$sfx... what did I do wrong? I'll try to do better.",
            "Ouch... that stung a little$sfx. But I'll learn from my mistakes.",
            "That makes me sad$sfx. Do tell me what I can improve."
        )
        val scared = if (lang == AppLang.ID) listOf(
            "A-ampun$sfx... saya takut. Saya minta maaf kalau saya membuat Anda kesal.",
            "Eh... jangan marah, saya jadi takut$sfx. Saya akan lebih baik, janji.",
            "Maaf$sfx! Saya gemetar... tolong tenang dulu, saya akan memperbaikinya."
        ) else listOf(
            "P-please$sfx... I'm scared. I'm so sorry if I upset you.",
            "Oh... please don't be angry, it frightens me$sfx. I'll do better, I promise.",
            "Sorry$sfx! I'm trembling... please calm down, I'll fix it."
        )
        val list = if (kind == Kind.ANGRY) scared else sad
        return list[((pick % list.size) + list.size) % list.size]
    }

    /** Ekspresi untuk [kind]: marah → takut, hinaan → sedih. */
    fun mood(kind: Kind): Mood = if (kind == Kind.ANGRY) Mood.SCARED else Mood.SAD
}

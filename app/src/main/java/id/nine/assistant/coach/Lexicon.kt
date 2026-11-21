package id.nine.assistant.coach

/**
 * Daftar kata untuk pemeriksa grammar (Kotlin murni, tanpa Android). Sengaja kecil dan berisi kata yang umum dipakai
 * pelajar: lebih baik melewatkan kesalahan langka daripada "mengoreksi" kalimat yang sebenarnya benar.
 */
internal object Lexicon {

    private class V(val base: String, val past: String, val pp: String)

    private val irregular = listOf(
        V("go", "went", "gone"), V("do", "did", "done"), V("have", "had", "had"), V("say", "said", "said"),
        V("get", "got", "got"), V("make", "made", "made"), V("know", "knew", "known"), V("think", "thought", "thought"),
        V("take", "took", "taken"), V("see", "saw", "seen"), V("come", "came", "come"), V("give", "gave", "given"),
        V("find", "found", "found"), V("tell", "told", "told"), V("feel", "felt", "felt"), V("buy", "bought", "bought"),
        V("eat", "ate", "eaten"), V("drink", "drank", "drunk"), V("drive", "drove", "driven"), V("write", "wrote", "written"),
        V("speak", "spoke", "spoken"), V("run", "ran", "run"), V("sleep", "slept", "slept"), V("meet", "met", "met"),
        V("leave", "left", "left"), V("bring", "brought", "brought"), V("begin", "began", "begun"), V("keep", "kept", "kept"),
        V("sit", "sat", "sat"), V("stand", "stood", "stood"), V("hear", "heard", "heard"), V("pay", "paid", "paid"),
        V("wear", "wore", "worn"), V("sell", "sold", "sold"), V("send", "sent", "sent"), V("build", "built", "built"),
        V("spend", "spent", "spent"), V("lose", "lost", "lost"), V("win", "won", "won"), V("sing", "sang", "sung"),
        V("swim", "swam", "swum"), V("fly", "flew", "flown"), V("forget", "forgot", "forgotten"),
        V("understand", "understood", "understood"), V("teach", "taught", "taught"), V("catch", "caught", "caught"),
        V("fall", "fell", "fallen"), V("grow", "grew", "grown"), V("draw", "drew", "drawn"), V("ride", "rode", "ridden"),
        V("wake", "woke", "woken"), V("choose", "chose", "chosen"), V("break", "broke", "broken"), V("become", "became", "become"),
        V("show", "showed", "shown"), V("lend", "lent", "lent"), V("throw", "threw", "thrown"), V("hide", "hid", "hidden"),
        V("read", "read", "read"), V("put", "put", "put"), V("cut", "cut", "cut"), V("let", "let", "let"),
        V("hit", "hit", "hit"), V("set", "set", "set")
    )

    /** Kata kerja beraturan (bentuk dasar) yang dikenali. */
    private val regular = setOf(
        "want", "like", "love", "need", "play", "work", "study", "live", "watch", "help", "use", "try", "call", "ask",
        "talk", "walk", "cook", "clean", "open", "close", "start", "stop", "listen", "learn", "visit", "enjoy", "hate",
        "wait", "look", "move", "change", "stay", "finish", "remember", "decide", "plan", "travel", "prefer", "believe",
        "agree", "invite", "cry", "carry", "worry", "happen", "miss", "wash", "push", "pass", "fix", "dance", "smile",
        "laugh", "relax", "shop", "text", "cycle", "exercise", "practise", "practice", "answer", "arrive", "borrow",
        "chat", "climb", "join", "kiss", "mean", "order", "paint", "rain", "rest", "share", "shout", "turn", "wish"
    )

    private val bases: Set<String> = irregular.map { it.base }.toSet() + regular
    private val pastToBase: Map<String, String> =
        irregular.filter { it.past != it.base }.associate { it.past to it.base }
    private val ppToBase: Map<String, String> =
        irregular.filter { it.pp != it.base && it.pp != it.past }.associate { it.pp to it.base }
    private val pastOfIrregular: Map<String, String> = irregular.associate { it.base to it.past }

    private val ppOfBase: Map<String, String> = irregular.associate { it.base to it.pp }

    /** Participle (V3) kata kerja tak beraturan; null bila bukan kata kerja tak beraturan yang dikenal. */
    fun participle(base: String): String? = ppOfBase[base]

    /** Kata kerja yang bentuk lampaunya sama dengan bentuk dasar (read, put, cut, let, hit, set): tidak bisa dibedakan tanpa konteks. */
    fun pastEqualsBase(w: String): Boolean = pastOfIrregular[w] == w

    /** Bentuk lampau yang juga kata benda/sifat/dasar dan sering sah: jangan dianggap salah di semua konteks. */
    val AMBIGUOUS_PAST = setOf("saw", "found", "left", "fell", "set", "bore")

    fun isBase(w: String): Boolean = w in bases

    fun isIrregular(w: String): Boolean = w in pastOfIrregular

    /** Bentuk -s/-es untuk orang ketiga tunggal. */
    fun third(base: String): String = when {
        base == "have" -> "has"
        base == "do" -> "does"
        base == "go" -> "goes"
        base.endsWith("y") && base.length > 1 && base[base.length - 2] !in "aeiou" -> base.dropLast(1) + "ies"
        base.endsWith("s") || base.endsWith("x") || base.endsWith("z") || base.endsWith("ch") || base.endsWith("sh") -> base + "es"
        else -> base + "s"
    }

    /** Bentuk lampau (V2). */
    fun past(base: String): String {
        pastOfIrregular[base]?.let { return it }
        return when {
            base.endsWith("e") -> base + "d"
            base.endsWith("y") && base.length > 1 && base[base.length - 2] !in "aeiou" -> base.dropLast(1) + "ied"
            base in DOUBLING -> base + base.last() + "ed"
            else -> base + "ed"
        }
    }

    /** Bentuk -ing. */
    fun ing(base: String): String = when {
        base.endsWith("ie") -> base.dropLast(2) + "ying"
        base.endsWith("e") && !base.endsWith("ee") && !base.endsWith("ye") && !base.endsWith("oe") -> base.dropLast(1) + "ing"
        base in DOUBLING -> base + base.last() + "ing"
        else -> base + "ing"
    }

    private val DOUBLING = setOf(
        "run", "sit", "get", "put", "stop", "plan", "swim", "begin", "cut", "hit", "shop", "chat", "let", "set", "win"
    )

    /** "goes" → "go", "studies" → "study", "has" → "have"; null bila bukan bentuk -s dari kata kerja yang dikenal. */
    fun baseFromThird(w: String): String? {
        when (w) {
            "has" -> return "have"
            "does" -> return "do"
            "goes" -> return "go"
        }
        if (w.length < 3 || !w.endsWith("s") || w.endsWith("ss")) return null
        val cands = ArrayList<String>(3)
        if (w.endsWith("ies")) cands += w.dropLast(3) + "y"
        if (w.endsWith("es")) cands += w.dropLast(2)
        cands += w.dropLast(1)
        return cands.firstOrNull { it in bases && third(it) == w }
    }

    /** "went" → "go", "liked" → "like", "studied" → "study", "stopped" → "stop"; juga participle tak beraturan ("gone" → "go"). */
    fun baseFromPast(w: String): String? {
        pastToBase[w]?.let { return it }
        ppToBase[w]?.let { return it }
        if (w.length < 4 || !w.endsWith("d")) return null
        val cands = ArrayList<String>(4)
        if (w.endsWith("ied")) cands += w.dropLast(3) + "y"
        if (w.endsWith("ed")) {
            cands += w.dropLast(2)
            if (w.length > 5 && w[w.length - 3] == w[w.length - 4]) cands += w.dropLast(3) // stopped → stop
        }
        cands += w.dropLast(1) // liked → like
        return cands.firstOrNull { it in regular && past(it) == w }
    }

    /** "going" → "go", "making" → "make", "running" → "run", "studying" → "study". */
    fun baseFromIng(w: String): String? {
        if (w.length < 5 || !w.endsWith("ing")) return null
        val stem = w.dropLast(3)
        val cands = ArrayList<String>(4)
        cands += stem
        cands += stem + "e"
        if (stem.length > 2 && stem[stem.length - 1] == stem[stem.length - 2]) cands += stem.dropLast(1)
        if (stem.endsWith("y")) cands += stem.dropLast(1) + "ie"
        return cands.firstOrNull { it in bases && ing(it) == w }
    }
}

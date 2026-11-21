package id.nine.assistant.coach

import java.util.Locale

/** Bidang kesalahan; dipakai untuk ringkasan latihan ("paling sering salah: ..."). */
enum class Topic(val en: String, val id: String) {
    SUBJECT_VERB("subject-verb agreement", "kesesuaian subjek dan kata kerja"),
    TENSE("verb tense", "bentuk waktu (tense)"),
    VERB_FORM("verb forms", "bentuk kata kerja"),
    ARTICLES("a / an", "a / an"),
    PREPOSITIONS("prepositions", "kata depan"),
    WORD_ORDER("word order", "urutan kata"),
    NEGATION("negatives", "kalimat negatif"),
    MISSING_WORDS("missing words", "kata yang hilang"),
    COMPARISON("comparisons", "perbandingan"),
    NOUNS("nouns", "kata benda"),
    PRONOUNS("pronouns", "kata ganti")
}

/** Satu perbaikan: [from] menjadi [to] (potongan kalimat yang berubah) beserta penjelasan singkat dua bahasa. */
data class Issue(val topic: Topic, val from: String, val to: String, val tipEn: String, val tipId: String)

/**
 * Pemeriksa grammar bahasa Inggris berbasis aturan (tanpa AI, tanpa Android). Sengaja hati-hati: setiap aturan hanya
 * bertindak pada pola yang hampir pasti salah; kalimat yang benar tidak boleh "dikoreksi". Ucapan dari pengenal suara
 * biasanya tanpa huruf besar dan tanda baca, jadi keduanya tidak dianggap kesalahan.
 */
object GrammarCheck {

    data class Result(val original: String, val corrected: String, val issues: List<Issue>) {
        val ok: Boolean get() = issues.isEmpty()
    }

    fun check(text: String): Result {
        val src = text.trim()
        val tokens = TOKEN.findAll(src).map { it.value }.toMutableList()
        if (tokens.none { it[0].isLetter() }) return Result(src, src, emptyList())
        val c = Ctx(tokens)
        youngAge(c)
        pastTense(c)
        beVerbErrors(c)
        negation(c)
        veryVerb(c)
        missingBe(c)
        presentAgreement(c)
        beAgreement(c)
        afterAux(c)
        modals(c)
        toInfinitive(c)
        havePast(c)
        articles(c)
        comparatives(c)
        plurals(c)
        prepositions(c)
        whOrder(c)
        pronounMe(c)
        if (c.issues.isEmpty()) return Result(src, src, emptyList())
        return Result(src, polish(c.t, src), c.issues.toList())
    }

    // ------------------------------------------------------------------ dasar

    private val TOKEN = Regex("""[A-Za-z]+(?:['’\-][A-Za-z]+)*|\d+(?:[.,]\d+)*|[^\sA-Za-z\d]""")

    private class Ctx(val t: MutableList<String>) {
        val issues = ArrayList<Issue>()
        val size: Int get() = t.size

        /** Kata ke-[i] huruf kecil (apostrof lengkung disamakan); kosong bila di luar batas. */
        fun lw(i: Int): String = if (i < 0 || i >= t.size) "" else t[i].lowercase(Locale.ROOT).replace('’', '\'')

        fun set(i: Int, w: String) {
            val old = t[i]
            t[i] = if (i == 0 && old.isNotEmpty() && old[0].isUpperCase()) w.replaceFirstChar { it.uppercaseChar() } else w
        }

        /** Jalankan [edit] atas rentang [start, end) lalu catat perbaikannya (potongan sebelum → sesudah). */
        fun edit(start: Int, end: Int, topic: Topic, tipEn: String, tipId: String, block: Ctx.() -> Unit) {
            val before = t.subList(start, end).joinToString(" ")
            val sizeBefore = t.size
            block()
            val after = t.subList(start, (end + t.size - sizeBefore).coerceAtLeast(start)).joinToString(" ")
            issues.add(Issue(topic, before, after, tipEn, tipId))
        }
    }

    private val SUBJ = setOf("i", "you", "he", "she", "it", "we", "they")
    private val SUB3 = setOf("he", "she", "it")

    /** Kata yang membuka klausa baru: subjek sesudahnya memang subjek (bukan objek kata kerja sebelumnya). */
    private val STARTERS = setOf(
        ",", ";", ":", "and", "but", "or", "so", "because", "when", "while", "if", "then", "although", "though", "since",
        "unless", "until", "before", "after", "as", "where", "think", "know", "believe", "hope", "guess", "say", "said",
        "feel", "maybe", "perhaps", "also", "now", "today", "tonight", "tomorrow", "yesterday", "later", "finally",
        "actually", "honestly", "sometimes", "usually", "week", "month", "year", "night", "weekend", "morning", "afternoon",
        "evening", "ago", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    private fun clauseStart(c: Ctx, i: Int): Boolean = i == 0 || c.lw(i - 1) in STARTERS

    private val WH_ALL = setOf("what", "where", "why", "how", "when", "who", "which")

    /** Kata bantu di awal pertanyaan ("did you ...", "what did you ...") sehingga subjek ada SETELAH kata bantu. */
    private fun questionAux(c: Ctx, i: Int): Boolean = clauseStart(c, i) || c.lw(i - 1) in WH_ALL

    private val NUMWORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety", "hundred"
    )

    private fun isNumber(w: String): Boolean =
        w.isNotEmpty() && (w.all { it.isDigit() || it == '.' || it == ',' } || w in NUMWORDS || ('-' in w && w.substringBefore('-') in NUMWORDS))

    private fun beFor(s: String): String = when (s) {
        "i" -> "am"
        "he", "she", "it" -> "is"
        else -> "are"
    }

    // ------------------------------------------------------------------ umur: "I have 20 years old"

    private fun youngAge(c: Ctx) {
        var i = 0
        while (i + 4 < c.size) {
            val s = c.lw(i)
            val h = c.lw(i + 1)
            if (s in SUBJ && (h == "have" || h == "has") && isNumber(c.lw(i + 2)) &&
                (c.lw(i + 3) == "years" || c.lw(i + 3) == "year") && c.lw(i + 4) == "old"
            ) {
                c.edit(
                    i, i + 5, Topic.VERB_FORM,
                    "To give your age, use am/is/are, not have: \"I am 20 years old\".",
                    "Untuk umur pakai am/is/are, bukan have: \"I am 20 years old\"."
                ) { set(i + 1, beFor(s)) }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ lampau: "yesterday I go"

    private val PAST_UNITS = setOf("night", "week", "month", "year", "weekend", "summer", "winter", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    /** Kalau salah satu ini ada, kalimat bukan lampau sederhana: aturan lampau tidak bertindak sama sekali. */
    private val PAST_BLOCK = setOf(
        "tomorrow", "tonight", "next", "will", "won't", "shall", "going", "would", "can", "could", "should", "must", "may", "might",
        "did", "didn't", "didnt", "used"
    )
    private val SEG_BREAK = setOf(",", ";", ":", ".", "!", "?", "that", "because", "but", "so", "when", "while", "if", "which", "who", "although", "though")
    private val AUX_ALL = setOf(
        "am", "is", "are", "was", "were", "be", "been", "being", "has", "have", "had", "do", "does", "did", "will", "would",
        "can", "could", "should", "must", "may", "might", "shall"
    )
    /** Kata kerja yang biasanya diikuti klausa ("I think yesterday was fun"): jangan dilampaukan. */
    private val CLAUSE_VERBS = setOf(
        "think", "thinks", "know", "knows", "believe", "believes", "hope", "hopes", "guess", "say", "says", "feel", "feels", "wish",
        "mean", "means", "remember", "remembers", "forget", "forgets", "understand", "understands", "suppose", "tell", "tells"
    )
    private val NOUNISH_PREV = setOf(
        "to", "the", "a", "an", "my", "your", "his", "her", "our", "their", "some", "any", "of", "for", "in", "at", "on", "with", "this", "that"
    )

    private fun isPastMarker(c: Ctx, i: Int): Boolean {
        val w = c.lw(i)
        return w == "yesterday" || w == "ago" || (w == "last" && c.lw(i + 1) in PAST_UNITS)
    }

    private fun hasPastMarker(c: Ctx): Boolean = (0 until c.size).any { isPastMarker(c, it) }

    private fun isVerbLike(w: String): Boolean =
        Lexicon.isBase(w) || Lexicon.baseFromThird(w) != null || Lexicon.baseFromPast(w) != null || Lexicon.baseFromIng(w) != null

    private fun pastTense(c: Ctx) {
        if (c.t.any { val w = it.lowercase(Locale.ROOT).replace('’', '\''); w in PAST_BLOCK || w.endsWith("'ll") }) {
            // "do you go to school yesterday?" dilewati juga: aturan lampau hanya untuk kalimat sederhana.
            return
        }
        val marks = (0 until c.size).filter { isPastMarker(c, it) }
        if (marks.isEmpty()) return

        // Pertanyaan: "do you go to school yesterday" -> "did you go ..."
        if ((c.lw(0) == "do" || c.lw(0) == "does") && c.lw(1) in SUBJ && Lexicon.isBase(c.lw(2))) {
            c.edit(
                0, 3, Topic.TENSE,
                "Use \"did\" for questions about the past (yesterday, last week, ago).",
                "Pakai \"did\" untuk pertanyaan tentang masa lalu (yesterday, last week, ago)."
            ) { set(0, "did") }
            return
        }

        val done = HashSet<Int>()
        for (m in marks) {
            var segStart = 0
            for (k in m - 1 downTo 0) {
                if (c.lw(k) in SEG_BREAK) {
                    segStart = k + 1
                    break
                }
            }
            var segEnd = c.size - 1
            for (k in m + 1 until c.size) {
                if (c.lw(k) in SEG_BREAK) {
                    segEnd = k - 1
                    break
                }
            }
            val pairs = (segStart..segEnd).filter { i ->
                c.lw(i) in SUBJ && (i == segStart || clauseStart(c, i)) && i + 1 <= segEnd && isPastCandidate(c.lw(i + 1))
            }
            if (pairs.isEmpty()) continue
            val p = if (m < pairs.first()) pairs.first() else (pairs.lastOrNull { it < m } ?: continue)
            if (p in done) continue
            if (!singleClause(c, segStart, segEnd, p)) continue
            if (toPast(c, p)) done.add(p)
        }
    }

    private fun isPastCandidate(w: String): Boolean {
        if (w == "am" || w == "is" || w == "are") return true
        if (w in CLAUSE_VERBS) return false
        if (Lexicon.isBase(w) && !Lexicon.pastEqualsBase(w)) return true
        val b = Lexicon.baseFromThird(w) ?: return false
        return !Lexicon.pastEqualsBase(b) && b !in CLAUSE_VERBS
    }

    /** Segmen hanya berisi satu klausa: tidak ada subjek/bantu lain dan tidak ada kata kerja lain (selain setelah to/artikel). */
    private fun singleClause(c: Ctx, start: Int, end: Int, p: Int): Boolean {
        for (k in start..end) {
            if (k == p || k == p + 1) continue
            val w = c.lw(k)
            if (w in SUBJ || w in AUX_ALL) return false
            if (isVerbLike(w) && c.lw(k - 1) !in NOUNISH_PREV) return false
        }
        return true
    }

    private fun toPast(c: Ctx, p: Int): Boolean {
        val s = c.lw(p)
        val v = c.lw(p + 1)
        val np: String = when {
            v == "am" || v == "is" -> "was"
            v == "are" -> "were"
            Lexicon.isBase(v) -> Lexicon.past(v)
            else -> Lexicon.baseFromThird(v)?.let { Lexicon.past(it) } ?: return false
        }
        if (np == v) return false
        // "yesterday you are" -> were (you/we/they), "yesterday I am" -> was
        val fixed = if (np == "was" && s in setOf("you", "we", "they")) "were" else np
        c.edit(
            p, p + 2, Topic.TENSE,
            "Use the past tense for finished actions (yesterday, last week, ago).",
            "Pakai bentuk lampau untuk kejadian yang sudah selesai (yesterday, last week, ago)."
        ) { set(p + 1, fixed) }
        return true
    }

    // ------------------------------------------------------------------ be + kata kerja

    private val PROG_SAFE = setOf(
        "go", "come", "eat", "drink", "sleep", "study", "play", "watch", "work", "write", "talk", "walk", "run", "cook", "wait",
        "listen", "learn", "travel", "stay", "sit", "stand", "laugh", "smile", "cry", "dance", "shop", "swim", "sing", "fly",
        "drive", "ride", "wash", "text", "chat", "climb"
    )
    private val NEG_VERBS = setOf(
        "understand", "know", "like", "want", "have", "speak", "think", "remember", "see", "hear", "care", "believe", "need",
        "agree", "eat", "drink", "work", "study", "play", "go", "come"
    )

    private fun beMatches(s: String, b: String): Boolean = when (s) {
        "i" -> b == "am"
        "he", "she", "it" -> b == "is"
        else -> b == "are"
    }

    private fun beVerbErrors(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val s = c.lw(i)
            // "i'm agree"
            if (s == "i'm" && c.lw(i + 1) == "agree") {
                c.edit(
                    i, i + 2, Topic.VERB_FORM,
                    "\"Agree\" is already a verb: say \"I agree\", not \"I am agree\".",
                    "\"Agree\" sudah kata kerja: katakan \"I agree\", bukan \"I am agree\"."
                ) { set(i, "I"); }
                i++
                continue
            }
            if (s == "i'm" && c.lw(i + 1) in PROG_SAFE && clauseStart(c, i)) {
                val v = c.lw(i + 1)
                c.edit(
                    i, i + 2, Topic.VERB_FORM,
                    "After am/is/are use verb + -ing (I am going).",
                    "Setelah am/is/are pakai kata kerja + -ing (I am going)."
                ) { set(i + 1, Lexicon.ing(v)) }
                i++
                continue
            }
            if (s !in SUBJ || !clauseStart(c, i) || !beMatches(s, c.lw(i + 1))) {
                i++
                continue
            }
            val n2 = c.lw(i + 2)
            if (n2 == "interesting" && c.lw(i + 3) in setOf("in", "about")) {
                c.edit(
                    i, i + 4, Topic.VERB_FORM,
                    "Use \"interested\" for how YOU feel (\"I am interested in music\"); \"interesting\" describes the thing.",
                    "Pakai \"interested\" untuk perasaan KAMU (\"I am interested in music\"); \"interesting\" menggambarkan bendanya."
                ) {
                    set(i + 2, "interested")
                    set(i + 3, "in")
                }
            } else if (n2 == "agree") {
                c.edit(
                    i, i + 3, Topic.VERB_FORM,
                    "\"Agree\" is already a verb: say \"I agree\", not \"I am agree\".",
                    "\"Agree\" sudah kata kerja: katakan \"I agree\", bukan \"I am agree\"."
                ) {
                    remove(i + 1)
                    if (s in SUB3) set(i + 1, "agrees")
                }
            } else if (n2 == "not" && c.lw(i + 3) in NEG_VERBS) {
                val aux = if (s in SUB3) "doesn't" else "don't"
                c.edit(
                    i, i + 4, Topic.NEGATION,
                    "Make a negative with do/does + not: \"I don't understand\", not \"I am not understand\".",
                    "Kalimat negatif pakai do/does + not: \"I don't understand\", bukan \"I am not understand\"."
                ) {
                    remove(i + 1)
                    set(i + 1, aux)
                }
            } else if (n2 in PROG_SAFE) {
                c.edit(
                    i, i + 3, Topic.VERB_FORM,
                    "After am/is/are use verb + -ing (I am going).",
                    "Setelah am/is/are pakai kata kerja + -ing (I am going)."
                ) { set(i + 2, Lexicon.ing(n2)) }
            }
            i++
        }
    }

    private fun Ctx.remove(i: Int) {
        t.removeAt(i)
    }

    private fun Ctx.insert(i: Int, w: String) {
        t.add(i, w)
    }

    // ------------------------------------------------------------------ negatif tanpa do

    private fun negation(c: Ctx) {
        var i = 0
        while (i + 2 < c.size) {
            val s = c.lw(i)
            val w = c.lw(i + 1)
            val v = c.lw(i + 2)
            if (s in SUBJ && clauseStart(c, i) && (w == "no" || w == "not") && Lexicon.isBase(v)) {
                val aux = if (s in SUB3) "doesn't" else "don't"
                c.edit(
                    i, i + 3, Topic.NEGATION,
                    "Make a negative with do/does + not: \"I don't like it\".",
                    "Kalimat negatif pakai do/does + not: \"I don't like it\"."
                ) { set(i + 1, aux) }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ "I very like"

    private val VERY_VERBS = setOf("like", "love", "want", "need", "enjoy", "hate", "miss")

    private fun veryVerb(c: Ctx) {
        var i = 0
        while (i + 2 < c.size) {
            val s = c.lw(i)
            if (s in SUBJ && clauseStart(c, i) && c.lw(i + 1) == "very" && c.lw(i + 2) in VERY_VERBS) {
                val v = c.lw(i + 2)
                c.edit(
                    i, i + 3, Topic.WORD_ORDER,
                    "Don't put \"very\" before a verb: say \"I really like it\" or \"I like it very much\".",
                    "Jangan taruh \"very\" sebelum kata kerja: \"I really like it\" atau \"I like it very much\"."
                ) {
                    set(i + 1, "really")
                    if (s in SUB3) set(i + 2, Lexicon.third(v))
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ be hilang: "I happy"

    private val ADJ_SAFE = setOf(
        "happy", "sad", "tired", "busy", "hungry", "thirsty", "sleepy", "angry", "sick", "ready", "late", "sorry", "glad",
        "nervous", "afraid", "lazy", "hot", "cold", "beautiful", "handsome", "smart", "tall", "old", "young", "rich", "poor"
    )
    private val INTENS = setOf("very", "so", "too", "really", "quite")
    private val NOT_AFTER_ADJ = setOf(
        "him", "her", "them", "me", "us", "it", "you", "the", "a", "an", "my", "your", "his", "our", "their", "this", "that", "of"
    )

    private fun missingBe(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val s = c.lw(i)
            if (s in SUBJ && clauseStart(c, i)) {
                var j = i + 1
                if (c.lw(j) in INTENS) j++
                val adj = c.lw(j)
                if (adj in ADJ_SAFE && c.lw(j + 1) !in NOT_AFTER_ADJ) {
                    val be = beFor(s)
                    c.edit(
                        i, j + 1, Topic.MISSING_WORDS,
                        "English needs am/is/are before an adjective: \"I am $adj\".",
                        "Bahasa Inggris butuh am/is/are sebelum kata sifat: \"I am $adj\"."
                    ) { insert(i + 1, be) }
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ kesesuaian subjek-kata kerja (sekarang)

    private val LIKE_NOT_VERB_NEXT = setOf("a", "an", "the", "he", "she", "they", "i", "we", "it", "this", "that", "my", "your", "his", "her", "our", "their")

    private fun presentAgreement(c: Ctx) {
        if (hasPastMarker(c)) return
        var i = 0
        while (i + 1 < c.size) {
            val s = c.lw(i)
            val n = c.lw(i + 1)
            if (s in SUB3 && clauseStart(c, i)) {
                if (n == "don't" || n == "dont") {
                    c.edit(
                        i, i + 2, Topic.SUBJECT_VERB,
                        "With he/she/it use \"doesn't\": \"he doesn't like it\".",
                        "Dengan he/she/it pakai \"doesn't\": \"he doesn't like it\"."
                    ) { set(i + 1, "doesn't") }
                } else if (Lexicon.isBase(n) && !Lexicon.pastEqualsBase(n) && n != "be") {
                    val skipLike = n == "like" && c.lw(i + 2) in LIKE_NOT_VERB_NEXT
                    if (!skipLike) {
                        c.edit(
                            i, i + 2, Topic.SUBJECT_VERB,
                            "With he/she/it the verb takes -s: \"he goes\", \"she has\".",
                            "Dengan he/she/it kata kerja diberi -s: \"he goes\", \"she has\"."
                        ) { set(i + 1, Lexicon.third(n)) }
                    }
                }
            } else if (s in SUBJ && s !in SUB3 && (s != "you" || clauseStart(c, i))) {
                if (n == "doesn't" || n == "doesnt") {
                    c.edit(
                        i, i + 2, Topic.SUBJECT_VERB,
                        "With I/you/we/they use \"don't\": \"I don't know\".",
                        "Dengan I/you/we/they pakai \"don't\": \"I don't know\"."
                    ) { set(i + 1, "don't") }
                } else {
                    val b = Lexicon.baseFromThird(n)
                    if (b != null) {
                        c.edit(
                            i, i + 2, Topic.SUBJECT_VERB,
                            "With I/you/we/they the verb has no -s: \"I like\", \"they go\".",
                            "Dengan I/you/we/they kata kerja tanpa -s: \"I like\", \"they go\"."
                        ) { set(i + 1, b) }
                    }
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ be: am / is / are / was / were

    private val IF_SET = setOf("if", "wish", "though", "as", "suppose", "imagine")

    private fun beAgreement(c: Ctx) {
        var i = 0
        while (i + 1 < c.size) {
            val s = c.lw(i)
            val n = c.lw(i + 1)
            if (s in SUBJ && clauseStart(c, i)) {
                val fix: String? = when {
                    s == "i" && (n == "is" || n == "are") -> "am"
                    s in SUB3 && (n == "am" || n == "are") -> "is"
                    s !in SUB3 && s != "i" && (n == "is" || n == "am") -> "are"
                    (s == "i" || s in SUB3) && n == "were" && c.lw(i - 1) !in IF_SET -> "was"
                    s !in SUB3 && s != "i" && n == "was" -> "were"
                    else -> null
                }
                if (fix != null) {
                    c.edit(
                        i, i + 2, Topic.SUBJECT_VERB,
                        "Use am with I, is with he/she/it, and are with you/we/they (was with I/he/she/it, were with you/we/they).",
                        "Pakai am untuk I, is untuk he/she/it, dan are untuk you/we/they (was untuk I/he/she/it, were untuk you/we/they)."
                    ) { set(i + 1, fix) }
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ setelah do / does / did

    private fun afterAux(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val a = c.lw(i)
            if (a !in DO_FORMS) {
                i++
                continue
            }
            var neg = a.endsWith("n't") || a == "dont" || a == "doesnt" || a == "didnt"
            var j = i + 1
            if (c.lw(j) == "not") {
                neg = true
                j++
            }
            var inverted = false
            if (c.lw(j) in SUBJ && questionAux(c, i)) {
                inverted = true
                j++
            }
            val afterSubj = i > 0 && c.lw(i - 1) in SUBJ
            if (j >= c.size || !(neg || inverted || afterSubj)) {
                i++
                continue
            }
            val v = c.lw(j)
            val bp = Lexicon.baseFromPast(v)
            val b3 = Lexicon.baseFromThird(v)
            val fix: String? = when {
                bp != null && bp != v -> if (v !in Lexicon.AMBIGUOUS_PAST || neg || inverted) bp else null
                b3 != null -> b3
                else -> null
            }
            if (fix != null) {
                c.edit(
                    j, j + 1, Topic.VERB_FORM,
                    "After do/does/did use the base verb: \"did you go?\", \"he doesn't like it\".",
                    "Setelah do/does/did pakai kata kerja dasar: \"did you go?\", \"he doesn't like it\"."
                ) { set(j, fix) }
            }
            i++
        }
    }

    private val DO_FORMS = setOf("do", "does", "did", "don't", "doesn't", "didn't", "dont", "doesnt", "didnt")

    // ------------------------------------------------------------------ modal

    private val MODALS = setOf(
        "can", "could", "will", "would", "should", "must", "may", "might", "shall", "won't", "can't", "cannot", "couldn't",
        "wouldn't", "shouldn't", "mustn't"
    )

    private fun modals(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val m = c.lw(i)
            if (m !in MODALS || !(c.lw(i - 1) in SUBJ || questionAux(c, i))) {
                i++
                continue
            }
            var j = i + 1
            if (c.lw(j) == "not") j++
            if (c.lw(j) in SUBJ && questionAux(c, i)) j++
            val v = c.lw(j)
            val tip = "After can/will/should/must use the base verb with no \"to\": \"I can go\", \"she will come\"."
            val tipId = "Setelah can/will/should/must pakai kata kerja dasar tanpa \"to\": \"I can go\", \"she will come\"."
            if (v == "to" && Lexicon.isBase(c.lw(j + 1))) {
                c.edit(i, j + 2, Topic.VERB_FORM, tip, tipId) { remove(j) }
            } else {
                // "Will works at a bank" / "May goes": nama orang di awal kalimat, bukan modal.
                val maybeName = i == 0 && (m == "will" || m == "may")
                val bp = Lexicon.baseFromPast(v)
                val b3 = Lexicon.baseFromThird(v)
                val fix: String? = when {
                    maybeName && j == i + 1 -> null
                    b3 != null -> b3
                    bp != null && bp != v && v !in Lexicon.AMBIGUOUS_PAST -> bp
                    else -> null
                }
                if (fix != null) c.edit(j, j + 1, Topic.VERB_FORM, tip, tipId) { set(j, fix) }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ to + kata kerja

    private val TO_VERBS = setOf(
        "want", "wants", "wanted", "need", "needs", "needed", "like", "likes", "liked", "love", "loves", "loved", "hate",
        "hates", "hated", "try", "tries", "tried", "plan", "plans", "planned", "decide", "decides", "decided", "prefer",
        "prefers", "preferred", "hope", "hopes", "hoped", "forget", "forgot", "remember", "remembered", "start", "starts",
        "started", "begin", "begins", "began", "learn", "learns", "learned", "wish", "wishes", "wished", "able", "ready",
        "going", "used", "have", "has", "had"
    )
    /** Setelah ini "to + V-ing" hampir pasti salah (bukan "look forward to going" atau "used to going"). */
    private val TO_NO_ING = setOf("used", "have", "has", "had", "going", "ready", "able")
    private val WANT_FAMILY = setOf(
        "want", "wants", "wanted", "need", "needs", "needed", "hope", "hopes", "hoped", "plan", "plans", "planned", "decide", "decides", "decided"
    )
    private val SAFE_INF = setOf(
        "go", "eat", "drink", "buy", "see", "meet", "come", "speak", "learn", "travel", "stay", "sit", "stand", "leave", "pay",
        "send", "sell", "win", "swim", "sing", "fly", "drive", "ride", "write", "read", "listen", "talk", "walk", "call", "ask",
        "tell", "take", "make", "know", "find", "give", "bring", "visit", "wait"
    )
    private val WANT_PREV_OK = setOf("don't", "doesn't", "didn't", "not", "really", "just", "also", "always", "never", "still")

    private fun toInfinitive(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val w = c.lw(i)
            if (w in TO_VERBS && c.lw(i + 1) == "to") {
                val v = c.lw(i + 2)
                val bp = Lexicon.baseFromPast(v)
                val b3 = Lexicon.baseFromThird(v)
                val bi = if (w in TO_NO_ING) null else Lexicon.baseFromIng(v)
                val fix: String? = when {
                    bp != null && bp != v && v !in Lexicon.AMBIGUOUS_PAST -> bp
                    b3 != null -> b3
                    bi != null -> bi
                    else -> null
                }
                if (fix != null) {
                    c.edit(
                        i + 2, i + 3, Topic.VERB_FORM,
                        "After \"to\" use the base verb: \"I want to go\".",
                        "Setelah \"to\" pakai kata kerja dasar: \"I want to go\"."
                    ) { set(i + 2, fix) }
                }
            } else if (w in WANT_FAMILY && c.lw(i + 1) in SAFE_INF && (c.lw(i - 1) in SUBJ || c.lw(i - 1) in WANT_PREV_OK)) {
                c.edit(
                    i, i + 2, Topic.MISSING_WORDS,
                    "Use \"to\" before the verb after want/need/hope: \"I want to go\".",
                    "Pakai \"to\" sebelum kata kerja setelah want/need/hope: \"I want to go\"."
                ) { insert(i + 1, "to") }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ have + participle

    private val PP_SAFE_BASE = setOf(
        "eat", "go", "see", "take", "give", "write", "speak", "know", "begin", "forget", "choose", "sing", "swim", "throw", "hide", "wear", "do"
    )

    private fun havePast(c: Ctx) {
        var i = 1
        while (i + 1 < c.size) {
            val h = c.lw(i)
            val p = c.lw(i - 1)
            val subjOk = (h == "have" && (p == "i" || p == "you" || p == "we" || p == "they")) ||
                (h == "has" && p in SUB3) || (h == "had" && p in SUBJ)
            if (subjOk) {
                val v = c.lw(i + 1)
                val bp = Lexicon.baseFromPast(v)
                val fix: String? = when {
                    v in PP_SAFE_BASE -> Lexicon.participle(v)
                    bp != null && bp != v && Lexicon.isIrregular(bp) -> Lexicon.participle(bp)
                    else -> null
                }
                if (fix != null && fix != v) {
                    c.edit(
                        i, i + 2, Topic.VERB_FORM,
                        "After have/has/had use the past participle: \"I have eaten\", \"she has gone\".",
                        "Setelah have/has/had pakai past participle: \"I have eaten\", \"she has gone\"."
                    ) { set(i + 1, fix) }
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ a / an

    private val A_SOUND = listOf(
        "univers", "unit", "union", "unif", "uniq", "unic", "unil", "unan", "use", "usu", "util", "eu", "one", "once", "ubiq", "uku"
    )
    private val AN_H = listOf("hour", "honest", "honor", "honour", "heir")
    private val AN_OPTIONAL = setOf("herb", "herbs", "herbal", "historic", "historical", "hotel")
    private val ART_SKIP_NEXT = setOf(
        "and", "or", "as", "at", "if", "on", "in", "of", "is", "it", "its", "up", "us", "am", "are", "also", "all", "any", "i", "a", "an", "the", "to"
    )

    private fun needsAn(w: String): Boolean {
        if (AN_H.any { w.startsWith(it) }) return true
        if (w[0] !in "aeiou") return false
        return A_SOUND.none { w.startsWith(it) }
    }

    private fun articles(c: Ctx) {
        for (i in 0 until c.size - 1) {
            val a = c.lw(i)
            if (a != "a" && a != "an") continue
            val raw = c.t[i + 1]
            val n = c.lw(i + 1)
            if (n.length < 2 || !n.all { it.isLetter() || it == '-' } || n in ART_SKIP_NEXT) continue
            if (raw.drop(1).any { it.isUpperCase() }) continue // singkatan: "a USB", "an SMS"
            if (n[1] == '-' || n in AN_OPTIONAL) continue // "an X-ray", "an herb" / "an historic" (sah di sebagian ragam)
            val an = needsAn(n)
            if (a == "a" && an) {
                c.edit(
                    i, i + 2, Topic.ARTICLES,
                    "Use \"an\" before a vowel sound: \"an apple\", \"an hour\".",
                    "Pakai \"an\" sebelum bunyi vokal: \"an apple\", \"an hour\"."
                ) { set(i, "an") }
            } else if (a == "an" && !an) {
                c.edit(
                    i, i + 2, Topic.ARTICLES,
                    "Use \"a\" before a consonant sound: \"a book\", \"a university\".",
                    "Pakai \"a\" sebelum bunyi konsonan: \"a book\", \"a university\"."
                ) { set(i, "a") }
            }
        }
    }

    // ------------------------------------------------------------------ perbandingan

    private val COMPARATIVES = setOf(
        "better", "faster", "bigger", "smaller", "easier", "cheaper", "harder", "worse", "higher", "lower", "larger", "older",
        "younger", "stronger", "happier", "cleaner", "nicer", "taller", "closer", "earlier", "longer", "shorter", "safer",
        "healthier", "simpler", "richer", "quicker", "slower"
    )
    private val SUPERLATIVES = setOf(
        "best", "biggest", "easiest", "fastest", "cheapest", "smallest", "oldest", "tallest", "highest", "largest", "strongest", "worst"
    )
    private val MORE_THAN = mapOf(
        "easy" to "easier", "cheap" to "cheaper", "fast" to "faster", "big" to "bigger", "small" to "smaller", "old" to "older",
        "young" to "younger", "tall" to "taller", "high" to "higher", "low" to "lower", "large" to "larger", "strong" to "stronger",
        "clean" to "cleaner", "close" to "closer", "early" to "earlier", "long" to "longer", "short" to "shorter", "quick" to "quicker",
        "slow" to "slower", "cold" to "colder", "hot" to "hotter", "warm" to "warmer", "cool" to "cooler", "nice" to "nicer"
    )

    private fun comparatives(c: Ctx) {
        var i = 0
        while (i + 1 < c.size) {
            val w = c.lw(i)
            val n = c.lw(i + 1)
            if (w == "more" && n in COMPARATIVES) {
                c.edit(
                    i, i + 2, Topic.COMPARISON,
                    "\"$n\" already means \"more\": don't add \"more\".",
                    "\"$n\" sudah berarti \"lebih\": jangan tambah \"more\"."
                ) { remove(i) }
            } else if (w == "most" && n in SUPERLATIVES) {
                c.edit(
                    i, i + 2, Topic.COMPARISON,
                    "\"$n\" already means \"most\": don't add \"most\".",
                    "\"$n\" sudah berarti \"paling\": jangan tambah \"most\"."
                ) { remove(i) }
            } else if (w == "more" && c.lw(i + 2) == "than" && n in MORE_THAN) {
                val comp = MORE_THAN.getValue(n)
                c.edit(
                    i, i + 2, Topic.COMPARISON,
                    "Short adjectives add -er instead of \"more\": \"easier than\".",
                    "Kata sifat pendek memakai -er, bukan \"more\": \"easier than\"."
                ) {
                    set(i, comp)
                    remove(i + 1)
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------ kata benda

    private val NOUN_FIX = mapOf(
        "informations" to "information", "advices" to "advice", "furnitures" to "furniture", "equipments" to "equipment",
        "knowledges" to "knowledge", "homeworks" to "homework", "luggages" to "luggage", "baggages" to "baggage",
        "musics" to "music", "moneys" to "money", "childs" to "children", "peoples" to "people", "datas" to "data",
        "womans" to "woman"
    )
    private val NOUN_TIP_EN = mapOf(
        "childs" to "The plural of \"child\" is \"children\".",
        "peoples" to "\"People\" is already plural.",
        "datas" to "\"Data\" is already plural.",
        "womans" to "Use \"woman\" (one) or \"women\" (many)."
    )

    private fun plurals(c: Ctx) {
        for (i in 0 until c.size) {
            val w = c.lw(i)
            val fix = NOUN_FIX[w] ?: continue
            val en = NOUN_TIP_EN[w] ?: "\"$fix\" is uncountable: it has no plural (no -s)."
            val id = NOUN_TIP_EN[w]?.let { "Bentuk yang benar: \"$fix\"." } ?: "\"$fix\" tidak bisa dihitung: tanpa bentuk jamak (tanpa -s)."
            c.edit(i, i + 1, Topic.NOUNS, en, id) { set(i, fix) }
        }
    }

    // ------------------------------------------------------------------ kata depan

    private val WEEKDAYS = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val DAY_PARTS = setOf("morning", "afternoon", "evening")
    private val DETERMINERS = setOf("the", "a", "an", "my", "your", "his", "her", "our", "their", "this", "that")
    private val DURATION_UNITS = setOf("years", "year", "months", "month", "weeks", "week", "days", "day", "hours", "hour")
    /** Frasa waktu berakhir di sini: akhir kalimat, tanda baca, subjek baru, atau kata sambung. */
    private fun phraseEnds(c: Ctx, k: Int): Boolean {
        val w = c.lw(k)
        return w.isEmpty() || !w[0].isLetterOrDigit() || w in SUBJ || w in setOf("and", "but", "because", "so", "after", "before", "when", "then")
    }

    private val LISTEN_OBJ = setOf("music", "radio", "song", "songs", "podcast", "podcasts", "him", "her", "them", "me", "us", "you", "it", "this", "that")

    private fun prepositions(c: Ctx) {
        var i = 0
        while (i < c.size) {
            val w = c.lw(i)
            val n = c.lw(i + 1)
            when {
                w in setOf("discuss", "discusses", "discussed", "discussing") && n == "about" ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "\"Discuss\" needs no \"about\": \"discuss the plan\".", "\"Discuss\" tidak pakai \"about\": \"discuss the plan\".") { remove(i + 1) }

                w in setOf("marry", "marries", "marrying") && n == "with" ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "\"Marry\" needs no \"with\": \"marry her\".", "\"Marry\" tidak pakai \"with\": \"marry her\".") { remove(i + 1) }

                w in setOf("explain", "explains", "explained", "explaining") && n in setOf("me", "him", "her", "us", "them") &&
                    c.lw(i + 2) in setOf("how", "what", "why", "when", "where", "who", "if", "whether") ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"explain to me\": \"explain\" needs \"to\" before the person.", "Katakan \"explain to me\": \"explain\" butuh \"to\" sebelum orangnya.") { insert(i + 1, "to") }

                w in setOf("listen", "listens", "listened", "listening") && n in LISTEN_OBJ ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"listen to\": \"listen to music\".", "Katakan \"listen to\": \"listen to music\".") { insert(i + 1, "to") }

                w in setOf("enter", "enters", "entered", "entering") && n == "to" && c.lw(i + 2) in DETERMINERS ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "\"Enter\" needs no \"to\": \"enter the room\".", "\"Enter\" tidak pakai \"to\": \"enter the room\".") { remove(i + 1) }

                w in setOf("depend", "depends", "depended", "depending") && n == "of" ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"depend on\".", "Katakan \"depend on\".") { set(i + 1, "on") }

                w == "different" && n == "with" ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"different from\".", "Katakan \"different from\".") { set(i + 1, "from") }

                w == "interested" && n == "about" ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"interested in\".", "Katakan \"interested in\".") { set(i + 1, "in") }

                w == "since" && (isNumber(n) || n == "a" || n == "an") && c.lw(i + 2) in DURATION_UNITS && c.lw(i + 3) != "ago" ->
                    c.edit(i, i + 1, Topic.PREPOSITIONS, "Use \"for\" with a length of time: \"for two years\". \"Since\" needs a starting point (since 2020).", "Pakai \"for\" untuk lamanya waktu: \"for two years\". \"Since\" butuh titik awal (since 2020).") { set(i, "for") }

                (w == "in" || w == "at") && n in WEEKDAYS ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Use \"on\" with days: \"on Monday\".", "Pakai \"on\" untuk hari: \"on Monday\".") { set(i, "on") }

                (w == "on" || w == "at") && n in DAY_PARTS && phraseEnds(c, i + 2) ->
                    c.edit(i, i + 2, Topic.PREPOSITIONS, "Say \"in the morning/afternoon/evening\".", "Katakan \"in the morning/afternoon/evening\".") {
                        set(i, "in")
                        insert(i + 1, "the")
                    }

                (w == "on" || w == "at") && n == "the" && c.lw(i + 2) in DAY_PARTS && phraseEnds(c, i + 3) ->
                    c.edit(i, i + 3, Topic.PREPOSITIONS, "Say \"in the morning/afternoon/evening\".", "Katakan \"in the morning/afternoon/evening\".") { set(i, "in") }

                w == "in" && n in DAY_PARTS && phraseEnds(c, i + 2) ->
                    c.edit(i, i + 2, Topic.ARTICLES, "Say \"in the morning/afternoon/evening\".", "Katakan \"in the morning/afternoon/evening\".") { insert(i + 1, "the") }

                w == "forward" && n == "to" && c.lw(i - 1) in setOf("look", "looking", "looks", "looked") && c.lw(i + 2) in LOOK_FORWARD_BASE ->
                    c.edit(i + 2, i + 3, Topic.VERB_FORM, "After \"look forward to\" use verb + -ing: \"look forward to seeing you\".", "Setelah \"look forward to\" pakai kata kerja + -ing: \"look forward to seeing you\".") { set(i + 2, Lexicon.ing(c.lw(i + 2))) }
            }
            i++
        }
    }

    private val LOOK_FORWARD_BASE = setOf("see", "meet", "hear", "go", "come", "speak", "eat", "travel", "learn", "talk")

    // ------------------------------------------------------------------ urutan kata pertanyaan

    private val WH = setOf("what", "where", "why", "how", "when")
    private val BE_FORMS = setOf("are", "is", "am", "were", "was")
    private val Q_VERBS = setOf("know", "think", "feel", "like", "want", "need", "do", "live", "go", "come", "work", "study", "eat", "say", "mean")

    private fun whOrder(c: Ctx) {
        if (c.size < 3 || c.size > 9 || c.lw(0) !in WH) return
        if ((1 until c.size - 1).any { !c.t[it][0].isLetterOrDigit() }) return // tanda baca di tengah: bukan satu pertanyaan sederhana
        val s = c.lw(1)
        val w2 = c.lw(2)
        val restHasAux = (2 until c.size).any { c.lw(it) in AUX_ALL && it != 2 }

        // "how old you" -> "how old are you"
        if (c.lw(0) == "how" && c.lw(1) == "old" && c.size == 3 && c.lw(2) in SUBJ) {
            val sub = c.lw(2)
            c.edit(0, 3, Topic.MISSING_WORDS, "Questions need am/is/are: \"How old are you?\"", "Pertanyaan butuh am/is/are: \"How old are you?\"") { insert(2, beFor(sub)) }
            return
        }
        // "what your name" -> "what is your name"
        if (c.lw(0) == "what" && c.size == 3 && s == "your" && w2 == "name") {
            c.edit(0, 3, Topic.MISSING_WORDS, "Questions need is/are: \"What is your name?\"", "Pertanyaan butuh is/are: \"What is your name?\"") { insert(1, "is") }
            return
        }
        if (s !in SUBJ) return
        // "where you from" -> "where are you from"
        if (c.lw(0) == "where" && w2 == "from" && c.size == 3) {
            val be = beFor(s)
            c.edit(0, 3, Topic.MISSING_WORDS, "Questions need am/is/are: \"Where are you from?\"", "Pertanyaan butuh am/is/are: \"Where are you from?\"") { insert(1, be) }
            return
        }
        if (restHasAux) return
        // "what you doing" -> "what are you doing"
        if (Lexicon.baseFromIng(w2) != null && c.size <= 5) {
            val be = beFor(s)
            c.edit(0, 3, Topic.MISSING_WORDS, "Questions need am/is/are: \"What are you doing?\"", "Pertanyaan butuh am/is/are: \"What are you doing?\"") { insert(1, be) }
            return
        }
        // "what you are doing" -> "what are you doing"
        if (w2 in BE_FORMS) {
            c.edit(0, 3, Topic.WORD_ORDER, "In a question the verb comes first: \"What are you doing?\"", "Dalam pertanyaan kata kerja bantu di depan: \"What are you doing?\"") {
                t[1] = w2
                t[2] = s
            }
            return
        }
        // "how you know that" -> "how do you know that"
        if (w2 in Q_VERBS && s != "i" && c.lw(0) != "when") {
            val aux = if (s in SUB3) "does" else "do"
            c.edit(0, 3, Topic.WORD_ORDER, "Questions need do/does: \"Where do you live?\"", "Pertanyaan butuh do/does: \"Where do you live?\"") { insert(1, aux) }
        }
    }

    // ------------------------------------------------------------------ "me want"

    private val ME_VERBS = setOf("want", "need", "like", "love", "go", "think", "know", "have", "will", "can", "am", "was")
    private val ME_NOUNS = setOf(
        "name", "friend", "mother", "father", "house", "family", "job", "school", "brother", "sister", "mom", "dad", "teacher",
        "phone", "car", "hobby", "birthday", "favorite", "favourite"
    )

    private fun pronounMe(c: Ctx) {
        for (i in 0 until c.size - 1) {
            if (c.lw(i) != "me" || !clauseStart(c, i)) continue
            val n = c.lw(i + 1)
            if (n in ME_VERBS) {
                c.edit(i, i + 2, Topic.PRONOUNS, "As the subject use \"I\", not \"me\": \"I want to go\".", "Sebagai subjek pakai \"I\", bukan \"me\": \"I want to go\".") { set(i, "I") }
            } else if (n in ME_NOUNS) {
                c.edit(i, i + 2, Topic.PRONOUNS, "Use \"my\" before a noun: \"my name\".", "Pakai \"my\" sebelum kata benda: \"my name\".") { set(i, "my") }
            }
        }
    }

    // ------------------------------------------------------------------ rapikan hasil

    private fun polish(t: List<String>, original: String): String {
        val words = t.map { w ->
            val l = w.lowercase(Locale.ROOT).replace('’', '\'')
            if (l == "i" || l.startsWith("i'")) "I" + w.drop(1) else w
        }.toMutableList()
        val sb = StringBuilder()
        for ((idx, w) in words.withIndex()) {
            val punct = w.length == 1 && w[0] in ".,!?;:"
            if (idx > 0 && !punct) sb.append(' ')
            sb.append(w)
        }
        var out = sb.toString()
        val first = out.indexOfFirst { it.isLetter() }
        if (first >= 0) out = out.substring(0, first) + out[first].uppercaseChar() + out.substring(first + 1)
        if (out.last() !in ".!?") {
            val head = words.firstOrNull { it[0].isLetter() }?.lowercase(Locale.ROOT).orEmpty()
            out += if (head in Q_STARTERS) "?" else "."
        }
        return out
    }

    private val Q_STARTERS = setOf(
        "what", "where", "when", "why", "who", "how", "which", "do", "does", "did", "can", "could", "will", "would", "should",
        "is", "are", "am", "was", "were", "have", "has"
    )
}

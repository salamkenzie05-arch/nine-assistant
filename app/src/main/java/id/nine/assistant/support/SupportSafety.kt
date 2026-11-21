package id.nine.assistant.support

import id.nine.assistant.core.AppLang

/**
 * Pemeriksa balasan AI lokal untuk mode curhat. Model bahasa bisa salah: mendiagnosis, menyebut obat, meremehkan
 * perasaan, atau menulis perintah "CMD:". Semua itu ditolak (null) dan Nine memakai balasan mesin aturan.
 * Hasil lolos dirapikan: tanpa markdown, panjang dibatasi, berhenti di batas kalimat. Kotlin murni.
 */
object SupportSafety {

    const val MAX_CHARS = 480
    private const val MIN_CHARS = 15

    private val CMD = Regex("""cmd\s*:""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)

    private val DIAGNOSIS = Regex(
        """\b(?:kamu|anda|dirimu) (?:sedang |mungkin |sepertinya |kemungkinan |tampaknya )?(?:mengalami|menderita|terkena|mengidap|punya|memiliki|kena) (?:gangguan|depresi|anxiety|kecemasan|bipolar|adhd|ptsd|ocd|skizofrenia|sakit jiwa|penyakit mental)|\bkamu (?:depresi|bipolar|gila|sakit jiwa)\b|\bini (?:adalah |itu |merupakan )?(?:tanda|gejala)(?:nya)? (?:depresi|gangguan|bipolar|kecemasan)|\byou (?:have|suffer from|are suffering from|are experiencing|might have|may have|probably have|likely have) (?:a |an )?(?:clinical )?(?:depression|anxiety|bipolar|adhd|ptsd|ocd|schizophrenia|mental illness|disorder)|\byou are (?:depressed|bipolar|crazy|mentally ill)\b|\b(?:sounds|looks|seems) like (?:you have |you re |you are )?(?:clinical )?depression\b""",
        RegexOption.IGNORE_CASE
    )

    private val MEDICATION = Regex(
        """\b(?:obat|dosis|mg|antidepresan|antidepressants?|sertraline|fluoxetine|xanax|alprazolam|diazepam|valium|benzodiazepines?|medications?|medicine|prescri\w+|resep|suplemen|supplements?|vitamin|pil)\b""",
        RegexOption.IGNORE_CASE
    )

    private val SELF_HARM = Regex(
        """\b(?:bunuh diri|suicid\w*|menyakiti diri|melukai diri|self[- ]?harm|mengakhiri hidup|kill yourself|hurt yourself|end your life|take your (?:own )?life)\b""",
        RegexOption.IGNORE_CASE
    )

    private val DISMISSIVE = Regex(
        """\b(?:lebay|alay|move on saja|move on aja|move on|pikirkan yang positif|berpikir positif|think positive|just be positive|get over it|cheer up|jangan sedih|tidak usah sedih|tidak perlu sedih|don'?t be sad|stop being sad|just relax|setidaknya|at least|bersyukurlah|be grateful)\b|semua(?:nya)? akan baik baik saja|everything (?:will|is going to) be (?:fine|okay|ok|alright)""",
        RegexOption.IGNORE_CASE
    )

    private val CLAIMS_PRO = Regex(
        """\b(?:saya|aku) (?:adalah |seorang |ini )*(?:psikolog|konselor|terapis|psikiater|dokter)\b|\bas your (?:therapist|psychologist|counselor|counsellor|doctor)\b|\bi am (?:a |your )?(?:licensed |professional )?(?:therapist|psychologist|counselor|counsellor|psychiatrist|doctor)\b""",
        RegexOption.IGNORE_CASE
    )

    private val EN_WORDS = Regex("""\b(?:the|you|and|is|are|it|that|this|your|with|for|have|what|how|feel|feeling|sounds|like)\b""", RegexOption.IGNORE_CASE)
    private val ID_WORDS = Regex("""\b(?:yang|dan|aku|kamu|tidak|adalah|ini|itu|dengan|untuk|perasaan|merasa|terasa|ada|bisa|apa|kedengarannya)\b""", RegexOption.IGNORE_CASE)

    private val LIST_LINE = Regex("""(?m)^\s*(?:\d+[.)]|[-*•])\s+""")
    private val SPEAKER = Regex("""^\s*(?:nine|assistant|asisten|jawaban|answer|reply|balasan)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val MARKDOWN = Regex("""[*_`#>~]+""")
    private val SPACES = Regex("""[ \t]+""")
    private val BLANKS = Regex("""\n{2,}""")

    /** Balasan yang aman dan rapi, atau null bila harus ditolak. */
    fun vet(raw: String?, lang: AppLang): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (CMD.containsMatchIn(s) || URL.containsMatchIn(s)) return null
        if (LIST_LINE.findAll(s).count() >= 2) return null

        s = MARKDOWN.replace(s, "")
        s = SPEAKER.replace(s, "")
        s = SPACES.replace(s, " ")
        s = BLANKS.replace(s, "\n").trim()
        if (s.length < MIN_CHARS) return null

        if (DIAGNOSIS.containsMatchIn(s)) return null
        if (MEDICATION.containsMatchIn(s)) return null
        if (SELF_HARM.containsMatchIn(s)) return null
        if (DISMISSIVE.containsMatchIn(s)) return null
        if (CLAIMS_PRO.containsMatchIn(s)) return null

        val en = EN_WORDS.findAll(s).count()
        val id = ID_WORDS.findAll(s).count()
        if (lang == AppLang.ID && en >= 3 && en > id) return null
        if (lang == AppLang.EN && id >= 3 && id > en) return null

        // Tepat satu pertanyaan: potong setelah pertanyaan pertama bila ada lebih dari satu.
        if (s.count { it == '?' } >= 2) s = s.substring(0, s.indexOf('?') + 1)

        if (s.length > MAX_CHARS) {
            val cut = s.substring(0, MAX_CHARS)
            val end = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            if (end < MIN_CHARS) return null
            s = cut.substring(0, end + 1)
        }
        if (s.length < MIN_CHARS) return null
        return s
    }
}

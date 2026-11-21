package id.nine.assistant.support

/**
 * Perapi teks untuk mode curhat: huruf kecil, tanda baca dibuang, bahasa gaul dan singkatan umum diseragamkan
 * ("gak/nggak/ga" -> "tidak", "pengen/pgn" -> "ingin", "gue/gw/saya" -> "aku", huruf berulang diringkas). Hanya untuk
 * dicocokkan dengan pola; teks asli pengguna tidak diubah. Kotlin murni.
 */
object SupportText {

    private val REPEAT = Regex("""(\p{L})\1{2,}""")
    private val NOT_LETTER = Regex("""[^\p{L}\p{N}' ]+""")
    private val SPACES = Regex("""\s+""")

    private val SLANG: List<Pair<Regex, String>> = listOf(
        Regex("""\b(?:gak|ga|nggak|ngga|enggak|engga|gk|ndak|nda|tak|tdk)\b""") to "tidak",
        Regex("""\b(?:pengen|pengin|pingin|kepingin|kepengen|pgn|pgen)\b""") to "ingin",
        Regex("""\b(?:gue|gw|gua|ane|saya|sy|ak)\b""") to "aku",
        Regex("""\b(?:bgt|bngt|banget)\b""") to "banget",
        Regex("""\b(?:udah|udh|sdh|sudah)\b""") to "sudah",
        Regex("""\b(?:dgn|dg)\b""") to "dengan",
        Regex("""\b(?:yg)\b""") to "yang",
        Regex("""\b(?:lg)\b""") to "lagi",
        Regex("""\b(?:aja|aj)\b""") to "saja",
        Regex("""\b(?:temen|teman|tmn)(\w*)""") to "teman$1",
        Regex("""\bwanna\b""") to "want to",
        Regex("""\bgonna\b""") to "going to",
        Regex("""\bi'm\b""") to "i am",
        Regex("""\bdon't\b""") to "do not",
        Regex("""\bdon’t\b""") to "do not",
        Regex("""\bcan't\b""") to "cannot",
        Regex("""\bcan’t\b""") to "cannot",
        Regex("""\bi've\b""") to "i have",
        Regex("""\bi'd\b""") to "i would",
        Regex("""\bwon't\b""") to "will not",
        Regex("""\bit's\b""") to "it is",
        Regex("""\bthat's\b""") to "that is",
        Regex("""\bi'll\b""") to "i will",
        Regex("""\bisn't\b""") to "is not",
        Regex("""\bwasn't\b""") to "was not",
        Regex("""\bdidn't\b""") to "did not",
        Regex("""\bdoesn't\b""") to "does not"
    )

    /** Huruf kecil, huruf berulang diringkas, tanda baca jadi spasi, bahasa gaul diseragamkan. */
    fun norm(raw: String): String {
        var t = raw.lowercase().replace('’', '\'')
        t = REPEAT.replace(t) { it.groupValues[1] }
        for ((re, to) in SLANG) t = re.replace(t, to)
        t = NOT_LETTER.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    /** Jumlah kata (untuk menilai ucapan pendek/panjang). */
    fun words(raw: String): Int = raw.trim().split(SPACES).count { it.isNotEmpty() }
}

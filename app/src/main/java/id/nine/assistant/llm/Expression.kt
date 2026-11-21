package id.nine.assistant.llm

import id.nine.assistant.core.Mood

/**
 * Ekspresi wajah Nine untuk jawaban AI. AI diminta mengawali jawaban dengan satu tag seperti "[happy]"; tag itu dibuang dari
 * teks (tidak tampil dan tidak diucapkan) dan menjadi ekspresi wajah. Bila AI lupa memberi tag (model kecil sering lupa),
 * [guess] menebak dari isi jawaban.
 */
object ExpressionTag {

    /** Teks tanpa tag di awalnya, dan ekspresi dari tag itu (null bila tidak ada tag yang dikenal). */
    class Parsed(val mood: Mood?, val text: String)

    private val NAMES: Map<String, Mood> = buildMap {
        fun add(m: Mood, vararg names: String) = names.forEach { put(it, m) }
        add(Mood.NEUTRAL, "neutral", "netral", "calm", "tenang", "biasa")
        add(Mood.HAPPY, "happy", "senang", "gembira", "bahagia", "smile", "smiling", "tersenyum")
        add(Mood.LOVE, "love", "loving", "cinta", "sayang", "heart")
        add(Mood.SURPRISED, "surprised", "surprise", "terkejut", "kaget", "kagum", "amazed")
        add(Mood.CONFUSED, "confused", "bingung", "heran")
        add(Mood.SAD, "sad", "sedih", "sympathetic", "simpati")
        add(Mood.ANNOYED, "annoyed", "angry", "kesal", "jengkel")
        add(Mood.SLEEPY, "sleepy", "tired", "mengantuk", "ngantuk", "lelah")
        add(Mood.CURIOUS, "curious", "penasaran", "thinking", "berpikir")
        add(Mood.SCARED, "scared", "afraid", "takut")
        add(Mood.WINK, "wink", "winking", "kedip", "mengedip", "playful", "jenaka")
        add(Mood.LAUGH, "laugh", "laughing", "tertawa", "tawa", "funny", "lucu")
    }

    /** Nama yang diminta di instruksi AI (yang lain hanya sinonim yang kita terima dengan toleran). */
    val PROMPT_NAMES = listOf("neutral", "happy", "love", "surprised", "confused", "sad", "annoyed", "sleepy", "curious", "scared", "wink", "laugh")

    private const val INNER = """(?:(?:mood|ekspresi|expression)\s*[:=]\s*)?([A-Za-z]{2,12})"""
    private val reLead = Regex("""^\s*\[\s*$INNER\s*]\s*""", RegexOption.IGNORE_CASE)
    private val reAny = Regex("""\[\s*$INNER\s*]""", RegexOption.IGNORE_CASE)

    /** Cukup panjang untuk tag terpanjang ("[expression: surprised]") dengan sedikit ruang. */
    private const val MAX_TAG = 28

    private fun lookup(name: String): Mood? = NAMES[name.trim().lowercase()]

    /** Pisahkan tag di awal [raw] (bila ada dan dikenal). Teks yang tidak diawali tag dikembalikan apa adanya. */
    fun parse(raw: String): Parsed {
        val m = reLead.find(raw) ?: return Parsed(null, raw)
        val mood = lookup(m.groupValues[1]) ?: return Parsed(null, raw)
        return Parsed(mood, raw.substring(m.range.last + 1))
    }

    /**
     * Teks yang baru datang sebagian masih mungkin awal sebuah tag ("[ha"): tahan dulu sebelum ditampilkan atau diucapkan.
     * False bila tag sudah lengkap (lihat [parse]), atau teks jelas bukan tag.
     */
    fun maybePartial(raw: String): Boolean {
        val t = raw.trimStart()
        if (!t.startsWith("[")) return false
        if (t.indexOf(']') >= 0) return false
        return t.length <= MAX_TAG
    }

    /** Buang semua tag ekspresi yang dikenal di mana pun dalam teks (AI kadang menyisipkannya di tengah jawaban). */
    fun strip(text: String): String {
        if (text.indexOf('[') < 0) return text
        return reAny.replace(text) { if (lookup(it.groupValues[1]) != null) "" else it.value }
    }

    /** Teks yang boleh tampil/diucapkan dari keluaran mentah [raw]: tanpa tag di awal; kosong selama tag masih datang sebagian. */
    fun visible(raw: String): String = if (maybePartial(raw)) "" else strip(parse(raw).text)

    private val reLaugh = Regex("""\b(?:(?:ha|he|hi|wk){2,}h?|lol|lucu banget)\b""")
    private val reSad = Regex("""\b(?:maaf|sayangnya|sorry|apologies|unfortunately|sadly|tidak bisa|turut berduka|ikut sedih|can't|cannot)\b""")
    private val reSurprised = Regex("""\b(?:wow|astaga|waduh|whoa|mengejutkan|menakjubkan|ternyata|surprising|amazing)\b""")
    private val reConfused = Regex("""\b(?:kurang jelas|tidak mengerti|tidak paham|bisa diperjelas|not sure what you mean|could you clarify|didn't understand)\b""")
    private val reSleepy = Regex("""\b(?:selamat tidur|good night|sleep well|mengantuk|ngantuk|sleepy)\b""")
    private val reHappy = Regex("""\b(?:selamat|hebat|bagus|luar biasa|senang|great|wonderful|excellent|splendid|congratulations|delighted|glad|keren|mantap)\b""")

    /** Tebakan ekspresi dari isi jawaban, dipakai saat AI tidak memberi tag. */
    fun guess(text: String): Mood {
        val low = text.lowercase()
        return when {
            reLaugh.containsMatchIn(low) -> Mood.LAUGH
            reSad.containsMatchIn(low) -> Mood.SAD
            reConfused.containsMatchIn(low) -> Mood.CONFUSED
            reSurprised.containsMatchIn(low) -> Mood.SURPRISED
            reSleepy.containsMatchIn(low) -> Mood.SLEEPY
            reHappy.containsMatchIn(low) -> Mood.HAPPY
            low.trimEnd().endsWith("?") -> Mood.CURIOUS
            else -> Mood.NEUTRAL
        }
    }
}

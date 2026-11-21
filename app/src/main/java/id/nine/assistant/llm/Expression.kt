package id.nine.assistant.llm

import id.nine.assistant.core.Mood

/**
 * Tag di awal jawaban AI. Satu tag ekspresi seperti "[happy]" menjadi ekspresi wajah Nine; nol sampai tiga tag ingatan
 * seperti "[ingat: Nama pengguna Budi]" adalah hal tetap tentang pengguna yang disimpan otomatis ke Memori. Semua tag
 * dibuang dari teks (tidak tampil dan tidak diucapkan). Bila AI lupa memberi tag ekspresi (model kecil sering lupa),
 * [guess] menebak dari isi jawaban.
 */
object ExpressionTag {

    /** Teks tanpa tag di awalnya, ekspresi dari tag (null bila tidak ada), dan hal-hal yang diminta diingat. */
    class Parsed(val mood: Mood?, val text: String, val facts: List<String> = emptyList())

    private val NAMES: Map<String, Mood> = buildMap {
        fun add(m: Mood, vararg names: String) = names.forEach { put(it, m) }
        add(Mood.NEUTRAL, "neutral", "netral", "calm", "tenang", "biasa", "serious", "formal", "polite", "sopan", "whisper", "whispers", "whispering", "softly", "gentle", "pause", "pauses", "short", "long")
        add(Mood.HAPPY, "happy", "senang", "gembira", "bahagia", "smile", "smiling", "tersenyum", "cheerful", "excited", "enthusiastic", "pleased", "delighted", "proud", "warm", "warmly", "friendly", "ramah", "confident", "positive")
        add(Mood.LOVE, "love", "loving", "cinta", "sayang", "heart")
        add(Mood.SURPRISED, "surprised", "surprise", "terkejut", "kaget", "kagum", "amazed", "gasps", "gasp", "shocked", "astonished")
        add(Mood.CONFUSED, "confused", "bingung", "heran")
        add(Mood.SAD, "sad", "sedih", "sympathetic", "simpati", "apologetic", "apologetically", "apologizing", "apologising", "sorry", "regretful", "sighs", "sigh", "concerned", "worried", "empathetic", "gloomy", "somber", "maaf")
        add(Mood.ANNOYED, "annoyed", "angry", "kesal", "jengkel")
        add(Mood.SLEEPY, "sleepy", "tired", "mengantuk", "ngantuk", "lelah")
        add(Mood.CURIOUS, "curious", "penasaran", "thinking", "berpikir", "thoughtful", "pensive", "hmm", "pondering", "interested")
        add(Mood.SCARED, "scared", "afraid", "takut")
        add(Mood.WINK, "wink", "winking", "kedip", "mengedip", "playful", "playfully", "jenaka", "teasing", "witty")
        add(Mood.LAUGH, "laugh", "laughing", "laughs", "giggles", "giggle", "chuckles", "chuckle", "tertawa", "tawa", "funny", "lucu")
    }

    /** Nama yang diminta di instruksi AI (yang lain hanya sinonim yang kita terima dengan toleran). */
    val PROMPT_NAMES = listOf("neutral", "happy", "love", "surprised", "confused", "sad", "annoyed", "sleepy", "curious", "scared", "wink", "laugh")

    /** Isi tag: satu sampai tiga kata ("apologetic", "sighs softly", "sad, apologetic"), boleh diawali "mood:". */
    private const val INNER = """(?:(?:mood|ekspresi|expression)\s*[:=]\s*)?([A-Za-z]{2,16}(?:[ ,\-]+[A-Za-z]{2,16}){0,2})"""
    private const val MEM_WORD = """(?:ingat|remember|memori|memory|mem)"""
    private val reLead = Regex("""^\s*\[\s*$INNER\s*](?!\()\s*""", RegexOption.IGNORE_CASE)
    private val reAny = Regex("""(?<![\w\])])\[\s*$INNER\s*](?![(\[])""", RegexOption.IGNORE_CASE)
    private val reMemLead = Regex("""^\s*\[\s*$MEM_WORD\s*[:=]\s*([^\]\n]{3,200}?)\s*]\s*""", RegexOption.IGNORE_CASE)
    private val reMemAny = Regex("""\[\s*$MEM_WORD\s*[:=]\s*[^\]\n]{3,200}?\s*]""", RegexOption.IGNORE_CASE)
    private val reMemStart = Regex("""^\[\s*$MEM_WORD\b""", RegexOption.IGNORE_CASE)

    /** Cukup panjang untuk tag terpanjang ("[expression: surprised]") dengan sedikit ruang. */
    private const val MAX_TAG = 44

    /** Tag ingatan boleh panjang (satu kalimat fakta). */
    private const val MAX_MEM_TAG = 240

    /** Paling banyak ini tag ingatan yang dipakai dari satu jawaban. */
    const val MAX_FACTS = 3

    private val reWordSep = Regex("""[ ,\-]+""")

    /** Ekspresi dari isi tag: kata pertama yang dikenal ("sighs softly" → sighs). Null bila tak ada yang dikenal. */
    private fun lookup(name: String): Mood? {
        val low = name.trim().lowercase()
        NAMES[low]?.let { return it }
        for (w in low.split(reWordSep)) NAMES[w]?.let { return it }
        return null
    }

    /**
     * Tag bergaya TTS yang tidak kita kenal ("[apologetic]", "[whispers]") tetap dibuang dari teks: model sering menulisnya
     * walau tidak diminta. Hanya tag huruf kecil (tulisan biasa seperti "[Catatan]" atau "[A]" dibiarkan).
     */
    private fun looksLikeTag(name: String): Boolean = name == name.lowercase() && !reWordSep.containsMatchIn(name.trim())

    /**
     * Pisahkan tag di awal [raw] (ekspresi dan ingatan, urutan bebas). Teks yang tidak diawali tag yang dikenal dikembalikan
     * apa adanya.
     */
    fun parse(raw: String): Parsed {
        var rest = raw
        var mood: Mood? = null
        var consumed = false
        val facts = ArrayList<String>()
        var guard = 0
        while (guard++ < 6) {
            val mem = reMemLead.find(rest)
            if (mem != null) {
                val f = mem.groupValues[1].trim()
                if (f.isNotEmpty() && facts.size < MAX_FACTS && f !in facts) facts.add(f)
                rest = rest.substring(mem.range.last + 1)
                continue
            }
            val m = reLead.find(rest) ?: break
            val name = m.groupValues[1]
            val md = lookup(name)
            if (md == null && !looksLikeTag(name)) break
            if (mood == null) mood = md
            consumed = true
            rest = rest.substring(m.range.last + 1)
        }
        return if (!consumed && facts.isEmpty()) Parsed(null, raw) else Parsed(mood, rest, facts)
    }

    /**
     * Teks yang baru datang sebagian masih mungkin awal sebuah tag ("[ha", "[ingat: Nama"): tahan dulu sebelum ditampilkan
     * atau diucapkan. False bila tag sudah lengkap (lihat [parse]), atau teks jelas bukan tag.
     */
    fun maybePartial(raw: String): Boolean {
        val t = parse(raw).text.trimStart()
        if (!t.startsWith("[")) return false
        if (t.indexOf(']') >= 0) return false
        if (t.length <= MAX_TAG) return true
        return t.length <= MAX_MEM_TAG && reMemStart.containsMatchIn(t)
    }

    /** Buang semua tag ekspresi dan ingatan yang dikenal di mana pun dalam teks (AI kadang menyisipkannya di tengah jawaban). */
    fun strip(text: String): String {
        if (text.indexOf('[') < 0) return text
        val noMem = reMemAny.replace(text, "")
        var changed = noMem != text
        val out = reAny.replace(noMem) {
            val name = it.groupValues[1]
            if (lookup(name) != null || looksLikeTag(name)) {
                changed = true
                ""
            } else {
                it.value
            }
        }
        // Tag di tengah kalimat meninggalkan dua spasi / spasi sebelum tanda baca.
        return if (changed) out.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""[ \t]+([,.!?;:])"""), "$1") else out
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

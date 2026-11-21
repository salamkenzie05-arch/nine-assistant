package id.nine.assistant.speech

/** Pria atau wanita: dipakai untuk suara Gemini (nama suara resmi berlabel gender) dan suara cadangan bawaan HP. */
enum class VoiceGender(val key: String, val geminiVoice: String) {
    // Nama dan gender suara mengikuti daftar resmi Google Cloud (Gemini-TTS): Kore = female, Charon = male.
    FEMALE("female", "Kore"),
    MALE("male", "Charon");

    companion object {
        fun of(key: String?): VoiceGender = values().firstOrNull { it.key == key } ?: FEMALE
    }
}

/** Satu suara mesin TTS bawaan HP, disederhanakan dari android.speech.tts.Voice agar bisa diuji tanpa Android. */
data class VoiceInfo(val name: String, val lang: String, val network: Boolean, val quality: Int)

/**
 * Memilih suara cadangan (mesin TTS bawaan HP) untuk [VoiceGender]. Android tidak mengekspos gender suara, jadi dipakai
 * petunjuk dari namanya; bila tidak ada, suara berbeda untuk tiap gender dengan nada digeser sedikit.
 */
object VoicePicker {
    class Pick(val name: String?, val pitch: Float, val byName: Boolean)

    private val femaleWords = Regex("""female|wanita|perempuan|woman|\bfem\b""", RegexOption.IGNORE_CASE)
    private val maleWords = Regex("""(?<![a-z])male|pria|laki|(?<![a-z])man\b""", RegexOption.IGNORE_CASE)

    /** Suara Google Indonesia: ida/idc terdengar wanita, idb/idd pria (pengamatan umum, bukan data resmi). */
    private val googleId = mapOf("ida" to VoiceGender.FEMALE, "idb" to VoiceGender.MALE, "idc" to VoiceGender.FEMALE, "idd" to VoiceGender.MALE)

    fun pitchFor(g: VoiceGender): Float = if (g == VoiceGender.MALE) 0.9f else 1.05f

    private fun hinted(v: VoiceInfo): VoiceGender? {
        val n = v.name
        if (femaleWords.containsMatchIn(n)) return VoiceGender.FEMALE
        if (maleWords.containsMatchIn(n)) return VoiceGender.MALE
        val tag = Regex("""-x-([a-z]{3})""").find(n.lowercase())?.groupValues?.get(1)
        return googleId[tag]
    }

    fun pick(voices: List<VoiceInfo>, lang: String, gender: VoiceGender): Pick {
        val same = voices.filter { it.lang.equals(lang, ignoreCase = true) }
        if (same.isEmpty()) return Pick(null, pitchFor(gender), false)
        // Suara di perangkat lebih dulu (tanpa internet, lebih cepat), lalu mutu tertinggi; nama sebagai pemutus agar stabil.
        val ordered = same.sortedWith(compareBy<VoiceInfo> { it.network }.thenByDescending { it.quality }.thenBy { it.name })
        ordered.firstOrNull { hinted(it) == gender }?.let { return Pick(it.name, 1.0f, true) }
        // Tanpa petunjuk: suara pertama untuk wanita, suara kedua (bila ada) untuk pria, dengan geseran nada.
        val idx = if (gender == VoiceGender.MALE && ordered.size > 1) 1 else 0
        return Pick(ordered[idx].name, pitchFor(gender), false)
    }
}

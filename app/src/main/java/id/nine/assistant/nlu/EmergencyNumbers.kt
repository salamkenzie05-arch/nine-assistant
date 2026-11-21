package id.nine.assistant.nlu

/**
 * Nomor polisi menurut negara tempat pengguna berada, dan pengenalan "telepon polisi". Hanya logika murni; yang
 * menelepon (dan selalu meminta konfirmasi dulu) ada di SkillExecutor.
 */
object EmergencyNumbers {

    /** Nomor darurat umum: tersambung di hampir semua negara lewat jaringan seluler (GSM), jadi dipakai bila negara tak dikenal. */
    const val FALLBACK = "112"

    private val POLICE: Map<String, String> = mapOf(
        // Asia Tenggara dan sekitarnya
        "ID" to "110", "MY" to "999", "SG" to "999", "BN" to "993", "TH" to "191", "VN" to "113", "PH" to "911",
        "KH" to "117", "LA" to "191", "MM" to "199", "TL" to "112",
        // Asia lain
        "JP" to "110", "CN" to "110", "TW" to "110", "HK" to "999", "KR" to "112", "IN" to "112", "PK" to "15",
        "BD" to "999", "LK" to "119", "NP" to "100", "SA" to "999", "AE" to "999", "QA" to "999", "IL" to "100",
        "TR" to "155",
        // Oseania
        "AU" to "000", "NZ" to "111",
        // Amerika
        "US" to "911", "CA" to "911", "MX" to "911", "AR" to "911", "BR" to "190", "CL" to "133", "CO" to "123",
        "PE" to "105",
        // Eropa dan Afrika (selebihnya 112)
        "GB" to "999", "IE" to "999", "DE" to "110", "FR" to "17", "RU" to "102", "UA" to "102", "ZA" to "10111",
        "EG" to "122", "MA" to "19", "KE" to "999"
    )

    /** Nomor lain yang tak boleh dihubungi tanpa konfirmasi, bila diucapkan langsung ("telepon 112"). */
    private val EXTRA_EMERGENCY = setOf("112", "911", "999", "000", "110", "111", "113", "119", "118", "115", "117")

    private val ALL: Set<String> = POLICE.values.toSet() + EXTRA_EMERGENCY

    /** Nomor polisi untuk kode negara [iso] (dua huruf, besar/kecil bebas); [FALLBACK] bila tidak dikenal. */
    fun police(iso: String?): String {
        val k = iso?.trim()?.uppercase().orEmpty()
        return POLICE[k] ?: FALLBACK
    }

    /** True bila [number] (angka saja, spasi/strip boleh) adalah nomor darurat: menelepon nomor ini selalu lewat konfirmasi. */
    fun isEmergencyNumber(number: String): Boolean {
        val t = number.trim()
        if (t.isEmpty() || !t.all { it.isDigit() || it == ' ' || it == '-' }) return false
        return t.filter { it.isDigit() } in ALL
    }

    private val policeWords = Regex(
        """^(?:(?:the|ke|pak|bapak|kantor|pos|nomor|number|emergency|darurat)\s+)*(?:polisi|police|polis|cops?|polsek)(?:\s+(?:terdekat|darurat|sekarang|segera|dong|ya|now|please|nearby))*$""",
        RegexOption.IGNORE_CASE
    )

    /** "polisi", "the police", "kantor polisi", "polisi terdekat", ...: target panggilan yang berarti polisi, bukan kontak. */
    fun isPoliceWord(target: String): Boolean {
        val t = target.lowercase().replace(Regex("""[^\p{L}\d\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        return t.isNotEmpty() && policeWords.matches(t)
    }

    /**
     * Negara tempat pengguna berada. Urutan: jaringan seluler yang sedang dipakai (paling mutakhir, tanpa izin), lalu lokasi
     * terakhir bila masih baru, lalu negara SIM, lalu bahasa/wilayah HP. Kode yang bukan dua huruf diabaikan.
     */
    fun pickCountry(network: String?, location: String?, sim: String?, locale: String?): String? {
        for (c in listOf(network, location, sim, locale)) {
            val k = c?.trim().orEmpty()
            if (k.length == 2 && k.all { it.isLetter() }) return k.uppercase()
        }
        return null
    }
}

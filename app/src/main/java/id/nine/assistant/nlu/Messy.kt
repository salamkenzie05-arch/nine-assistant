package id.nine.assistant.nlu

/**
 * Pemahaman kalimat berantakan: urutan kata terbalik ("youtube buka dong", "wifi nyala"), salah ketik kecil
 * ("bluetoth matikan"), dan kata pengisi. Hanya menghasilkan KANDIDAT kalimat yang lebih rapi; parser biasa yang
 * menilai kandidat itu. Dipakai hanya bila kalimat asli tidak dikenali. Kotlin murni.
 */
object Messy {

    private val filler = setOf(
        "dong", "deh", "nih", "sih", "kok", "tuh", "lho", "loh", "ya", "yah", "aja", "saja", "gitu", "banget", "dulu",
        "donk", "plis", "pls", "please", "tolong", "coba", "mohon", "bro", "bang", "kak", "gan", "bos", "nine", "nain",
        "hey", "hei", "hai", "halo", "oke", "ok", "eh", "hmm", "anu", "gini", "bisa", "kamu", "kau", "lu", "lo", "anda",
        "nyah", "cepat", "cepetan", "buruan", "sekarang", "now", "the", "a", "my", "ke", "dong,"
    )

    /** Kata kerja perintah (bentuk baku yang dikenali parser). */
    private val verbs = setOf(
        "buka", "tutup", "nyalakan", "matikan", "putar", "setel", "pasang", "hapus", "tambah", "tambahkan", "catat",
        "telepon", "hubungi", "kirim", "ingatkan", "hitung", "navigasi", "arahkan", "cari", "carikan", "tampilkan",
        "lihat", "bacakan", "nyanyikan", "jeda", "lanjutkan", "ganti", "ubah", "buatkan", "atur", "naikkan", "turunkan",
        "kecilkan", "besarkan", "open", "close", "turn", "set", "call", "send", "remind", "calculate", "search", "show",
        "delete", "add", "remove", "play", "pause"
    )

    private val verbAlias = mapOf(
        "bukain" to "buka", "bukak" to "buka", "bukakan" to "buka", "nyalain" to "nyalakan", "nyalakan" to "nyalakan",
        "hidupin" to "nyalakan", "hidupkan" to "nyalakan", "aktifin" to "nyalakan", "aktifkan" to "nyalakan",
        "matiin" to "matikan", "matikan" to "matikan", "nonaktifin" to "matikan", "nonaktifkan" to "matikan",
        "setelin" to "putar", "puterin" to "putar", "mainin" to "putar", "pasangin" to "pasang", "setting" to "atur",
        "hapusin" to "hapus", "ingetin" to "ingatkan", "ngingetin" to "ingatkan", "catetin" to "catat", "hitungin" to "hitung",
        "kirimin" to "kirim", "telfon" to "telepon", "telpon" to "telepon", "nelpon" to "telepon", "nelfon" to "telepon",
        "bikin" to "buatkan", "bikinin" to "buatkan", "buatin" to "buatkan", "carikan" to "cari", "cariin" to "cari",
        "nyanyiin" to "nyanyikan", "tutupin" to "tutup", "naikin" to "naikkan", "turunin" to "turunkan", "gedein" to "besarkan",
        "kecilin" to "kecilkan"
    )

    private val onWords = setOf("nyala", "menyala", "hidup", "on", "aktif", "enable")
    private val offWords = setOf("mati", "off", "nonaktif", "disable")

    /** Kosakata untuk memperbaiki salah ketik (kata benda/perintah umum). */
    private val vocab: Set<String> = verbs + setOf(
        "alarm", "timer", "senter", "volume", "wifi", "bluetooth", "hotspot", "youtube", "spotify", "whatsapp", "maps",
        "kalender", "musik", "lagu", "tugas", "pengeluaran", "pemasukan", "kamera", "pengaturan", "catatan", "kontak",
        "instagram", "telegram", "chrome", "google", "gmail", "tiktok", "twitter", "facebook", "netflix", "pesawat",
        "baterai", "jadwal", "pengingat", "gombal", "pantun", "bijak"
    )

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun repair(t: String): String {
        verbAlias[t]?.let { return it }
        if (t.length < 5 || t in vocab || t.any { it.isDigit() }) return t
        val limit = if (t.length >= 8) 2 else 1
        val near = vocab.filter { kotlin.math.abs(it.length - t.length) <= limit && lev(t, it) <= limit }
        return if (near.size == 1) near.first() else t
    }

    /** Ucapan bercerita ("tadi aku buka youtube") bukan perintah. */
    private val narrative = Regex("""\b(?:aku|saya|gue|gw|i|kita|dia|dia|mereka)\s+(?:tadi|kemarin|sudah|udah|baru|lagi|pernah|lupa|belum|tidak|nggak|gak|ga|was|had|didn't)\b|\b(?:tadi|kemarin|semalam|yesterday)\b""", RegexOption.IGNORE_CASE)

    /** Kandidat kalimat yang lebih rapi untuk [input] (kosong bila sebaiknya tidak ditebak). Urutan = prioritas. */
    fun candidates(input: String): List<String> {
        val text = input.lowercase().trim()
        if (text.isEmpty() || narrative.containsMatchIn(text)) return emptyList()
        val rawTokens = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (rawTokens.size < 2 || rawTokens.size > 9) return emptyList()
        val tokens = rawTokens.map { repair(it.trim('.', '!', '?', ';')) }
        val core = tokens.filter { it !in filler }
        if (core.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        fun add(l: List<String>) {
            val s = l.joinToString(" ")
            if (s.isNotBlank() && s != text) out.add(s)
        }
        // 1) hanya salah ketik/kata pengisi dibereskan
        add(core)
        // 2) "wifi nyala" / "senter mati": kata keadaan di akhir menjadi kata kerja di depan
        val last = core.last()
        if (core.size >= 2 && last in onWords) add(listOf("nyalakan") + core.dropLast(1))
        if (core.size >= 2 && last in offWords) add(listOf("matikan") + core.dropLast(1))
        // 3) kata kerja di belakang/tengah dipindah ke depan ("youtube buka", "alarm jam 6 pasang")
        for ((i, t) in core.withIndex()) {
            if (i > 0 && t in verbs) add(listOf(t) + core.filterIndexed { j, _ -> j != i })
        }
        return out.toList().take(5)
    }
}

package id.nine.assistant.core

/**
 * Mengenali kalimat yang berisi hal tetap tentang pengguna ("nama saya Budi", "aku suka nasi goreng",
 * "my sister lives in Bandung") dari ucapan biasa, supaya Nine bisa mengingatnya otomatis.
 *
 * Sengaja hati-hati: hanya kalimat pernyataan berpola jelas, bukan pertanyaan, dan bukan hal sementara
 * (hari ini, besok, sedang, ...). Kotlin murni agar mudah diuji.
 */
object FactExtractor {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val filler = rx("""^(?:oh|ya|eh|oiya|oh\s+ya|btw|by\s+the\s+way|jadi|so|well|oke|ok|okay|nah|dan|and|ngomong-ngomong|sebenarnya|actually)[,\s]+""")

    /** Hal sementara atau jadwal: ditangani alarm, kalender, atau tugas, bukan ingatan tetap. */
    private val transient = rx("""\b(?:hari\s+ini|besok|lusa|nanti|tadi|barusan|sekarang|malam\s+ini|pagi\s+ini|sore\s+ini|minggu\s+ini|kemarin|sedang|lagi|today|tomorrow|tonight|right\s+now|just|later|yesterday|this\s+(?:morning|afternoon|evening|week)|currently)\b""")

    private val patterns = listOf(
        // --- Indonesia
        rx("""^(?:nama\s+(?:aku|saya|gue|gua|ku)\s+\S+.*|namaku\s+\S+.*|panggil\s+(?:aku|saya|gue)\s+\S+.*|(?:aku|saya)\s+dipanggil\s+\S+.*)$"""),
        rx("""^(?:aku|saya|gue|gua)\s+(?:(?:juga|sangat|paling|memang|tidak|nggak|gak|ga|belum|bukan)\s+)*(?:suka|senang|cinta|benci|tinggal|berasal|lahir|bekerja|kerja|kuliah|sekolah|memiliki|alergi|vegetarian|vegan|berumur|berusia|umur|usia|hobi|penggemar|pendukung|mahasiswa|pelajar|karyawan|dokter|guru|perawat|programmer|pengusaha|seorang)\b.*"""),
        rx("""^(?:aku|saya|gue|gua)\s+punya\s+(?:\w+\s+)?(?:kucing|anjing|istri|suami|anak|adik|kakak|saudara|motor|mobil|usaha|toko|bisnis|rumah|hewan)\b.*"""),
        rx("""^(?:aku|saya|gue|gua)\s+(?:selalu|biasanya|biasa|sering|jarang|tidak\s+pernah|nggak\s+pernah|gak\s+pernah)\s+\w+.*"""),
        rx("""^(?:umur|usia|hobi|ulang\s+tahun|ultah|tanggal\s+lahir|rumah|alamat|kantor|pekerjaan|kerjaan|makanan\s+favorit|minuman\s+favorit|warna\s+favorit|lagu\s+favorit|film\s+favorit|band\s+favorit|olahraga\s+favorit|tim\s+favorit|nomor\s+hp|istri|suami|anak|ibu|bapak|ayah|papa|mama|adik|kakak|abang|saudara|teman|sahabat|pacar|bos|kucing|anjing|peliharaan)(?:ku|\s+(?:aku|saya|gue|gua))\s+\S+.*"""),
        // --- English
        rx("""^(?:my\s+name\s+is|call\s+me|you\s+can\s+call\s+me|i\s+go\s+by)\s+\S+.*"""),
        rx("""^i(?:'m|’m|\s+am)\s+(?:(?:a|an)\s+(?!bit\b|little\b|lot\b)\w+|from\s+\S+|allergic\s+to\s+\S+|vegetarian|vegan|\d{1,3}(?:\s+years?\s+old)?|(?:left|right)[- ]handed|married|single|retired|diabetic|lactose\s+intolerant|gluten[- ]free).*"""),
        rx("""^i\s+(?:(?:really|also|always|usually|never|still)\s+)?(?:live|work|study|studied|speak|play|grew\s+up|was\s+born|own|love|like|hate|dislike|prefer|enjoy|drive|ride|can't\s+(?:eat|stand|drink)|cannot\s+(?:eat|stand|drink)|don't\s+(?:eat|drink|like|smoke)|do\s+not\s+(?:eat|drink|like)|am\s+allergic)\b.*"""),
        rx("""^i\s+have\s+(?:a|an|two|three|four|\d+)\s+(?:\w+\s+)?(?:cats?|dogs?|wife|husband|sons?|daughters?|brothers?|sisters?|kids?|child|children|pets?|cars?|bikes?|motorcycles?)\b.*"""),
        rx("""^my\s+(?:wife|husband|son|daughter|mother|mom|father|dad|brother|sister|friend|best\s+friend|boss|girlfriend|boyfriend|partner|cat|dog|pet|birthday|home|house|address|job|office|hobby|age|favou?rite\s+\w+)\b\s*(?:is|are|was|lives?|works?|loves?|likes?|hates?)?\s*\S+.*""")
    )

    /** Kalimat-kalimat dalam [utterance] yang layak diingat (sudah dibersihkan dari kata sambung di depan). */
    fun extract(utterance: String): List<String> {
        val out = ArrayList<String>()
        for (raw in utterance.split(Regex("(?<=[.!?])\\s+|\\n"))) {
            var s = raw.trim()
            if (s.isEmpty() || s.endsWith("?")) continue
            // buang kata sambung di depan (bisa berlapis: "oh ya, jadi ...")
            while (true) {
                val m = filler.find(s) ?: break
                s = s.substring(m.range.last + 1).trim()
            }
            val words = s.split(Regex("\\s+")).size
            if (words < 3 || words > 30) continue
            if (transient.containsMatchIn(s)) continue
            if (patterns.any { it.containsMatchIn(s) }) out.add(s)
        }
        return out
    }
}

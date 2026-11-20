package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

enum class FinType { INCOME, EXPENSE }

enum class FinPeriod { TODAY, YESTERDAY, WEEK, MONTH, LAST_MONTH, YEAR, ALL }

/** Satu catatan keuangan. [time] = epoch ms, [amount] dalam Rupiah utuh, [cat] = kunci kategori ([FinanceCats]). */
data class Txn(val id: Long, val time: Long, val type: FinType, val amount: Long, val cat: String, val note: String)

/** Kategori catatan keuangan: kunci stabil (disimpan) dan nama tampilan per bahasa. */
object FinanceCats {
    private val names: Map<String, Pair<String, String>> = mapOf(
        "food" to ("Makanan & minuman" to "Food & drink"),
        "transport" to ("Transportasi" to "Transport"),
        "bills" to ("Tagihan & pulsa" to "Bills & phone"),
        "shopping" to ("Belanja" to "Shopping"),
        "health" to ("Kesehatan" to "Health"),
        "fun" to ("Hiburan" to "Entertainment"),
        "edu" to ("Pendidikan" to "Education"),
        "social" to ("Sosial & hadiah" to "Social & gifts"),
        "other" to ("Lainnya" to "Other"),
        "salary" to ("Gaji" to "Salary"),
        "bonus" to ("Bonus & komisi" to "Bonus & commission"),
        "business" to ("Usaha & freelance" to "Business & freelance"),
        "gift" to ("Pemberian" to "Gifts received"),
        "other_in" to ("Lainnya" to "Other")
    )

    fun name(key: String, lang: AppLang): String = names[key]?.let { lang.t(it.first, it.second) } ?: key

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val expenseCats: List<Pair<String, Regex>> = listOf(
        "food" to rx("""\b(?:makan|minum|kopi|teh|bakso|nasi|ayam|sate|mie|mi|bubur|snack|jajan|resto|restoran|warung|cafe|kafe|boba|susu|roti|sarapan|lunch|dinner|breakfast|coffee|food|meal|kuliner|gorengan|martabak|seblak|pizza|burger|jus|es)\b"""),
        "transport" to rx("""\b(?:bensin|pertalite|pertamax|solar|parkir|tol|ojek|ojol|grab|gojek|taksi|taxi|bus|kereta|krl|mrt|lrt|angkot|fuel|gas|uber|servis|service|tiket\s+(?:kereta|pesawat|bus)|transport|bbm)\b"""),
        "bills" to rx("""\b(?:listrik|pln|air|pdam|wifi|internet|pulsa|paket\s+data|token|bpjs|cicilan|kredit|asuransi|sewa|kos|kontrakan|pajak|tagihan|bill|rent|netflix|spotify|langganan|subscription|indihome|kuota)\b"""),
        "shopping" to rx("""\b(?:belanja|baju|sepatu|tas|elektronik|hp|gadget|shopee|tokopedia|lazada|groceries|supermarket|indomaret|alfamart|minimarket|pasar|kosmetik|skincare|shopping|celana|jaket|laptop)\b"""),
        "health" to rx("""\b(?:obat|dokter|rumah\s+sakit|apotek|vitamin|klinik|medicine|doctor|medical|gigi|checkup|periksa)\b"""),
        "fun" to rx("""\b(?:nonton|bioskop|game|konser|film|wisata|liburan|hotel|movie|cinema|vacation|hobi|karaoke|main)\b"""),
        "edu" to rx("""\b(?:buku|kursus|sekolah|kuliah|spp|les|course|tuition|school|ujian|seminar|pelatihan)\b"""),
        "social" to rx("""\b(?:donasi|sedekah|zakat|infak|amal|hadiah|kado|traktir|donation|gift|charity|arisan|sumbangan|angpao)\b""")
    )

    private val incomeCats: List<Pair<String, Regex>> = listOf(
        "salary" to rx("""\b(?:gaji|gajian|salary|payroll|upah)\b"""),
        "bonus" to rx("""\b(?:bonus|thr|komisi|insentif|commission)\b"""),
        "business" to rx("""\b(?:jual|jualan|usaha|dagang|untung|profit|freelance|proyek|project|honor|order|klien|client)\b"""),
        "gift" to rx("""\b(?:dikasih|diberi|diberikan|hadiah|gift|angpao|transfer\s+dari|ibu|ayah|bapak|orang\s+tua|teman)\b""")
    )

    fun infer(text: String, type: FinType): String {
        val list = if (type == FinType.EXPENSE) expenseCats else incomeCats
        for ((k, r) in list) if (r.containsMatchIn(text)) return k
        return if (type == FinType.EXPENSE) "other" else "other_in"
    }
}

/** Hitung periode & ringkasan catatan keuangan (Kotlin murni). */
object FinanceMath {

    /** Rentang [dari, sampai) dalam epoch ms untuk [period]. */
    fun range(period: FinPeriod, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (period) {
            FinPeriod.TODAY -> ms(today) to ms(today.plusDays(1))
            FinPeriod.YESTERDAY -> ms(today.minusDays(1)) to ms(today)
            FinPeriod.WEEK -> {
                val start = today.with(DayOfWeek.MONDAY)
                ms(start) to ms(start.plusDays(7))
            }
            FinPeriod.MONTH -> {
                val start = today.withDayOfMonth(1)
                ms(start) to ms(start.plusMonths(1))
            }
            FinPeriod.LAST_MONTH -> {
                val start = today.withDayOfMonth(1).minusMonths(1)
                ms(start) to ms(start.plusMonths(1))
            }
            FinPeriod.YEAR -> {
                val start = today.withDayOfYear(1)
                ms(start) to ms(start.plusYears(1))
            }
            FinPeriod.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
        }
    }

    fun label(period: FinPeriod, lang: AppLang): String = when (period) {
        FinPeriod.TODAY -> lang.t("hari ini", "today")
        FinPeriod.YESTERDAY -> lang.t("kemarin", "yesterday")
        FinPeriod.WEEK -> lang.t("minggu ini", "this week")
        FinPeriod.MONTH -> lang.t("bulan ini", "this month")
        FinPeriod.LAST_MONTH -> lang.t("bulan lalu", "last month")
        FinPeriod.YEAR -> lang.t("tahun ini", "this year")
        FinPeriod.ALL -> lang.t("secara keseluruhan", "overall")
    }

    data class Summary(
        val income: Long,
        val expense: Long,
        val count: Int,
        /** Pengeluaran per kategori, terbesar dulu. */
        val expenseByCat: List<Pair<String, Long>>,
        val incomeByCat: List<Pair<String, Long>>
    ) {
        val balance: Long get() = income - expense
    }

    fun summarize(all: List<Txn>, from: Long, to: Long): Summary {
        val sel = all.filter { it.time >= from && it.time < to }
        val inc = sel.filter { it.type == FinType.INCOME }
        val exp = sel.filter { it.type == FinType.EXPENSE }
        fun by(l: List<Txn>) = l.groupBy { it.cat }.map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
        return Summary(inc.sumOf { it.amount }, exp.sumOf { it.amount }, sel.size, by(exp), by(inc))
    }

    /** "Rp 25.000" (ID) / "Rp 25,000" (EN). */
    fun money(v: Long, lang: AppLang): String {
        val sep = if (lang == AppLang.ID) '.' else ','
        val neg = v < 0
        val s = Math.abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(sep)
            sb.append(c)
        }
        return (if (neg) "-" else "") + "Rp " + sb
    }
}

/**
 * Pemahaman perintah keuangan: mencatat pengeluaran/pemasukan dari kalimat bebas
 * ("tadi beli kopi 18 ribu", "gajian 5 juta", "catat pengeluaran 50rb bensin"), laporan, riwayat, undo.
 * Hanya Rupiah. Kotlin murni.
 */
object FinanceParser {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    data class Amount(val value: Long, val start: Int, val end: Int)

    private val reAmount = rx(
        """(?:\b(?:rp|idr)\.?\s*)?(\d{1,3}(?:[.,]\d{3})+|\d+(?:[.,]\d+)?)\s*(juta|jt|ribu|rb|miliar|milyar|thousand|million|billion|k|perak)?(?![\p{L}\d])"""
    )

    private val reBefore = rx("""(?:jam|pukul|tanggal|tgl|tahun|nomor|no|level|senar|ke-|hari\s+ke|lantai|kelas|bab|halaman|hal)\.?\s*$""")

    private fun scaleOf(w: String?): Long = when (w?.lowercase()) {
        "juta", "jt", "million" -> 1_000_000L
        "ribu", "rb", "k", "thousand" -> 1_000L
        "miliar", "milyar", "billion" -> 1_000_000_000L
        else -> 1L
    }

    private fun numberOf(s: String): Double? {
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        val thousands = Regex("""^\d{1,3}(?:[.,]\d{3})+$""").matches(s)
        return when {
            thousands -> s.replace(".", "").replace(",", "").toDoubleOrNull()
            dots + commas == 0 -> s.toDoubleOrNull()
            dots + commas == 1 -> s.replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    /** Jumlah uang pertama yang masuk akal dalam [text] (sudah huruf kecil, kata bilangan sudah jadi digit). */
    fun findAmount(text: String): Amount? {
        data class Hit(val value: Long, val scale: Long, val start: Int, val end: Int)
        val hits = ArrayList<Hit>()
        for (m in reAmount.findAll(text)) {
            val n = numberOf(m.groupValues[1]) ?: continue
            val sc = scaleOf(m.groupValues[2].ifEmpty { null })
            val hasRp = m.value.trimStart().let { it.startsWith("rp", true) || it.startsWith("idr", true) }
            val v = Math.round(n * sc)
            if (v <= 0L || v > 999_999_999_999L) continue
            val before = text.substring(0, m.range.first)
            if (sc == 1L && !hasRp) {
                // angka telanjang: bukan uang bila kecil atau terdengar seperti jam/tanggal/nomor
                if (v < 100L) continue
                if (reBefore.containsMatchIn(before)) continue
            }
            hits += Hit(v, sc, m.range.first, m.range.last + 1)
        }
        if (hits.isEmpty()) return null
        var cur = hits[0]
        var total = cur.value
        var end = cur.end
        // "2 juta 500 ribu"
        for (h in hits.drop(1)) {
            val gap = text.substring(end, h.start)
            if (gap.isBlank() && cur.scale >= 1000L && h.scale >= 1000L && h.scale < cur.scale) {
                total += h.value
                end = h.end
                cur = h
            } else break
        }
        return Amount(total, hits[0].start, end)
    }

    // ------------------------------------------------------------------ pola

    private const val EXP = """(?:beli|membeli|bayar|membayar|belanja|jajan|habis|keluar|keluarin|ngeluarin|spent|spend|paid|pay|bought|buy|isi|isiin|topup|top\s*up|donasi|sedekah|zakat|traktir|menghabiskan|ongkos|nyicil|cicil)"""
    private const val INC = """(?:dapat|dapet|mendapat|mendapatkan|terima(?!\s*kasih)|menerima|dikasih|diberi|diberikan|gajian|gaji|bonus|untung|earned|earn|received|receive|dibayar|komisi|upah|honor|cuan|penghasilan|salary|got\s+paid)"""
    private const val NOUN_EXP = """(?:pengeluaran|expense|expenses|spending|uang\s+keluar|belanjaan)"""
    private const val NOUN_INC = """(?:pemasukan|pendapatan|income|uang\s+masuk|penerimaan)"""

    private val reLead = rx(
        """^(?:(?:catat(?:kan)?|tambah(?:kan)?|masukkan|masukin|input|simpan|record|log|add|save|note)\s+)?""" +
            """(?:(?:sebuah|satu|transaksi|baru)\s+)*""" +
            """(?:(?:tadi|barusan|baru|sudah|udah|lagi|pagi|siang|sore|malam|hari\s+ini|kemarin|aku|saya|gue|gw|ku|kita|i|just|today|yesterday|tuh)\s+)*"""
    )
    private val reStartExp = rx("""^(?:$EXP|$NOUN_EXP)\b""")
    private val reStartInc = rx("""^(?:$INC|$NOUN_INC)\b""")
    private val reWant = rx("""\b(?:mau|ingin|pengen|pingin|akan|rencana|nanti|kalau|kalo|jika|seandainya|sebaiknya|should|want|wanna|going\s+to|will|would|planning|bagaimana|gimana|bisakah|apakah)\b""")
    private val reQuestion = rx("""^(?:apa|apakah|kenapa|mengapa|siapa|kapan|dimana|di\s+mana|bagaimana|gimana|berapa|why|how|what|who|when|where|is|are|do|does|can|could)\b""")
    private val reFiller = rx("""\b(?:untuk|buat|dari|di|ke|for|on|from|dengan|seharga|sebesar|senilai|sebanyak|total|uang|duit|rp|sebagai|karena|sama|oleh|itu|ini|tadi|barusan|ya|dong|deh|nih|yaa|aja|saja|sebanyak|about|of|at|the|a|an|my)\b""")

    /** Rencana ("mau beli kopi 18 ribu besok", "akan bayar listrik") bukan catatan transaksi. Diperiksa pada ucapan asli. */
    private val rePlan = rx("""\b(?:mau|ingin|pengen|pingin|akan|rencana|rencananya|want\s+to|wanna|going\s+to|will|plan\s+to|planning\s+to|would\s+like\s+to)\s+(?:$EXP|$INC)\b""")

    fun isPlan(original: String): Boolean = rePlan.containsMatchIn(original.lowercase())

    private fun start(text: String, re: Regex): Boolean {
        val lead = reLead.find(text) ?: return false
        return re.containsMatchIn(text.substring(lead.range.last + 1).trimStart())
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim(' ', ',', '.', ':', ';', '-')

    private fun parseAdd(t: String): Command? {
        if (reQuestion.containsMatchIn(t) || reWant.containsMatchIn(t)) return null
        val amt = findAmount(t) ?: return null
        val rest0 = reLead.find(t)?.let { t.substring(it.range.last + 1).trimStart() } ?: t
        val type = when {
            start(t, reStartInc) -> FinType.INCOME
            start(t, reStartExp) -> FinType.EXPENSE
            else -> return null
        }
        // sisa kalimat: buang kata kerja pembuka dan angkanya
        val withoutAmt = (t.substring(0, amt.start) + " " + t.substring(amt.end)).let { clean(it) }
        val body = reLead.find(withoutAmt)?.let { withoutAmt.substring(it.range.last + 1).trimStart() } ?: withoutAmt
        val verbless = Regex("""^(?:$EXP|$INC|$NOUN_EXP|$NOUN_INC)\b\s*""", RegexOption.IGNORE_CASE).replaceFirst(body, "")
        val note = clean(reFiller.replace(verbless, " "))
        if (rest0.isEmpty()) return null
        val cat = FinanceCats.infer(t, type)
        return Command.FinanceAdd(type, amt.value, cat, note.replaceFirstChar { it.uppercase() })
    }

    private val reOpen = rx("""^(?:buka|bukakan|open|tampilkan|lihat|show)\s+(?:layar\s+|halaman\s+|aplikasi\s+|menu\s+|my\s+)?(?:catatan\s+keuangan|keuangan(?:ku|\s+saya)?|buku\s+kas|pencatatan\s+keuangan|finance|expense\s+tracker|money\s+tracker|finances)$""")
    private val reUndo = rx("""^(?:hapus|batalkan|undo|cancel|delete|remove|buang)\s+(?:transaksi|catatan\s+keuangan|pengeluaran|pemasukan|entri\s+keuangan|transaction|expense|income)(?:\s+yang)?\s+(?:terakhir|tadi|barusan|itu|last|latest|previous)$|^undo(?:\s+transaksi)?$""")
    private val reClear = rx("""^(?:hapus|bersihkan|reset|kosongkan|clear|delete)\s+(?:semua\s+|seluruh\s+|all\s+)(?:catatan\s+keuangan|data\s+keuangan|transaksi|riwayat\s+keuangan|pengeluaran\s+dan\s+pemasukan|my\s+transactions|finance(?:\s+data)?)$""")

    private val reFinWord = rx("""\b(?:pengeluaran|pemasukan|pendapatan|keuangan|saldo|expenses?|income|spending|balance|uang\s+(?:masuk|keluar)|cashflow|arus\s+kas|sisa\s+uang|transaksi|transactions?|penerimaan)(?:ku|nya)?\b""")
    private val reShowCue = rx("""\b(?:berapa|total|lihat|tampilkan|cek|rekap|laporan|ringkasan|summary|report|how\s+much|show|check|apa|bacakan|baca|jumlah|daftar|riwayat|rincian|detail|list|history|sisa|tinggal|read)\b""")
    private val reListCue = rx("""\b(?:daftar|riwayat|rincian|detail|list|history|transaksi|transactions?)\b""")

    private fun periodOf(t: String): FinPeriod? = when {
        Regex("""\b(?:hari\s+ini|today|sekarang)\b""").containsMatchIn(t) -> FinPeriod.TODAY
        Regex("""\b(?:kemarin|yesterday)\b""").containsMatchIn(t) -> FinPeriod.YESTERDAY
        Regex("""\b(?:bulan\s+(?:lalu|kemarin)|last\s+month)\b""").containsMatchIn(t) -> FinPeriod.LAST_MONTH
        Regex("""\b(?:minggu\s+ini|pekan\s+ini|this\s+week)\b""").containsMatchIn(t) -> FinPeriod.WEEK
        Regex("""\b(?:bulan\s+ini|this\s+month)\b""").containsMatchIn(t) -> FinPeriod.MONTH
        Regex("""\b(?:tahun\s+ini|this\s+year)\b""").containsMatchIn(t) -> FinPeriod.YEAR
        Regex("""\b(?:semua|keseluruhan|total\s+semua|all\s+time|overall|sejauh\s+ini)\b""").containsMatchIn(t) -> FinPeriod.ALL
        else -> null
    }

    private val reOnlyExp = rx("""\b(?:pengeluaran|expenses?|spending|uang\s+keluar)(?:ku|nya)?\b""")
    private val reOnlyInc = rx("""\b(?:pemasukan|pendapatan|income|uang\s+masuk|penerimaan)(?:ku|nya)?\b""")

    fun parse(raw: String): Command? {
        val t = Calc.wordsToDigits(raw.lowercase().trim())
        if (t.isEmpty()) return null
        if (reOpen.matches(t)) return Command.FinanceOpen
        if (reUndo.matches(t)) return Command.FinanceUndo
        if (reClear.matches(t)) return Command.FinanceClear
        parseAdd(t)?.let { return it }
        if (!reFinWord.containsMatchIn(t)) return null
        if (findAmount(t) != null) return null
        if (!reShowCue.containsMatchIn(t) && t.split(Regex("\\s+")).size > 4) return null
        val period = periodOf(t)
        val exp = reOnlyExp.containsMatchIn(t)
        val inc = reOnlyInc.containsMatchIn(t)
        val type = when {
            exp && !inc -> FinType.EXPENSE
            inc && !exp -> FinType.INCOME
            else -> null
        }
        val isSaldo = Regex("""\b(?:saldo|balance|sisa\s+uang|tinggal)(?:ku|nya)?\b""").containsMatchIn(t)
        if (reListCue.containsMatchIn(t) && !isSaldo) {
            return Command.FinanceList(period ?: FinPeriod.MONTH, type)
        }
        return Command.FinanceReport(period ?: if (isSaldo) FinPeriod.ALL else FinPeriod.MONTH, type)
    }
}

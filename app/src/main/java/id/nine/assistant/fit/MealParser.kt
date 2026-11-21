package id.nine.assistant.fit

/** Mengurai ucapan makan: "sarapan nasi goreng 1 piring dan telur ceplok 2 butir" -> daftar makanan + gizi. */
object MealParser {
    class Parsed(val type: MealType?, val items: List<MealItem>, val unknown: List<String>, val dayOffset: Int)

    private val SPLIT = Regex("""\s*(?:,|;|\+|\bdan\b|\bsama\b|\blalu\b|\bterus\b|\bplus\b|\bserta\b|\bdengan\b|\bwith\b|\band\b|\bpakai\b)\s*""", RegexOption.IGNORE_CASE)
    private val QTY_WORDS = listOf(
        Regex("""\bsetengah\b|\bseparuh\b|\bhalf\b""") to "0.5",
        Regex("""\bseperempat\b""") to "0.25",
        Regex("""\b1/2\b""") to "0.5",
        Regex("""\b1/4\b""") to "0.25",
        Regex("""\bsepiring\b""") to "1 piring",
        Regex("""\bsegelas\b""") to "1 gelas",
        Regex("""\bsemangkuk\b|\bsemangkok\b""") to "1 mangkuk",
        Regex("""\bsebutir\b""") to "1 butir",
        Regex("""\bsepotong\b""") to "1 potong",
        Regex("""\bsebuah\b""") to "1 buah",
        Regex("""\bsebungkus\b""") to "1 bungkus",
        Regex("""\bsecangkir\b""") to "1 cangkir",
        Regex("""\bsesendok makan\b""") to "1 sdm",
        Regex("""\bsesendok teh\b""") to "1 sdt",
        Regex("""\bsekaleng\b""") to "1 kaleng",
        Regex("""\bsebotol\b""") to "1 botol"
    )
    private val UNIT_SYN = mapOf(
        "mangkok" to "mangkuk", "biji" to "butir", "slice" to "lembar", "iris" to "potong", "piece" to "potong", "pieces" to "potong",
        "bowl" to "mangkuk", "plate" to "piring", "glass" to "gelas", "tbsp" to "sdm", "tsp" to "sdt", "serving" to "porsi", "servings" to "porsi",
        "sachet" to "bungkus", "pack" to "bungkus", "pcs" to "buah", "pc" to "buah", "buah" to "buah", "cups" to "cup", "scoops" to "scoop",
        "g" to "gram", "gr" to "gram", "grams" to "gram", "gram" to "gram", "ml" to "ml", "kali" to "porsi"
    )
    private val UNITS = Regex("""(?:sendok makan|sendok teh|sdm|sdt|porsi|piring|mangkuk|mangkok|bungkus|butir|biji|buah|potong|iris|lembar|gelas|cangkir|centong|genggam|kaleng|botol|scoop|scoops|batang|keping|tusuk|cup|cups|kotak|ekor|slice|piece|pieces|bowl|plate|glass|tbsp|tsp|serving|servings|sachet|pack|pcs|pc|gram|grams|gr|g|ml)""")
    private val QTY = Regex("""(\d+(?:\.\d+)?)\s*(""" + UNITS.pattern + """)?\b""")
    private val CAL = Regex("""(\d+(?:\.\d+)?)\s*(?:kalori|kkal|kcal|calories|calorie|cals?)\b""")
    private val STOP = setOf(
        "tadi", "barusan", "baru", "saja", "habis", "abis", "aku", "saya", "gue", "makan", "sarapan", "minum", "ngemil", "nyemil", "konsumsi", "menu", "catat", "makanan",
        "siang", "malam", "pagi", "sore", "hari", "ini", "lagi", "sedang", "yang", "enak", "banget", "lumayan", "sedikit", "banyak", "cukup", "kenyang", "tapi", "juga", "ya",
        "dong", "deh", "nih", "kemarin", "kemaren", "camilan", "cemilan", "snack", "lunch", "dinner", "breakfast", "ate", "had", "drank", "eat", "drink", "my", "i", "for", "a", "the", "some",
        "tolong", "catatan", "ingat", "simpan", "masukkan", "tambah", "tambahkan", "log", "di", "ke", "dari", "pakai", "satu", "kali", "sekali", "atau", "of", "malem", "siangan", "kira", "kira", "sekitar", "ada", "buat", "untuk"
    )

    fun mealType(t: String): MealType? = when {
        Regex("""\bsarapan\b|\bbreakfast\b""").containsMatchIn(t) -> MealType.BREAKFAST
        Regex("""\bmakan siang\b|\blunch\b|\bsiang\b""").containsMatchIn(t) -> MealType.LUNCH
        Regex("""\bmakan malam\b|\bdinner\b|\bmalam\b|\bmalem\b""").containsMatchIn(t) -> MealType.DINNER
        Regex("""\bngemil\b|\bnyemil\b|\bcamilan\b|\bcemilan\b|\bsnack\b""").containsMatchIn(t) -> MealType.SNACK
        else -> null
    }

    fun typeByHour(hour: Int): MealType = when (hour) {
        in 4..10 -> MealType.BREAKFAST
        in 11..15 -> MealType.LUNCH
        in 16..21 -> MealType.DINNER
        else -> MealType.SNACK
    }

    private fun pre(piece: String): String {
        var t = piece.lowercase()
        for ((re, to) in QTY_WORDS) t = re.replace(t, to)
        return FitText.norm(t)
    }

    fun parse(raw: String): Parsed {
        val whole = FitText.norm(raw)
        val type = mealType(whole)
        val off = WorkoutParser.dayOffset(whole)
        val items = ArrayList<MealItem>()
        val unknown = ArrayList<String>()
        for (piece in raw.split(SPLIT)) {
            val t = pre(piece)
            if (t.isEmpty()) continue
            parsePiece(t, items, unknown)
        }
        return Parsed(type, items, unknown, off)
    }

    private fun parsePiece(t: String, items: MutableList<MealItem>, unknown: MutableList<String>) {
        val hits = Foods.find(t)
        // Kalori ditulis langsung: "martabak 400 kalori".
        val cal = CAL.find(t)
        if (cal != null) {
            val kcal = cal.groupValues[1].toDoubleOrNull() ?: 0.0
            val name = t.replace(cal.value, " ").split(' ').filter { it.isNotEmpty() && it !in STOP && !it.first().isDigit() }.joinToString(" ").trim()
            if (kcal in 1.0..5000.0) {
                if (hits.isNotEmpty() && name.isNotEmpty()) {
                    val label = hits.first().food.key
                    items.add(MealItem(label, 1.0, "porsi", kcal, 0.0, 0.0, 0.0, estimated = true))
                } else {
                    items.add(MealItem(name.ifEmpty { "makanan" }, 1.0, "porsi", kcal, 0.0, 0.0, 0.0, estimated = true))
                }
                return
            }
        }
        if (hits.isEmpty()) {
            val left = leftovers(t, emptyList())
            if (left.isNotEmpty()) unknown.add(left.joinToString(" "))
            return
        }
        var used = BooleanArray(t.length)
        for ((i, h) in hits.withIndex()) {
            for (k in h.start until h.end) used[k] = true
            val prevEnd = if (i > 0) hits[i - 1].end else 0
            val nextStart = if (i + 1 < hits.size) hits[i + 1].start else t.length
            val after = t.substring(h.end, nextStart)
            val before = t.substring(prevEnd, h.start)
            val q = qty(after, before, h.food)
            if (q == null) { unknown.add(h.food.name(id.nine.assistant.core.AppLang.ID) + " (jumlah tidak masuk akal)"); continue }
            items.add(q)
            // tandai angka/satuan yang terpakai agar tidak dilaporkan sebagai sisa
            markUsed(t, prevEnd, h.start, used); markUsed(t, h.end, nextStart, used)
        }
        val left = leftovers(t, hits)
        if (left.isNotEmpty()) unknown.add(left.joinToString(" "))
    }

    private fun markUsed(t: String, from: Int, to: Int, used: BooleanArray) {
        if (from >= to) return
        for (m in QTY.findAll(t.substring(from, to))) for (k in m.range) used[from + k] = true
    }

    private fun leftovers(t: String, hits: List<Foods.Hit>): List<String> {
        val chars = t.toCharArray()
        for (h in hits) for (k in h.start until h.end) chars[k] = ' '
        val rest = String(chars)
        return rest.split(' ').filter { w ->
            w.isNotEmpty() && w.any { it.isLetter() } && w !in STOP && w.length > 2 && !UNITS.matches(w) && !UNIT_SYN.containsKey(w)
        }
    }

    /** Cari jumlah + satuan di dekat nama makanan: lebih dulu setelah nama ("telur 2 butir"), lalu sebelum ("2 butir telur"). */
    private fun qty(after: String, before: String, food: Foods.Food): MealItem? {
        var amount = 1.0
        var unit: String? = null
        val a = QTY.find(after.trimStart())
        val aOk = a != null && after.trimStart().startsWith(a.value)
        // sebelum nama: angka dengan satuan atau angka tunggal di ujung kanan ("2 telur", "1 piring nasi")
        val bm = Regex("""(\d+(?:\.\d+)?)\s*(""" + UNITS.pattern + """)?\s*$""").find(before)
        if (aOk) {
            amount = a!!.groupValues[1].toDoubleOrNull() ?: return null
            unit = a.groupValues[2].ifEmpty { null }
        } else if (bm != null) {
            amount = bm.groupValues[1].toDoubleOrNull() ?: return null
            unit = bm.groupValues[2].ifEmpty { null }
        }
        val canon = unit?.let { UNIT_SYN[it] ?: it.replace("sendok makan", "sdm").replace("sendok teh", "sdt") }
        val grams: Double
        val outUnit: String
        val outQty: Double
        when {
            canon == "gram" -> { grams = amount; outUnit = "g"; outQty = amount }
            canon == "ml" -> { grams = amount; outUnit = "ml"; outQty = amount }
            canon != null && food.units.containsKey(canon) -> { grams = amount * food.units.getValue(canon); outUnit = canon; outQty = amount }
            else -> { grams = amount * food.defaultGrams; outUnit = food.defaultUnit; outQty = amount }
        }
        if (grams <= 0.0 || grams > 3000.0 || (canon != "gram" && canon != "ml" && amount > 30.0)) return null
        val n = food.per(grams)
        return MealItem(food.key, outQty, outUnit, n[0], n[1], n[2], n[3])
    }
}

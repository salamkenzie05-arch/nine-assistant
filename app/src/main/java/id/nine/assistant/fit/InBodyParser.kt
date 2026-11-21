package id.nine.assistant.fit

/**
 * Membaca hasil InBody dari teks OCR (per baris) atau dari ucapan/ketikan. Kotlin murni.
 * Tata letak lembar hasil berbeda antar model InBody dan antar bahasa, jadi pembacaan ini BERBASIS LABEL pada satu baris
 * dengan pengecekan kewajaran; nilai yang tidak yakin dibiarkan kosong (bukan ditebak) dan pengguna diminta memeriksa.
 */
object InBodyParser {
    /** Satu baris teks hasil OCR beserta kotak pembatasnya (piksel). */
    class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Kelompokkan baris OCR yang sejajar secara vertikal menjadi satu baris tabel, urut dari kiri ke kanan. */
    fun rows(lines: List<OcrLine>): List<String> {
        class Row(var cy: Double, var n: Int, val items: ArrayList<OcrLine>)
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { (it.top + it.bottom) / 2 }
        val rows = ArrayList<Row>()
        for (l in sorted) {
            val cy = (l.top + l.bottom) / 2.0
            val h = (l.bottom - l.top).coerceAtLeast(8)
            val last = rows.lastOrNull()
            if (last != null && Math.abs(cy - last.cy) <= h * 0.55) {
                last.items.add(l)
                last.cy = (last.cy * last.n + cy) / (last.n + 1)
                last.n++
            } else {
                rows.add(Row(cy, 1, arrayListOf(l)))
            }
        }
        return rows.map { r -> r.items.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    private val NUM = Regex("""(?<![\d.])(\d{1,4}(?:\.\d{1,3})?)(?![\d])""")
    private val RANGE = Regex("""\(?\s*(\d{1,4}(?:\.\d{1,3})?)\s*[~\-–—]\s*(\d{1,4}(?:\.\d{1,3})?)\s*\)?""")

    private class Metric(val key: String, val label: Regex, val lo: Double, val hi: Double, val skip: Regex? = null)

    private fun m(key: String, label: String, lo: Double, hi: Double, skip: String? = null) =
        Metric(key, Regex(label), lo, hi, skip?.let { Regex(it) })

    private val METRICS = listOf(
        m("smm", """skeletal muscle mass|skeletal muscle|\bsmm\b|massa otot rangka|massa otot skeletal|otot rangka""", 8.0, 80.0),
        m("bfm", """body fat mass|\bbfm\b|massa lemak tubuh|massa lemak""", 2.0, 150.0),
        m("pbf", """percent body fat|body fat percentage|\bpbf\b|persentase lemak tubuh|persen lemak tubuh|persentase lemak|% body fat""", 3.0, 70.0),
        m("bmi", """\bbmi\b|\bimt\b|body mass index""", 10.0, 70.0),
        m("visceral", """visceral fat level|visceral fat|\bvfl\b|tingkat lemak vis?c?eral|lemak vis?c?eral|level lemak vis?c?eral""", 1.0, 30.0, skip = """area|vfa|cm2|cm²"""),
        m("bmr", """basal metabolic rate|\bbmr\b|laju metabolisme basal|metabolisme basal""", 600.0, 4500.0),
        m("tbw", """total body water|\btbw\b|total air tubuh|air tubuh total""", 15.0, 90.0),
        m("ffm", """fat free mass|\bffm\b|lean body mass|\blbm\b|massa bebas lemak""", 20.0, 130.0),
        m("score", """inbody score|inbody point|skor inbody|nilai inbody""", 20.0, 100.0),
        m("weight", """\bweight\b|berat badan|\bbb\b""", 25.0, 250.0, skip = """target|control|ideal|adjust|history|dry lean|lean mass|fat mass|muscle"""),
        m("height", """\bheight\b|tinggi badan|\btb\b""", 100.0, 230.0),
        m("age", """\bage\b|\busia\b|\bumur\b""", 5.0, 100.0)
    )

    private val SEGMENTS = listOf(
        "RA" to Regex("""right arm|lengan kanan"""), "LA" to Regex("""left arm|lengan kiri"""), "TR" to Regex("""\btrunk\b|\btubuh\b|\bbadan\b"""),
        "RL" to Regex("""right leg|kaki kanan"""), "LL" to Regex("""left leg|kaki kiri""")
    )

    fun normRow(s: String): String =
        s.lowercase().replace(Regex("""(?<=\d),(?=\d{1,2}(?!\d))"""), ".").replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun numbersAfter(row: String, from: Int): List<Pair<Double, IntRange>> {
        val out = ArrayList<Pair<Double, IntRange>>()
        for (mm in NUM.findAll(row, from)) mm.groupValues[1].toDoubleOrNull()?.let { out.add(it to mm.range) }
        return out
    }

    private fun fixThousands(key: String, v: Double, raw: String): Double =
        if (key == "bmr" && v < 20.0 && raw.substringAfter('.', "").length == 3) v * 1000.0 else v

    fun parseText(text: String): InBodyReading = parseRows(text.split('\n', '\r'))

    fun parseRows(rowsIn: List<String>): InBodyReading {
        val found = HashMap<String, Double>()
        val ranges = LinkedHashMap<String, Pair<Double, Double>>()
        var sex: Sex? = null
        val segKg = LinkedHashMap<String, Double>()
        val segPct = LinkedHashMap<String, Double>()
        for (rowRaw in rowsIn) {
            val row = normRow(rowRaw)
            if (row.isEmpty()) continue
            if (sex == null) {
                when {
                    Regex("""\b(?:female|perempuan|wanita)\b""").containsMatchIn(row) -> sex = Sex.FEMALE
                    Regex("""\b(?:male|laki laki|laki-laki|pria)\b""").containsMatchIn(row) -> sex = Sex.MALE
                }
            }
            for ((code, re) in SEGMENTS) {
                val sm = re.find(row) ?: continue
                if (segKg.containsKey(code)) continue
                val nums = numbersAfter(row, sm.range.last + 1).map { it.first }
                val kg = nums.firstOrNull { it in 0.5..30.0 }
                if (kg != null) {
                    segKg[code] = kg
                    nums.firstOrNull { it in 50.0..200.0 && it != kg }?.let { segPct[code] = it }
                }
            }
            for (mt in METRICS) {
                if (found.containsKey(mt.key)) continue
                val lm = mt.label.find(row) ?: continue
                if (mt.skip != null && mt.skip.containsMatchIn(row)) continue
                // "weight" tidak boleh cocok dengan bagian dari label lain di baris yang sama (mis. "body fat mass ... weight").
                val tail = numbersAfter(row, lm.range.last + 1)
                for ((v0, r) in tail) {
                    val raw = row.substring(r.first, r.last + 1)
                    val v = fixThousands(mt.key, v0, raw)
                    if (v < mt.lo || v > mt.hi) continue
                    found[mt.key] = v
                    // rentang normal tercetak tepat setelah nilai: "70.5 (59.8~80.9)"
                    val after = row.substring(r.last + 1)
                    val rg = RANGE.find(after)
                    if (rg != null && rg.range.first <= 4) {
                        val a = rg.groupValues[1].toDoubleOrNull(); val b = rg.groupValues[2].toDoubleOrNull()
                        if (a != null && b != null && a < b) ranges[mt.key] = a to b
                    }
                    break
                }
            }
        }
        var weight = found["weight"]; var bmi = found["bmi"]; var pbf = found["pbf"]
        val bfm = found["bfm"]; val height = found["height"]
        if (pbf == null && bfm != null && weight != null && weight > 0) pbf = Math.round(bfm / weight * 1000.0) / 10.0
        if (bmi == null && weight != null && height != null) bmi = Math.round(weight / ((height / 100) * (height / 100)) * 10.0) / 10.0
        return InBodyReading(
            weightKg = weight, smmKg = found["smm"], bodyFatKg = bfm, pbf = pbf, bmi = bmi, visceral = found["visceral"],
            bmr = found["bmr"], tbwL = found["tbw"], ffmKg = found["ffm"], score = found["score"], heightCm = height,
            age = found["age"]?.toInt(), sex = sex, ranges = ranges, segments = segKg, segmentPct = segPct
        )
    }

    /** Kode masalah kewajaran antar angka; kosong bila semua tampak masuk akal. */
    fun issues(r: InBodyReading): List<String> {
        val out = ArrayList<String>()
        val w = r.weightKg
        if (w != null && r.bodyFatKg != null && r.pbf != null && w > 0) {
            val calc = r.bodyFatKg / w * 100.0
            if (Math.abs(calc - r.pbf) > Math.max(2.5, r.pbf * 0.25)) out.add("PBF_MISMATCH")
        }
        if (w != null && r.smmKg != null && r.smmKg > w * 0.75) out.add("SMM_TOO_HIGH")
        if (w != null && r.bodyFatKg != null && r.bodyFatKg > w * 0.8) out.add("FAT_TOO_HIGH")
        if (w != null && r.bmi != null && r.heightCm != null) {
            val calc = w / ((r.heightCm / 100) * (r.heightCm / 100))
            if (Math.abs(calc - r.bmi) > 1.5) out.add("BMI_MISMATCH")
        }
        if (r.ffmKg != null && r.smmKg != null && r.smmKg > r.ffmKg) out.add("SMM_GT_FFM")
        return out
    }

    // ------------------------------------------------------------------ ucapan / ketikan

    private val WORDS: List<Pair<String, Regex>> = listOf(
        "smm" to Regex("""\b(?:otot rangka|massa otot|otot|smm|skeletal muscle|muscle mass|muscle)\b"""),
        "visceral" to Regex("""\b(?:visceral|viseral|vfl|lemak perut|lemak dalam)\b"""),
        "bmr" to Regex("""\b(?:bmr|metabolisme basal|metabolisme)\b"""),
        "tbw" to Regex("""\b(?:air tubuh|total air|tbw|body water)\b"""),
        "score" to Regex("""\b(?:skor|score|poin|point)\b"""),
        "bmi" to Regex("""\b(?:bmi|imt)\b"""),
        "height" to Regex("""\b(?:tinggi|height|tb)\b"""),
        "age" to Regex("""\b(?:umur|usia|age)\b"""),
        "pbf" to Regex("""\b(?:pbf|persen lemak|persentase lemak|body fat percent\w*|percent body fat|lemak tubuh persen)\b"""),
        "bfm" to Regex("""\b(?:bfm|massa lemak|fat mass|body fat mass)\b"""),
        "fat" to Regex("""\b(?:lemak|fat|body fat)\b"""),
        "weight" to Regex("""\b(?:berat badan|berat|weight|bb)\b""")
    )

    private val LOOSE_NUM = Regex("""(?<![\d.])(\d{1,4}(?:\.\d{1,2})?)(?![\d])""")

    /** "inbody berat 70.5 otot 32 lemak 14.5 pbf 20 visceral 7 bmr 1612"; minimal 2 nilai, selain itu null. */
    fun parseManual(raw: String): InBodyReading? = manualCore(raw)?.takeIf { it.fieldCount() >= 2 }

    /** Koreksi singkat atas bacaan yang menunggu konfirmasi ("berat 70.5", "lemak viseral 8"): cukup satu nilai. */
    fun parseCorrection(raw: String): InBodyReading? {
        val t = FitText.norm(raw)
        if (t.split(' ').size > 10 || Exercises.find(t).isNotEmpty() || Foods.find(t).isNotEmpty()) return null
        return manualCore(raw)?.takeIf { it.fieldCount() >= 1 || it.heightCm != null || it.age != null || it.sex != null }
    }

    private fun manualCore(raw: String): InBodyReading? {
        val t = FitText.norm(raw)
        val taken = ArrayList<IntRange>()
        val found = HashMap<String, Double>()
        var sex: Sex? = null
        if (Regex("""\b(?:perempuan|wanita|female|cewek)\b""").containsMatchIn(t)) sex = Sex.FEMALE
        else if (Regex("""\b(?:laki laki|laki|pria|male|cowok)\b""").containsMatchIn(t)) sex = Sex.MALE
        // Setiap label dicocokkan dengan angka pertama SESUDAHNYA (maks. 2 kata), supaya "lemak 14.5 pbf 20" tidak tertukar.
        val hits = ArrayList<Triple<String, Int, Int>>()
        for ((key, re) in WORDS) for (mm in re.findAll(t)) {
            if (taken.any { mm.range.first <= it.last && mm.range.last >= it.first }) continue
            taken.add(mm.range)
            hits.add(Triple(key, mm.range.first, mm.range.last + 1))
        }
        hits.sortBy { it.second }
        for ((i, h) in hits.withIndex()) {
            val limit = if (i + 1 < hits.size) hits[i + 1].second else t.length
            val seg = t.substring(h.third, limit)
            val nm = LOOSE_NUM.find(seg) ?: continue
            // jarak: maksimal tiga kata antara label dan angka
            val gap = seg.substring(0, nm.range.first).trim().split(' ').filter { it.isNotEmpty() }
            if (gap.size > 3) continue
            val v = nm.groupValues[1].toDoubleOrNull() ?: continue
            var key = h.first
            if (key == "fat") {
                val after = seg.substring(nm.range.last + 1).trim()
                key = if (after.startsWith("persen") || after.startsWith("percent") || after.startsWith("pct") || after.startsWith("%") || raw.contains("%")) "pbf" else "bfm"
            }
            if (!found.containsKey(key)) found[key] = v
        }
        val lim = mapOf(
            "weight" to (25.0..250.0), "smm" to (8.0..80.0), "bfm" to (2.0..150.0), "pbf" to (3.0..70.0), "bmi" to (10.0..70.0),
            "visceral" to (1.0..30.0), "bmr" to (600.0..4500.0), "tbw" to (15.0..90.0), "score" to (20.0..100.0),
            "height" to (100.0..230.0), "age" to (5.0..100.0)
        )
        val ok = found.filter { (k, v) -> lim[k]?.contains(v) == true }
        val weight = ok["weight"]; val bfm = ok["bfm"]; val height = ok["height"]
        var pbf = ok["pbf"]; var bmi = ok["bmi"]
        if (pbf == null && bfm != null && weight != null) pbf = Math.round(bfm / weight * 1000.0) / 10.0
        if (bmi == null && weight != null && height != null) bmi = Math.round(weight / ((height / 100) * (height / 100)) * 10.0) / 10.0
        val rd = InBodyReading(
            weightKg = weight, smmKg = ok["smm"], bodyFatKg = bfm, pbf = pbf, bmi = bmi, visceral = ok["visceral"], bmr = ok["bmr"],
            tbwL = ok["tbw"], score = ok["score"], heightCm = height, age = ok["age"]?.toInt(), sex = sex
        )
        return rd
    }

    /** Gabungkan koreksi/tambahan pengguna ke bacaan OCR: nilai baru menimpa yang lama. */
    fun merge(base: InBodyReading, add: InBodyReading): InBodyReading = base.copy(
        weightKg = add.weightKg ?: base.weightKg, smmKg = add.smmKg ?: base.smmKg, bodyFatKg = add.bodyFatKg ?: base.bodyFatKg,
        pbf = add.pbf ?: base.pbf, bmi = add.bmi ?: base.bmi, visceral = add.visceral ?: base.visceral, bmr = add.bmr ?: base.bmr,
        tbwL = add.tbwL ?: base.tbwL, ffmKg = add.ffmKg ?: base.ffmKg, score = add.score ?: base.score,
        heightCm = add.heightCm ?: base.heightCm, age = add.age ?: base.age, sex = add.sex ?: base.sex,
        // nilai yang dikoreksi tidak lagi punya rentang tercetak yang valid
        ranges = base.ranges.filterKeys { k -> when (k) {
            "weight" -> add.weightKg == null; "smm" -> add.smmKg == null; "bfm" -> add.bodyFatKg == null; "pbf" -> add.pbf == null
            "visceral" -> add.visceral == null; "bmi" -> add.bmi == null; else -> true } }
    )
}

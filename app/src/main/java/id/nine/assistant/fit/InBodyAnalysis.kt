package id.nine.assistant.fit

import id.nine.assistant.core.AppLang

/**
 * Membaca hasil InBody dengan bahasa awam dan saran umum. Acuan: rentang standar InBody (PBF 10-20% pria, 18-28% wanita;
 * viseral 1-9), BMI Asia-Pasifik. Bukan diagnosis medis; BIA dipengaruhi kadar air tubuh sehingga TREN lebih bermakna
 * daripada satu angka. Kotlin murni.
 */
object InBodyAnalysis {
    enum class Band { LOW, STANDARD, HIGH, VERY_HIGH }

    fun fatBand(pbf: Double, sex: Sex): Band = if (sex == Sex.MALE) when {
        pbf < 10.0 -> Band.LOW; pbf <= 20.0 -> Band.STANDARD; pbf <= 25.0 -> Band.HIGH; else -> Band.VERY_HIGH
    } else when {
        pbf < 18.0 -> Band.LOW; pbf <= 28.0 -> Band.STANDARD; pbf <= 35.0 -> Band.HIGH; else -> Band.VERY_HIGH
    }

    fun bmiLabel(bmi: Double, l: AppLang): String = when {
        bmi < 18.5 -> l.t("berat badan kurang", "underweight")
        bmi < 23.0 -> l.t("normal", "normal")
        bmi < 25.0 -> l.t("berat badan lebih", "overweight")
        bmi < 30.0 -> l.t("obesitas I", "obese class I")
        else -> l.t("obesitas II", "obese class II")
    }

    private fun f(d: Double, l: AppLang, digits: Int = 1) = FitText.fmt(d, l, digits)

    private fun rangeNote(r: InBodyReading, key: String, v: Double, l: AppLang): String {
        val rg = r.ranges[key] ?: return ""
        val state = when {
            v < rg.first -> l.t("di bawah", "below")
            v > rg.second -> l.t("di atas", "above")
            else -> l.t("dalam", "within")
        }
        return l.t(" (tercetak $state rentang normal ${f(rg.first, l)}-${f(rg.second, l)})", " ($state the printed normal range ${f(rg.first, l)}-${f(rg.second, l)})")
    }

    /** Ringkasan angka yang terbaca, untuk dikonfirmasi pengguna. */
    fun readback(r: InBodyReading, l: AppLang): String {
        val parts = ArrayList<String>()
        r.weightKg?.let { parts.add(l.t("berat ${f(it, l)} kg", "weight ${f(it, l)} kg")) }
        r.smmKg?.let { parts.add(l.t("otot rangka ${f(it, l)} kg", "skeletal muscle ${f(it, l)} kg")) }
        r.bodyFatKg?.let { parts.add(l.t("massa lemak ${f(it, l)} kg", "fat mass ${f(it, l)} kg")) }
        r.pbf?.let { parts.add(l.t("lemak tubuh ${f(it, l)}%", "body fat ${f(it, l)}%")) }
        r.bmi?.let { parts.add("BMI ${f(it, l)}") }
        r.visceral?.let { parts.add(l.t("lemak viseral level ${f(it, l, 0)}", "visceral fat level ${f(it, l, 0)}")) }
        r.bmr?.let { parts.add("BMR ${f(it, l, 0)} kkal") }
        r.tbwL?.let { parts.add(l.t("air tubuh ${f(it, l)} L", "body water ${f(it, l)} L")) }
        r.score?.let { parts.add(l.t("skor ${f(it, l, 0)}", "score ${f(it, l, 0)}")) }
        r.heightCm?.let { parts.add(l.t("tinggi ${f(it, l, 0)} cm", "height ${f(it, l, 0)} cm")) }
        r.age?.let { parts.add(l.t("usia $it", "age $it")) }
        r.sex?.let { parts.add(if (it == Sex.MALE) l.t("laki-laki", "male") else l.t("perempuan", "female")) }
        return parts.joinToString(", ")
    }

    fun issueText(codes: List<String>, l: AppLang): String {
        if (codes.isEmpty()) return ""
        val msgs = codes.map {
            when (it) {
                "PBF_MISMATCH" -> l.t("persen lemak tidak cocok dengan massa lemak dan berat", "body-fat % doesn't match fat mass and weight")
                "SMM_TOO_HIGH" -> l.t("massa otot terlalu besar terhadap berat", "muscle mass is too large for the weight")
                "FAT_TOO_HIGH" -> l.t("massa lemak terlalu besar terhadap berat", "fat mass is too large for the weight")
                "BMI_MISMATCH" -> l.t("BMI tidak cocok dengan berat dan tinggi", "BMI doesn't match weight and height")
                "SMM_GT_FFM" -> l.t("massa otot melebihi massa bebas lemak", "muscle mass exceeds fat-free mass")
                else -> it
            }
        }
        return l.t("Ada angka yang tampak janggal: ", "Some numbers look off: ") + msgs.joinToString("; ") + "."
    }

    fun analyze(r: InBodyReading, profile: Profile, prev: InBodyReading?, l: AppLang): String {
        val sex = r.sex ?: profile.sex
        val sb = StringBuilder()
        val lines = ArrayList<String>()
        val w = r.weightKg
        if (w != null) {
            var s = l.t("Berat ${f(w, l)} kg", "Weight ${f(w, l)} kg")
            r.bmi?.let { s += l.t(", BMI ${f(it, l)} (${bmiLabel(it, l)})", ", BMI ${f(it, l)} (${bmiLabel(it, l)})") }
            lines.add("• $s")
        } else r.bmi?.let { lines.add("• BMI ${f(it, l)} (${bmiLabel(it, l)})") }
        r.smmKg?.let {
            var s = l.t("Otot rangka ${f(it, l)} kg", "Skeletal muscle ${f(it, l)} kg")
            if (w != null) s += l.t(" (${f(it / w * 100.0, l, 0)}% dari berat)", " (${f(it / w * 100.0, l, 0)}% of weight)")
            s += rangeNote(r, "smm", it, l)
            lines.add("• $s")
        }
        r.bodyFatKg?.let { lines.add("• " + l.t("Massa lemak ${f(it, l)} kg", "Fat mass ${f(it, l)} kg") + rangeNote(r, "bfm", it, l)) }
        var band: Band? = null
        r.pbf?.let { p ->
            if (sex != null) {
                band = fatBand(p, sex)
                val std = if (sex == Sex.MALE) "10-20%" else "18-28%"
                val txt = when (band) {
                    Band.LOW -> l.t("di bawah rentang standar ($std)", "below the standard range ($std)")
                    Band.STANDARD -> l.t("dalam rentang standar ($std)", "within the standard range ($std)")
                    Band.HIGH -> l.t("di atas rentang standar ($std)", "above the standard range ($std)")
                    else -> l.t("jauh di atas rentang standar ($std)", "well above the standard range ($std)")
                }
                lines.add("• " + l.t("Lemak tubuh ${f(p, l)}%: $txt", "Body fat ${f(p, l)}%: $txt"))
            } else {
                lines.add("• " + l.t("Lemak tubuh ${f(p, l)}% (standar InBody: pria 10-20%, wanita 18-28%; sebutkan jenis kelaminmu agar bisa saya nilai)", "Body fat ${f(p, l)}% (InBody standard: men 10-20%, women 18-28%; tell me your sex so I can rate it)"))
            }
        }
        var visBand = 0
        r.visceral?.let { v ->
            visBand = when { v <= 9.0 -> 0; v <= 14.0 -> 1; else -> 2 }
            val txt = when (visBand) {
                0 -> l.t("normal (1-9)", "normal (1-9)")
                1 -> l.t("naik; perlu perhatian", "elevated; worth attention")
                else -> l.t("tinggi; sebaiknya diperiksa dokter", "high; worth a doctor's check")
            }
            lines.add("• " + l.t("Lemak viseral level ${f(v, l, 0)}: $txt", "Visceral fat level ${f(v, l, 0)}: $txt"))
        }
        r.bmr?.let { lines.add("• " + l.t("BMR ${f(it, l, 0)} kkal (kalori saat istirahat)", "BMR ${f(it, l, 0)} kcal (resting calories)")) }
        if (r.tbwL != null && w != null) {
            val pct = r.tbwL / w * 100.0
            lines.add("• " + l.t("Air tubuh ${f(r.tbwL, l)} L (${f(pct, l, 0)}% dari berat; kisaran optimal sekitar 50-60% pria, 45-65% wanita)", "Body water ${f(r.tbwL, l)} L (${f(pct, l, 0)}% of weight; optimal about 50-60% men, 45-65% women)"))
        }
        r.score?.let { lines.add("• " + l.t("Skor InBody ${f(it, l, 0)}/100 (80 sering dipakai sebagai acuan standar)", "InBody Score ${f(it, l, 0)}/100 (80 is often used as the standard reference)")) }
        val seg = segmentNote(r, l)
        if (seg != null) lines.add("• $seg")
        sb.append(l.t("Hasil InBody:\n", "InBody result:\n")).append(lines.joinToString("\n"))

        // Saran umum
        val tips = ArrayList<String>()
        val goal = profile.goal
        val fatHigh = band == Band.HIGH || band == Band.VERY_HIGH
        val smmWithin = r.ranges["smm"]?.let { rg -> r.smmKg?.let { it in rg.first..rg.second } }
        val smmLow = r.ranges["smm"]?.let { rg -> r.smmKg?.let { it < rg.first } } == true
        if (fatHigh) tips.add(l.t(
            "Lemak tubuh di atas standar: fokus defisit kalori moderat (±300-500 kkal), protein tinggi (±1,6-2,2 g/kg), dan latihan beban 2-4 kali seminggu supaya otot terjaga. Target turun ±0,25-0,5 kg per minggu.",
            "Body fat above standard: aim for a moderate deficit (±300-500 kcal), high protein (±1.6-2.2 g/kg) and strength training 2-4 times a week to keep muscle. Target ±0.25-0.5 kg loss per week."))
        if (smmLow) tips.add(l.t(
            "Otot di bawah rentang normal: prioritaskan latihan beban dengan progresi, protein cukup, dan kalori tidak terlalu rendah.",
            "Muscle is below the normal range: prioritize progressive strength training, enough protein and not-too-low calories."))
        if (band == Band.LOW) tips.add(l.t(
            "Lemak tubuh rendah: pastikan asupan kalori dan lemak sehat cukup; kalau tidak sengaja sangat rendah atau ada gangguan haid/lelah terus, periksakan ke dokter.",
            "Body fat is low: make sure calories and healthy fats are enough; if it's unintentional or you have missed periods/constant fatigue, see a doctor."))
        if (band == Band.STANDARD && !smmLow) tips.add(
            if (goal == Goal.CUT) l.t("Lemak sudah standar; hati-hati menurunkan lagi terlalu agresif agar otot tidak ikut turun.", "Fat is already standard; avoid cutting too aggressively so muscle isn't lost.")
            else l.t("Komposisi sudah dalam standar: pertahankan, atau naikkan otot dengan surplus kecil dan latihan beban.", "Composition is in the standard range: maintain it, or build muscle with a small surplus and strength training."))
        if (visBand >= 1) tips.add(l.t(
            "Untuk lemak viseral: perbanyak jalan kaki/kardio, kurangi minuman manis dan alkohol, tidur cukup. Kalau level ≥15 atau ada riwayat diabetes/hipertensi/kolesterol, konsultasikan ke dokter.",
            "For visceral fat: add walking/cardio, cut sweet drinks and alcohol, sleep enough. If level ≥15 or you have diabetes/hypertension/cholesterol history, talk to a doctor."))
        if (smmWithin == true && !fatHigh && tips.isEmpty()) tips.add(l.t("Angka utama tampak baik. Ukur ulang tiap 4-8 minggu pada kondisi serupa untuk melihat tren.", "Main numbers look good. Re-scan every 4-8 weeks under similar conditions to see the trend."))
        if (tips.isNotEmpty()) sb.append("\n\n").append(l.t("Saran umum:", "General advice:")).append('\n').append(tips.joinToString("\n") { "• $it" })

        if (prev != null) sb.append("\n\n").append(compare(prev, r, l))
        sb.append("\n\n").append(l.t(
            "Catatan: InBody memperkirakan lewat air tubuh, jadi hasil bisa bergeser karena makan, minum, atau olahraga sebelum ukur. Ini bukan diagnosis; bandingkan beberapa kali ukur.",
            "Note: InBody estimates via body water, so results can shift with eating, drinking or exercise before the scan. This isn't a diagnosis; compare several scans."))
        return sb.toString()
    }

    private fun segmentNote(r: InBodyReading, l: AppLang): String? {
        fun diff(a: String, b: String): Double? {
            val x = r.segments[a]; val y = r.segments[b]
            if (x == null || y == null || x <= 0.0 || y <= 0.0) return null
            return Math.abs(x - y) / Math.max(x, y) * 100.0
        }
        val arms = diff("RA", "LA"); val legs = diff("RL", "LL")
        val notes = ArrayList<String>()
        if (arms != null && arms >= 8.0) notes.add(l.t("lengan kanan dan kiri berbeda ±${f(arms, l, 0)}%", "left and right arms differ by ±${f(arms, l, 0)}%"))
        if (legs != null && legs >= 8.0) notes.add(l.t("kaki kanan dan kiri berbeda ±${f(legs, l, 0)}%", "left and right legs differ by ±${f(legs, l, 0)}%"))
        if (notes.isEmpty()) return if (arms != null || legs != null) l.t("Otot kiri dan kanan relatif seimbang.", "Left and right muscle look fairly balanced.") else null
        return l.t("Keseimbangan otot: ", "Muscle balance: ") + notes.joinToString("; ") + l.t(". Latih sisi yang lebih lemah dengan gerakan satu sisi (unilateral).", ". Train the weaker side with single-sided (unilateral) work.")
    }

    fun compare(a: InBodyReading, b: InBodyReading, l: AppLang): String {
        val parts = ArrayList<String>()
        fun d(label: String, x: Double?, y: Double?, unit: String, digits: Int = 1) {
            if (x == null || y == null) return
            val diff = y - x
            val sign = if (diff > 0) "+" else ""
            parts.add("$label: ${f(x, l, digits)} → ${f(y, l, digits)}$unit ($sign${f(diff, l, digits)})")
        }
        d(l.t("Berat", "Weight"), a.weightKg, b.weightKg, " kg")
        d(l.t("Otot rangka", "Skeletal muscle"), a.smmKg, b.smmKg, " kg")
        d(l.t("Massa lemak", "Fat mass"), a.bodyFatKg, b.bodyFatKg, " kg")
        d(l.t("Lemak tubuh", "Body fat"), a.pbf, b.pbf, "%")
        d(l.t("Lemak viseral", "Visceral fat"), a.visceral, b.visceral, "", 0)
        val sb = StringBuilder(l.t("Dibanding hasil sebelumnya:\n", "Compared with the previous scan:\n"))
        sb.append(parts.joinToString("\n") { "• $it" })
        val dm = if (a.smmKg != null && b.smmKg != null) b.smmKg - a.smmKg else null
        val dfat = if (a.bodyFatKg != null && b.bodyFatKg != null) b.bodyFatKg - a.bodyFatKg
            else if (a.pbf != null && b.pbf != null) b.pbf - a.pbf else null
        val small = (dm == null || Math.abs(dm) < 0.5) && (dfat == null || Math.abs(dfat) < (if (a.bodyFatKg != null) 0.7 else 1.0))
        if (parts.isEmpty()) return l.t("Belum ada angka yang bisa dibandingkan.", "No comparable numbers yet.")
        val verdict = when {
            dm == null || dfat == null -> null
            small -> l.t("Perubahannya kecil, masih dalam batas variasi pengukuran.", "The change is small, within normal measurement variation.")
            dm > 0 && dfat < 0 -> l.t("Otot naik dan lemak turun: rekomposisi yang bagus.", "Muscle up and fat down: great recomposition.")
            dm < 0 && dfat < 0 -> l.t("Lemak turun tetapi otot ikut turun: naikkan protein, tetap latihan beban, dan jangan defisit terlalu besar.", "Fat down but muscle down too: raise protein, keep lifting and avoid too big a deficit.")
            dm > 0 && dfat > 0 -> l.t("Otot dan lemak sama-sama naik (fase menambah massa): pantau agar lemak tidak naik terlalu cepat.", "Muscle and fat both up (a gaining phase): watch that fat doesn't climb too fast.")
            dm < 0 && dfat > 0 -> l.t("Otot turun dan lemak naik: periksa tidur, protein, dan konsistensi latihan.", "Muscle down and fat up: check sleep, protein and training consistency.")
            else -> null
        }
        if (verdict != null) sb.append("\n").append(verdict)
        return sb.toString()
    }
}

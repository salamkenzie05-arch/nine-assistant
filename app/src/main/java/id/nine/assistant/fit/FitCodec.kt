package id.nine.assistant.fit

/**
 * Serialisasi sederhana berbasis baris dan tab (tanpa pustaka JSON, supaya bisa diuji di JVM).
 * Baris yang rusak dilewati; satu baris buruk tidak boleh menghilangkan seluruh catatan.
 */
object FitCodec {
    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun n(d: Double?): String = if (d == null) "" else d.toString()
    private fun d(s: String?): Double? = s?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun i(s: String?): Int? = s?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    fun encode(data: FitData): String {
        val sb = StringBuilder()
        sb.append("V\t1\t").append(data.nextId).append('\n')
        val p = data.profile
        sb.append("P\t").append(p.sex?.name ?: "").append('\t').append(p.age?.toString() ?: "").append('\t')
            .append(n(p.heightCm)).append('\t').append(n(p.weightKg)).append('\t').append(p.goal?.name ?: "").append('\t')
            .append(p.activity?.name ?: "").append('\t').append(p.daysPerWeek?.toString() ?: "").append('\n')
        for (w in data.workouts) for (e in w.entries) {
            sb.append("W\t").append(w.id).append('\t').append(w.time).append('\t').append(esc(e.exercise)).append('\t')
                .append(e.sets).append('\t').append(e.reps).append('\t').append(e.weightKg).append('\t')
                .append(e.minutes).append('\t').append(e.km).append('\t').append(e.seconds).append('\n')
        }
        for (b in data.weights) sb.append("B\t").append(b.id).append('\t').append(b.time).append('\t').append(b.kg).append('\n')
        for (m in data.meals) for (it in m.items) {
            sb.append("M\t").append(m.id).append('\t').append(m.time).append('\t').append(m.type.name).append('\t')
                .append(esc(it.name)).append('\t').append(it.qty).append('\t').append(esc(it.unit)).append('\t')
                .append(it.kcal).append('\t').append(it.protein).append('\t').append(it.carbs).append('\t')
                .append(it.fat).append('\t').append(if (it.estimated) 1 else 0).append('\n')
        }
        for (r in data.inbody) {
            sb.append("I\t").append(r.time).append('\t').append(n(r.weightKg)).append('\t').append(n(r.smmKg)).append('\t')
                .append(n(r.bodyFatKg)).append('\t').append(n(r.pbf)).append('\t').append(n(r.bmi)).append('\t')
                .append(n(r.visceral)).append('\t').append(n(r.bmr)).append('\t').append(n(r.tbwL)).append('\t')
                .append(n(r.ffmKg)).append('\t').append(n(r.score)).append('\t').append(n(r.heightCm)).append('\t')
                .append(r.age?.toString() ?: "").append('\t').append(r.sex?.name ?: "").append('\t')
                .append(esc(r.ranges.entries.joinToString(";") { "${it.key}=${it.value.first}~${it.value.second}" })).append('\t')
                .append(esc(r.segments.entries.joinToString(";") { "${it.key}=${it.value}" })).append('\t')
                .append(esc(r.segmentPct.entries.joinToString(";") { "${it.key}=${it.value}" })).append('\n')
        }
        return sb.toString()
    }

    private fun pairs(s: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (part in unesc(s).split(';')) {
            val k = part.substringBefore('=', "")
            val v = part.substringAfter('=', "").toDoubleOrNull() ?: continue
            if (k.isNotEmpty() && v.isFinite()) out[k] = v
        }
        return out
    }

    private fun ranges(s: String): Map<String, Pair<Double, Double>> {
        val out = LinkedHashMap<String, Pair<Double, Double>>()
        for (part in unesc(s).split(';')) {
            val k = part.substringBefore('=', "")
            val v = part.substringAfter('=', "")
            val lo = v.substringBefore('~').toDoubleOrNull() ?: continue
            val hi = v.substringAfter('~', "").toDoubleOrNull() ?: continue
            if (k.isNotEmpty()) out[k] = lo to hi
        }
        return out
    }

    fun decode(raw: String): FitData {
        val data = FitData()
        val wk = LinkedHashMap<Long, Pair<Long, ArrayList<WorkoutEntry>>>()
        val ml = LinkedHashMap<Long, Triple<Long, MealType, ArrayList<MealItem>>>()
        var maxId = 0L
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "V" -> data.nextId = f.getOrNull(2)?.toLongOrNull() ?: 1
                    "P" -> data.profile = Profile(
                        sex = f.getOrNull(1)?.let { s -> Sex.values().firstOrNull { it.name == s } },
                        age = i(f.getOrNull(2)),
                        heightCm = d(f.getOrNull(3)),
                        weightKg = d(f.getOrNull(4)),
                        goal = f.getOrNull(5)?.let { s -> Goal.values().firstOrNull { it.name == s } },
                        activity = f.getOrNull(6)?.let { s -> Activity.values().firstOrNull { it.name == s } },
                        daysPerWeek = i(f.getOrNull(7))
                    )
                    "W" -> {
                        val id = f[1].toLong(); val t = f[2].toLong()
                        val e = WorkoutEntry(unesc(f[3]), f[4].toInt(), f[5].toInt(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), f[9].toInt())
                        wk.getOrPut(id) { t to ArrayList() }.second.add(e)
                        if (id > maxId) maxId = id
                    }
                    "B" -> {
                        val id = f[1].toLong()
                        data.weights.add(WeightEntry(id, f[2].toLong(), f[3].toDouble()))
                        if (id > maxId) maxId = id
                    }
                    "M" -> {
                        val id = f[1].toLong(); val t = f[2].toLong()
                        val type = MealType.values().firstOrNull { it.name == f[3] } ?: MealType.SNACK
                        val item = MealItem(unesc(f[4]), f[5].toDouble(), unesc(f[6]), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(), f[10].toDouble(), f.getOrNull(11) != "0")
                        ml.getOrPut(id) { Triple(t, type, ArrayList()) }.third.add(item)
                        if (id > maxId) maxId = id
                    }
                    "I" -> data.inbody.add(
                        InBodyReading(
                            time = f[1].toLong(), weightKg = d(f.getOrNull(2)), smmKg = d(f.getOrNull(3)), bodyFatKg = d(f.getOrNull(4)),
                            pbf = d(f.getOrNull(5)), bmi = d(f.getOrNull(6)), visceral = d(f.getOrNull(7)), bmr = d(f.getOrNull(8)),
                            tbwL = d(f.getOrNull(9)), ffmKg = d(f.getOrNull(10)), score = d(f.getOrNull(11)), heightCm = d(f.getOrNull(12)),
                            age = i(f.getOrNull(13)),
                            sex = f.getOrNull(14)?.let { s -> Sex.values().firstOrNull { it.name == s } },
                            ranges = ranges(f.getOrNull(15) ?: ""),
                            segments = pairs(f.getOrNull(16) ?: ""),
                            segmentPct = pairs(f.getOrNull(17) ?: "")
                        )
                    )
                }
            } catch (e: Exception) {
                // baris rusak: dilewati
            }
        }
        for ((id, v) in wk) data.workouts.add(Workout(id, v.first, v.second))
        for ((id, v) in ml) data.meals.add(Meal(id, v.first, v.second, v.third))
        data.workouts.sortBy { it.time }
        data.weights.sortBy { it.time }
        data.meals.sortBy { it.time }
        data.inbody.sortBy { it.time }
        if (data.nextId <= maxId) data.nextId = maxId + 1
        return data
    }
}

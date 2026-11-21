package id.nine.assistant.fit

/** Mengurai ucapan latihan: "bench press 60 kg 3 set 8 rep", "squat 80x5, 90x3", "lari 5 km 30 menit". */
object WorkoutParser {
    enum class WarnKind { NO_WEIGHT, NO_REPS, ODD_NUMBER, NO_AMOUNT }
    class Warning(val kind: WarnKind, val exercise: String)
    class Parsed(val entries: List<WorkoutEntry>, val dayOffset: Int, val warnings: List<Warning>)

    private val WEIGHT = Regex("""(\d+(?:\.\d+)?)\s*(kg|kilo|kilogram|lbs?|pound|pounds)\b""")
    private val SETS = Regex("""(\d+)\s*(?:set|sets|seri)\b""")
    private val REPS = Regex("""(\d+)\s*(?:rep|reps|repetisi|kali|ulangan)\b""")
    private val PAIR = Regex("""(\d+(?:\.\d+)?)\s*(kg|kilo)?\s*x\s*(\d+)\b""")
    private val MINUTES = Regex("""(\d+(?:\.\d+)?)\s*(?:menit|min|mnt|minutes?|mins)\b""")
    private val HOURS = Regex("""(\d+(?:\.\d+)?)\s*(?:jam|hours?|hrs?)\b""")
    private val SECONDS = Regex("""(\d+)\s*(?:detik|sec|secs|seconds?)\b""")
    private val KM = Regex("""(\d+(?:\.\d+)?)\s*(?:km|kilometer|kilometre)\b""")
    private val BARE = Regex("""(?<![\d.])(\d+)(?![\d.])""")

    private const val MAX_KG = 600.0
    private const val MAX_REPS = 300
    private const val MAX_SETS = 40
    private const val MAX_MIN = 720.0

    fun dayOffset(t: String): Int = when {
        Regex("""\b(?:kemarin|kemaren|yesterday)\b""").containsMatchIn(t) -> -1
        else -> 0
    }

    fun parse(raw: String): Parsed? = parseNorm(FitText.norm(raw))

    fun parseNorm(t: String): Parsed? {
        val hits = Exercises.find(t)
        if (hits.isEmpty()) return null
        val entries = ArrayList<WorkoutEntry>()
        val warns = ArrayList<Warning>()
        for ((i, h) in hits.withIndex()) {
            val end = if (i + 1 < hits.size) hits[i + 1].start else t.length
            var seg = t.substring(h.end, end)
            // Angka yang diucapkan sebelum nama gerakan pertama ("3 set bench press 60 kg").
            if (i == 0 && !BARE.containsMatchIn(seg) && h.start > 0) seg = t.substring(0, h.start) + " " + seg
            parseSegment(h.def, seg, entries, warns)
        }
        if (entries.isEmpty() && warns.isEmpty()) return null
        return Parsed(entries, dayOffset(t), warns)
    }

    private fun lbToKg(v: Double, unit: String): Double = if (unit.startsWith("lb") || unit.startsWith("pound")) v * 0.45359237 else v

    private fun parseSegment(def: Exercises.Def, seg: String, out: MutableList<WorkoutEntry>, warns: MutableList<Warning>) {
        when (def.kind) {
            Exercises.Kind.CARDIO -> cardio(def, seg, out, warns)
            Exercises.Kind.HOLD -> hold(def, seg, out, warns)
            else -> strength(def, seg, out, warns)
        }
    }

    private fun cardio(def: Exercises.Def, seg: String, out: MutableList<WorkoutEntry>, warns: MutableList<Warning>) {
        var minutes = MINUTES.find(seg)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        HOURS.find(seg)?.groupValues?.get(1)?.toDoubleOrNull()?.let { minutes += it * 60 }
        val km = KM.find(seg)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (minutes <= 0.0 && km <= 0.0) { warns.add(Warning(WarnKind.NO_AMOUNT, def.key)); return }
        if (minutes > MAX_MIN || km > 300.0) { warns.add(Warning(WarnKind.ODD_NUMBER, def.key)); return }
        out.add(WorkoutEntry(def.key, minutes = minutes, km = km))
    }

    private fun hold(def: Exercises.Def, seg: String, out: MutableList<WorkoutEntry>, warns: MutableList<Warning>) {
        var sec = SECONDS.find(seg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        MINUTES.find(seg)?.groupValues?.get(1)?.toDoubleOrNull()?.let { sec += (it * 60).toInt() }
        if (sec <= 0) { warns.add(Warning(WarnKind.NO_AMOUNT, def.key)); return }
        if (sec > 3600) { warns.add(Warning(WarnKind.ODD_NUMBER, def.key)); return }
        val sets = SETS.find(seg)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, MAX_SETS) ?: 1
        out.add(WorkoutEntry(def.key, sets = sets, seconds = sec))
    }

    private fun strength(def: Exercises.Def, segIn: String, out: MutableList<WorkoutEntry>, warns: MutableList<Warning>) {
        val bodyweight = def.kind == Exercises.Kind.BODYWEIGHT
        var seg = segIn
        val wm = WEIGHT.find(seg)
        var weight: Double? = wm?.let { lbToKo(it) }
        val setsExplicit = SETS.find(seg)?.groupValues?.get(1)?.toIntOrNull()
        val repsExplicit = REPS.find(seg)?.groupValues?.get(1)?.toIntOrNull()

        val pairs = PAIR.findAll(seg).toList()
        if (pairs.isNotEmpty()) {
            val groups = ArrayList<WorkoutEntry>()
            // Beban eksplisit + satu pasangan tanpa "kg" di dalamnya: itu "set x rep" ("60 kg 3x8").
            val firstHasKg = pairs.any { !it.groupValues[2].isEmpty() }
            val weightFirst = firstHasKg || pairs.size >= 2 && pairs.all { isWeightLike(it.groupValues[1]) } ||
                (weight == null && pairs.size == 1 && !bodyweight && isWeightLike(pairs[0].groupValues[1]))
            if (weightFirst) {
                for (p in pairs) {
                    val w = p.groupValues[1].toDoubleOrNull() ?: continue
                    val r = p.groupValues[3].toIntOrNull() ?: continue
                    addGroup(def, 1, r, w, groups, warns)
                }
                // "60x8 3 set": jumlah set eksplisit berlaku bila hanya satu kelompok.
                if (groups.size == 1 && setsExplicit != null && setsExplicit in 1..MAX_SETS) groups[0] = groups[0].copy(sets = setsExplicit)
            } else {
                val p = pairs[0]
                val s = p.groupValues[1].toIntOrNull()
                val r = p.groupValues[3].toIntOrNull()
                if (s != null && r != null) addGroup(def, s, r, if (bodyweight) 0.0 else weight ?: 0.0, groups, warns, noWeightKnown = !bodyweight && weight == null)
            }
            out.addAll(groups)
            return
        }

        val sets = setsExplicit ?: 1
        var reps = repsExplicit
        if (reps == null) {
            // "push up 20" / "plank"-like tanpa kata rep: satu-satunya angka sisa dianggap jumlah rep (bukan beban/set).
            seg = WEIGHT.replace(seg, " ")
            seg = SETS.replace(seg, " ")
            val bare = BARE.findAll(seg).map { it.groupValues[1].toInt() }.toList()
            if (bare.size == 1) reps = bare[0]
        }
        if (reps == null || reps <= 0) { warns.add(Warning(WarnKind.NO_REPS, def.key)); return }
        val groups = ArrayList<WorkoutEntry>()
        addGroup(def, sets, reps, if (bodyweight) (weight ?: 0.0) else weight ?: 0.0, groups, warns, noWeightKnown = !bodyweight && weight == null)
        out.addAll(groups)
    }

    private fun lbToKo(m: MatchResult): Double? {
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return lbToKg(v, m.groupValues[2])
    }

    /** Angka sebelum "x" yang lebih mirip beban (>= 20 atau berdesimal) daripada jumlah set. */
    private fun isWeightLike(s: String): Boolean {
        val v = s.toDoubleOrNull() ?: return false
        return v >= 20.0 || s.contains('.')
    }

    private fun addGroup(
        def: Exercises.Def, sets: Int, reps: Int, kg: Double, out: MutableList<WorkoutEntry>, warns: MutableList<Warning>,
        noWeightKnown: Boolean = false
    ) {
        if (reps !in 1..MAX_REPS || sets !in 1..MAX_SETS || kg < 0.0 || kg > MAX_KG) {
            warns.add(Warning(WarnKind.ODD_NUMBER, def.key)); return
        }
        if (noWeightKnown) warns.add(Warning(WarnKind.NO_WEIGHT, def.key))
        out.add(WorkoutEntry(def.key, sets = sets, reps = reps, weightKg = Math.round(kg * 100) / 100.0))
    }
}

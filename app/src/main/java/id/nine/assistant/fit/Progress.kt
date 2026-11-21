package id.nine.assistant.fit

import java.time.LocalDate
import java.time.ZoneId

/** Hitungan progres latihan dan berat badan. Kotlin murni. */
object Progress {

    /** Perkiraan 1RM (Epley). Hanya dianggap layak untuk 1..12 rep; selain itu null. */
    fun e1rm(kg: Double, reps: Int): Double? = when {
        kg <= 0.0 || reps < 1 || reps > 12 -> null
        reps == 1 -> kg
        else -> kg * (1.0 + reps / 30.0)
    }

    fun volume(e: WorkoutEntry): Double = e.sets * e.reps * e.weightKg

    class Day(val date: LocalDate, val bestKg: Double, val bestReps: Int, val bestE1rm: Double?, val volume: Double, val sets: Int, val totalReps: Int)

    class Stats(
        val key: String,
        val days: List<Day>,
        val prKg: Double,
        val prKgDate: LocalDate?,
        val prE1rm: Double?,
        val maxReps: Int,
        val totalSessions: Int
    )

    fun stats(data: FitData, key: String, zone: ZoneId): Stats? {
        val byDay = java.util.TreeMap<LocalDate, ArrayList<WorkoutEntry>>()
        for (w in data.workouts) {
            val d = FitTime.day(w.time, zone)
            for (e in w.entries) if (e.exercise == key) byDay.getOrPut(d) { ArrayList() }.add(e)
        }
        if (byDay.isEmpty()) return null
        val days = ArrayList<Day>()
        var prKg = 0.0
        var prKgDate: LocalDate? = null
        var prE: Double? = null
        var maxReps = 0
        for ((d, list) in byDay) {
            var bestKg = 0.0
            var bestReps = 0
            var bestE: Double? = null
            var vol = 0.0
            var sets = 0
            var reps = 0
            for (e in list) {
                if (e.weightKg > bestKg || (e.weightKg == bestKg && e.reps > bestReps)) { bestKg = e.weightKg; bestReps = e.reps }
                e1rm(e.weightKg, e.reps)?.let { if (bestE == null || it > bestE!!) bestE = it }
                vol += volume(e)
                sets += e.sets
                reps += e.sets * e.reps
                if (e.reps > maxReps) maxReps = e.reps
            }
            days.add(Day(d, bestKg, bestReps, bestE, vol, sets, reps))
            if (bestKg > prKg) { prKg = bestKg; prKgDate = d }
            bestE?.let { if (prE == null || it > prE!!) prE = it }
        }
        return Stats(key, days, prKg, prKgDate, prE, maxReps, days.size)
    }

    enum class PrKind { FIRST, WEIGHT, E1RM, REPS }

    class Pr(val exercise: String, val kind: PrKind, val kg: Double, val reps: Int)

    /** Rekor baru dibanding catatan SEBELUM [entries] disimpan. */
    fun detectPrs(before: FitData, entries: List<WorkoutEntry>, zone: ZoneId): List<Pr> {
        val out = ArrayList<Pr>()
        val seen = HashSet<String>()
        for (e in entries) {
            if (!seen.add(e.exercise)) continue
            val def = Exercises.byKey[e.exercise] ?: continue
            if (def.kind == Exercises.Kind.CARDIO || def.kind == Exercises.Kind.HOLD) continue
            val prior = stats(before, e.exercise, zone)
            val mine = entries.filter { it.exercise == e.exercise }
            val topKg = mine.maxOf { it.weightKg }
            val topReps = mine.maxOf { it.reps }
            val topE = mine.mapNotNull { e1rm(it.weightKg, it.reps) }.maxOrNull()
            if (prior == null) { out.add(Pr(e.exercise, PrKind.FIRST, topKg, mine.first { it.weightKg == topKg }.reps)); continue }
            if (topKg > prior.prKg + 1e-9) {
                out.add(Pr(e.exercise, PrKind.WEIGHT, topKg, mine.first { it.weightKg == topKg }.reps))
            } else if (topE != null && prior.prE1rm != null && topE > prior.prE1rm + 0.5) {
                val best = mine.first { e1rm(it.weightKg, it.reps) == topE }
                out.add(Pr(e.exercise, PrKind.E1RM, best.weightKg, best.reps))
            } else if (def.kind == Exercises.Kind.BODYWEIGHT && topReps > prior.maxReps) {
                out.add(Pr(e.exercise, PrKind.REPS, 0.0, topReps))
            }
        }
        return out
    }

    /** Set keras per kelompok otot dalam [days] hari terakhir (sampai [now]). */
    fun weeklySets(data: FitData, now: Long, zone: ZoneId, days: Int = 7): Map<Exercises.Group, Int> {
        val from = FitTime.startOf(FitTime.day(now, zone).minusDays((days - 1).toLong()), zone)
        val out = java.util.EnumMap<Exercises.Group, Int>(Exercises.Group::class.java)
        for (w in data.workouts) {
            if (w.time < from || w.time > now + 86_400_000L) continue
            for (e in w.entries) {
                val def = Exercises.byKey[e.exercise] ?: continue
                if (def.kind == Exercises.Kind.CARDIO) continue
                out[def.group] = (out[def.group] ?: 0) + e.sets
            }
        }
        return out
    }

    fun trainingDays(data: FitData, now: Long, zone: ZoneId, days: Int): Int {
        val today = FitTime.day(now, zone)
        val from = today.minusDays((days - 1).toLong())
        return data.workouts.map { FitTime.day(it.time, zone) }.filter { !it.isBefore(from) && !it.isAfter(today) }.toSet().size
    }

    /** Hari berturut-turut (sampai hari ini atau kemarin) dengan minimal satu latihan. */
    fun streak(data: FitData, now: Long, zone: ZoneId): Int {
        val days = data.workouts.map { FitTime.day(it.time, zone) }.toSet()
        var d = FitTime.day(now, zone)
        if (d !in days) d = d.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    class NextWeight(val kg: Double, val repsTarget: Int, val increase: Boolean, val lastKg: Double, val lastReps: Int)

    /** Progresi ganda: naik beban bila rep atas tercapai, selain itu kejar satu rep lagi dengan beban yang sama. */
    fun nextWeight(stats: Stats, def: Exercises.Def): NextWeight? {
        if (def.kind != Exercises.Kind.STRENGTH) return null
        val last = stats.days.last()
        if (last.bestKg <= 0.0) return null
        return if (last.bestReps >= 12) NextWeight(last.bestKg + def.step, 8, true, last.bestKg, last.bestReps)
        else NextWeight(last.bestKg, (last.bestReps + 1).coerceAtLeast(6), false, last.bestKg, last.bestReps)
    }

    // ------------------------------------------------------------------ berat badan

    class WeightTrend(
        val latest: WeightEntry,
        val first: WeightEntry,
        val avg7: Double?,
        val prevAvg7: Double?,
        /** Perubahan per minggu (kg) dari regresi sederhana atas 28 hari terakhir; null bila data kurang. */
        val perWeek: Double?
    )

    fun weightTrend(data: FitData, now: Long, zone: ZoneId): WeightTrend? {
        val ws = data.weights.sortedBy { it.time }
        if (ws.isEmpty()) return null
        val today = FitTime.day(now, zone)
        fun avg(from: Long, to: Long): Double? {
            val xs = ws.filter { it.time in from until to }
            return if (xs.isEmpty()) null else xs.map { it.kg }.average()
        }
        val s7 = FitTime.startOf(today.minusDays(6), zone)
        val sPrev = FitTime.startOf(today.minusDays(13), zone)
        val tomorrow = FitTime.startOf(today.plusDays(1), zone)
        val a7 = avg(s7, tomorrow)
        val p7 = avg(sPrev, s7)
        val from28 = FitTime.startOf(today.minusDays(27), zone)
        val recent = ws.filter { it.time >= from28 }
        var perWeek: Double? = null
        if (recent.size >= 3) {
            val spanDays = (recent.last().time - recent.first().time) / 86_400_000.0
            if (spanDays >= 7.0) {
                val xs = recent.map { (it.time - recent.first().time) / 86_400_000.0 }
                val ys = recent.map { it.kg }
                val mx = xs.average(); val my = ys.average()
                val den = xs.sumOf { (it - mx) * (it - mx) }
                if (den > 0.0) perWeek = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den * 7.0
            }
        }
        return WeightTrend(ws.last(), ws.first(), a7, p7, perWeek)
    }
}

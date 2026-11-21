package id.nine.assistant.fit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Perkiraan kebutuhan energi dan makronutrien. Rumus standar (Mifflin-St Jeor) dan rentang protein dari posisi
 * ISSN; ini PERKIRAAN awal untuk dipantau dan disesuaikan, bukan resep medis. Ada pagar pengaman: tidak ada target
 * defisit untuk di bawah 18 tahun atau berat badan kurang, dan kalori tidak turun di bawah batas bawah.
 */
object Targets {
    enum class Block { MINOR, UNDERWEIGHT_CUT, NEEDS_PROFILE }

    class Target(
        val bmr: Double,
        val bmrFromInbody: Boolean,
        val tdee: Double,
        val kcal: Double,
        val proteinG: Double,
        val proteinLow: Double,
        val proteinHigh: Double,
        val fatG: Double,
        val carbG: Double,
        val fiberG: Double,
        val waterMl: Double,
        val goal: Goal,
        /** Kode catatan: FLOOR (kalori dinaikkan ke batas bawah), OBESE_PROTEIN, INBODY_BMR, ASSUMED_ACTIVITY, ASSUMED_GOAL. */
        val notes: List<String>
    )

    class Result(val target: Target?, val block: Block?, val missing: List<String> = emptyList())

    fun mifflin(sex: Sex, kg: Double, cm: Double, age: Int): Double =
        10.0 * kg + 6.25 * cm - 5.0 * age + if (sex == Sex.MALE) 5.0 else -161.0

    fun bmi(kg: Double, cm: Double): Double = kg / ((cm / 100.0) * (cm / 100.0))

    private fun r1(d: Double) = round(d * 10.0) / 10.0

    /** Berat badan acuan untuk protein: berat sebenarnya, atau berat pada BMI 25 bila BMI >= 30 (agar tidak berlebihan). */
    private fun proteinWeight(kg: Double, cm: Double): Pair<Double, Boolean> {
        val b = bmi(kg, cm)
        return if (b >= 30.0) (25.0 * (cm / 100.0) * (cm / 100.0)) to true else kg to false
    }

    fun compute(p: Profile, latestKg: Double?, inbodyBmr: Double?): Result {
        val missing = ArrayList<String>()
        if (p.sex == null) missing.add("sex")
        if (p.age == null) missing.add("age")
        if (p.heightCm == null) missing.add("height")
        val kg = latestKg ?: p.weightKg
        if (kg == null) missing.add("weight")
        if (missing.isNotEmpty()) return Result(null, Block.NEEDS_PROFILE, missing)
        val sex = p.sex!!; val age = p.age!!; val cm = p.heightCm!!; val w = kg!!
        if (age < 18) return Result(null, Block.MINOR)
        val goalGiven = p.goal
        val goal = goalGiven ?: Goal.MAINTAIN
        val b = bmi(w, cm)
        if (goal == Goal.CUT && b < 18.5) return Result(null, Block.UNDERWEIGHT_CUT)
        val notes = ArrayList<String>()
        if (goalGiven == null) notes.add("ASSUMED_GOAL")
        val formula = mifflin(sex, w, cm, age)
        val useInbody = inbodyBmr != null && inbodyBmr > 800.0 && Math.abs(inbodyBmr - formula) / formula <= 0.25
        val bmr = if (useInbody) inbodyBmr!! else formula
        if (useInbody) notes.add("INBODY_BMR")
        val act = p.activity ?: Activity.LIGHT
        if (p.activity == null) notes.add("ASSUMED_ACTIVITY")
        val tdee = bmr * act.factor
        val raw = when (goal) {
            Goal.CUT -> tdee * 0.82
            Goal.BULK -> tdee * 1.10
            Goal.MAINTAIN, Goal.RECOMP -> tdee
        }
        val floor = if (sex == Sex.MALE) 1500.0 else 1200.0
        var kcal = raw
        if (kcal < floor) { kcal = floor; notes.add("FLOOR") }
        // Defisit tidak boleh lebih dari 25% dari kebutuhan.
        if (goal == Goal.CUT && kcal < tdee * 0.75) kcal = tdee * 0.75
        val (pw, obese) = proteinWeight(w, cm)
        if (obese) notes.add("OBESE_PROTEIN")
        val perKg = when (goal) {
            Goal.CUT, Goal.RECOMP -> 2.0
            Goal.BULK -> 1.8
            Goal.MAINTAIN -> 1.6
        }
        val protein = pw * perKg
        val fat = min(max(0.8 * w, 0.20 * kcal / 9.0), 0.35 * kcal / 9.0)
        val carbs = max(0.0, (kcal - protein * 4.0 - fat * 9.0) / 4.0)
        val fiber = min(38.0, max(25.0, 14.0 * kcal / 1000.0))
        return Result(
            Target(
                bmr = r1(bmr), bmrFromInbody = useInbody, tdee = round(tdee), kcal = round(kcal / 10.0) * 10.0,
                proteinG = round(protein), proteinLow = round(pw * 1.4), proteinHigh = round(pw * 2.2),
                fatG = round(fat), carbG = round(carbs), fiberG = fiber, waterMl = round(w * 35.0 / 100.0) * 100.0,
                goal = goal, notes = notes
            ),
            null
        )
    }
}

package id.nine.assistant.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TargetsAndProgressTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(y: Int, m: Int, d: Int, h: Int = 10) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ target

    @Test fun mifflinKnownValues() {
        assertEquals(1627.5, Targets.mifflin(Sex.MALE, 70.0, 170.0, 28), 0.01)
        assertEquals(1289.0, Targets.mifflin(Sex.FEMALE, 60.0, 160.0, 30), 0.01)
        assertEquals(24.22, Targets.bmi(70.0, 170.0), 0.01)
    }

    private fun prof(sex: Sex = Sex.MALE, age: Int = 28, cm: Double = 170.0, kg: Double = 70.0, goal: Goal? = null, act: Activity? = null) =
        Profile(sex = sex, age = age, heightCm = cm, weightKg = kg, goal = goal, activity = act)

    @Test fun maintenanceTarget() {
        val r = Targets.compute(prof(act = Activity.MODERATE, goal = Goal.MAINTAIN), null, null)
        val t = r.target!!
        assertEquals(1627.5 * 1.55, t.tdee, 1.0)
        assertEquals(t.tdee, t.kcal, 10.0)
        assertEquals(70.0 * 1.6, t.proteinG, 1.0)
        assertTrue(t.proteinLow < t.proteinG && t.proteinG < t.proteinHigh)
        assertTrue(t.kcal > 0 && t.fatG > 0 && t.carbG > 0)
        // makro hampir sama dengan kalori
        assertEquals(t.kcal, t.proteinG * 4 + t.carbG * 4 + t.fatG * 9, t.kcal * 0.04)
    }

    @Test fun cuttingHasModerateDeficitAndHighProtein() {
        val t = Targets.compute(prof(goal = Goal.CUT, act = Activity.MODERATE), null, null).target!!
        assertTrue("defisit 15-25%: ${t.kcal} vs ${t.tdee}", t.kcal >= t.tdee * 0.75 && t.kcal <= t.tdee * 0.85)
        assertEquals(140.0, t.proteinG, 1.0)
    }

    @Test fun bulkingHasSmallSurplus() {
        val t = Targets.compute(prof(goal = Goal.BULK, act = Activity.MODERATE), null, null).target!!
        assertTrue(t.kcal > t.tdee && t.kcal <= t.tdee * 1.12)
    }

    @Test fun floorsProtectAgainstTooLowCalories() {
        val small = Profile(sex = Sex.FEMALE, age = 60, heightCm = 150.0, weightKg = 52.0, goal = Goal.CUT, activity = Activity.LOW)
        val t = Targets.compute(small, null, null).target!!
        assertTrue("batas bawah wanita 1200: ${t.kcal}", t.kcal >= 1200.0)
        assertTrue(t.notes.contains("FLOOR"))
        val male = Profile(sex = Sex.MALE, age = 60, heightCm = 160.0, weightKg = 60.0, goal = Goal.CUT, activity = Activity.LOW)
        assertTrue(Targets.compute(male, null, null).target!!.kcal >= 1500.0)
    }

    @Test fun minorsGetNoTargets() {
        val r = Targets.compute(prof(age = 16, goal = Goal.CUT), null, null)
        assertNull(r.target); assertEquals(Targets.Block.MINOR, r.block)
        assertEquals(Targets.Block.MINOR, Targets.compute(prof(age = 17), null, null).block)
        assertNotNull(Targets.compute(prof(age = 18), null, null).target)
    }

    @Test fun noCutWhenUnderweight() {
        val r = Targets.compute(prof(cm = 175.0, kg = 52.0, goal = Goal.CUT), null, null)
        assertEquals(Targets.Block.UNDERWEIGHT_CUT, r.block); assertNull(r.target)
        assertNotNull(Targets.compute(prof(cm = 175.0, kg = 52.0, goal = Goal.BULK), null, null).target)
        // latestKg menggantikan berat profil
        assertEquals(Targets.Block.UNDERWEIGHT_CUT, Targets.compute(prof(kg = 80.0, goal = Goal.CUT), 50.0, null).block)
    }

    @Test fun missingProfileIsListed() {
        val r = Targets.compute(Profile(sex = Sex.MALE), null, null)
        assertEquals(Targets.Block.NEEDS_PROFILE, r.block)
        assertEquals(setOf("age", "height", "weight"), r.missing.toSet())
    }

    @Test fun inbodyBmrUsedOnlyWhenPlausible() {
        val base = prof(act = Activity.LIGHT)
        val formula = Targets.compute(base, null, null).target!!
        val ok = Targets.compute(base, null, formula.bmr * 1.1).target!!
        assertTrue(ok.bmrFromInbody)
        val absurd = Targets.compute(base, null, formula.bmr * 2.0).target!!
        assertTrue(!absurd.bmrFromInbody)
        assertTrue(!Targets.compute(base, null, 300.0).target!!.bmrFromInbody)
    }

    @Test fun obeseProteinUsesReferenceWeight() {
        val t = Targets.compute(prof(cm = 165.0, kg = 120.0, goal = Goal.CUT, act = Activity.LIGHT), null, null).target!!
        assertTrue("protein tidak boleh 2 g/kg * 120: ${t.proteinG}", t.proteinG < 120.0 * 1.5)
        assertTrue(t.notes.contains("OBESE_PROTEIN"))
    }

    // ------------------------------------------------------------------ progres

    private fun data(vararg w: Triple<Long, String, List<Pair<Double, Int>>>): FitData {
        val d = FitData()
        for ((time, ex, sets) in w) d.workouts.add(Workout(d.newId(), time, sets.map { (kg, reps) -> WorkoutEntry(ex, 1, reps, kg) }))
        return d
    }

    @Test fun e1rm() {
        assertEquals(100.0, Progress.e1rm(100.0, 1)!!, 0.0)
        assertEquals(116.67, Progress.e1rm(100.0, 5)!!, 0.01)
        assertNull(Progress.e1rm(100.0, 15)); assertNull(Progress.e1rm(0.0, 5)); assertNull(Progress.e1rm(100.0, 0))
    }

    @Test fun prDetection() {
        val before = data(Triple(at(2026, 11, 1), "squat", listOf(100.0 to 5)))
        val first = Progress.detectPrs(FitData(), listOf(WorkoutEntry("squat", 3, 5, 80.0)), zone)
        assertEquals(Progress.PrKind.FIRST, first.single().kind)
        val w = Progress.detectPrs(before, listOf(WorkoutEntry("squat", 1, 3, 110.0)), zone)
        assertEquals(Progress.PrKind.WEIGHT, w.single().kind); assertEquals(110.0, w.single().kg, 0.0)
        val none = Progress.detectPrs(before, listOf(WorkoutEntry("squat", 3, 5, 90.0)), zone)
        assertTrue(none.isEmpty())
        val e = Progress.detectPrs(before, listOf(WorkoutEntry("squat", 1, 8, 100.0)), zone)
        assertEquals(Progress.PrKind.E1RM, e.single().kind)
        val pu = data(Triple(at(2026, 11, 1), "push_up", listOf(0.0 to 20)))
        assertEquals(Progress.PrKind.REPS, Progress.detectPrs(pu, listOf(WorkoutEntry("push_up", 1, 25, 0.0)), zone).single().kind)
        // kardio tidak punya rekor beban
        assertTrue(Progress.detectPrs(FitData(), listOf(WorkoutEntry("running", minutes = 30.0, km = 5.0)), zone).isEmpty())
    }

    @Test fun statsAndNextWeight() {
        val d = data(
            Triple(at(2026, 11, 1), "bench_press", listOf(60.0 to 8, 60.0 to 8)),
            Triple(at(2026, 11, 8), "bench_press", listOf(62.5 to 8)),
            Triple(at(2026, 11, 15), "bench_press", listOf(62.5 to 12))
        )
        val st = Progress.stats(d, "bench_press", zone)!!
        assertEquals(3, st.totalSessions); assertEquals(62.5, st.prKg, 0.0); assertEquals(12, st.maxReps)
        val def = Exercises.byKey["bench_press"]!!
        val nw = Progress.nextWeight(st, def)!!
        assertTrue(nw.increase); assertEquals(62.5 + def.step, nw.kg, 0.0)
        val d2 = data(Triple(at(2026, 11, 1), "bench_press", listOf(60.0 to 8)))
        val nw2 = Progress.nextWeight(Progress.stats(d2, "bench_press", zone)!!, def)!!
        assertTrue(!nw2.increase); assertEquals(60.0, nw2.kg, 0.0); assertEquals(9, nw2.repsTarget)
        assertNull(Progress.stats(d, "squat", zone))
    }

    @Test fun weeklySetsAndTrainingDays() {
        val now = at(2026, 11, 21)
        val d = FitData()
        d.workouts.add(Workout(1, at(2026, 11, 20), listOf(WorkoutEntry("bench_press", 3, 8, 60.0), WorkoutEntry("squat", 3, 5, 80.0))))
        d.workouts.add(Workout(2, at(2026, 11, 21, 6), listOf(WorkoutEntry("bench_press", 2, 8, 60.0), WorkoutEntry("running", minutes = 20.0))))
        d.workouts.add(Workout(3, at(2026, 11, 1), listOf(WorkoutEntry("squat", 3, 5, 80.0))))
        val sets = Progress.weeklySets(d, now, zone)
        assertEquals(5, sets[Exercises.Group.CHEST]); assertEquals(3, sets[Exercises.Group.LEGS])
        assertNull(sets[Exercises.Group.CARDIO])
        assertEquals(2, Progress.trainingDays(d, now, zone, 7))
        assertEquals(3, Progress.trainingDays(d, now, zone, 30))
    }

    @Test fun weightTrend() {
        val now = at(2026, 11, 21)
        val d = FitData()
        for (i in 0..27) d.weights.add(WeightEntry(d.newId(), at(2026, 11, 21, 7) - i * 86_400_000L, 72.0 + i * 0.1))
        val t = Progress.weightTrend(d, now, zone)!!
        assertEquals(72.0, t.latest.kg, 0.001)
        assertNotNull(t.avg7); assertNotNull(t.prevAvg7)
        assertEquals(-0.7, t.perWeek!!, 0.05)
        assertNull(Progress.weightTrend(FitData(), now, zone))
        // data kurang: tidak ada laju
        val few = FitData(); few.weights.add(WeightEntry(1, now, 70.0))
        assertNull(Progress.weightTrend(few, now, zone)!!.perWeek)
    }
}

package id.nine.assistant.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutParserTest {
    private fun parse(s: String) = WorkoutParser.parse(s)

    @Test fun setsRepsWeightInAnyOrder() {
        for (s in listOf("bench press 60 kg 3x8", "bench press 3x8 60 kg", "60 kg 3x8 bench press", "bench press 60 kg 3 set 8 rep", "bench press 60kg 3 sets of 8 reps")) {
            val e = parse(s)!!.entries
            assertEquals(s, 1, e.size)
            assertEquals(s, 3, e[0].sets); assertEquals(s, 8, e[0].reps); assertEquals(s, 60.0, e[0].weightKg, 0.0)
        }
    }

    @Test fun pairsOfWeightTimesReps() {
        val e = parse("squat 80x5, 90x3")!!.entries
        assertEquals(2, e.size)
        assertEquals(80.0, e[0].weightKg, 0.0); assertEquals(5, e[0].reps)
        assertEquals(90.0, e[1].weightKg, 0.0); assertEquals(3, e[1].reps)
    }

    @Test fun severalExercisesInOneUtterance() {
        val e = parse("tadi aku squat 100 kg 5 set 5 rep dan deadlift 120 kg 3x5")!!.entries
        assertEquals(listOf("squat", "deadlift"), e.map { it.exercise })
        assertEquals(5, e[0].sets); assertEquals(100.0, e[0].weightKg, 0.0)
        assertEquals(3, e[1].sets); assertEquals(120.0, e[1].weightKg, 0.0)
    }

    @Test fun bodyweightAndHold() {
        val p = parse("push up 20")!!.entries[0]
        assertEquals("push_up", p.exercise); assertEquals(20, p.reps); assertEquals(0.0, p.weightKg, 0.0)
        val pl = parse("plank 45 detik")!!.entries[0]
        assertEquals(45, pl.seconds)
        val pl2 = parse("plank 3 set 1 menit")!!.entries[0]
        assertEquals(60, pl2.seconds); assertEquals(3, pl2.sets)
    }

    @Test fun cardio() {
        val r = parse("lari 5 km 30 menit")!!.entries[0]
        assertEquals("running", r.exercise); assertEquals(5.0, r.km, 0.0); assertEquals(30.0, r.minutes, 0.0)
        val c = parse("sepeda 1 jam")!!.entries[0]
        assertEquals(60.0, c.minutes, 0.0)
        val w = parse("jalan kaki 45 menit")!!.entries[0]
        assertEquals("walking", w.exercise); assertEquals(45.0, w.minutes, 0.0)
    }

    @Test fun poundsConvertToKg() {
        val e = parse("bench press 135 lbs 3x5")!!.entries[0]
        assertEquals(61.23, e.weightKg, 0.01)
    }

    @Test fun yesterday() { assertEquals(-1, parse("kemarin squat 80 kg 5x5")!!.dayOffset) }

    @Test fun warningsInsteadOfGuessing() {
        val noWeight = parse("squat 3x8")!!
        assertEquals(1, noWeight.entries.size)
        assertEquals(WorkoutParser.WarnKind.NO_WEIGHT, noWeight.warnings.single().kind)
        val noReps = parse("bench press 60 kg")!!
        assertTrue(noReps.entries.isEmpty())
        assertEquals(WorkoutParser.WarnKind.NO_REPS, noReps.warnings.single().kind)
        val noAmount = parse("lari")!!
        assertEquals(WorkoutParser.WarnKind.NO_AMOUNT, noAmount.warnings.single().kind)
        val odd = parse("squat 9000 kg 3x8")!!
        assertTrue(odd.entries.isEmpty()); assertEquals(WorkoutParser.WarnKind.ODD_NUMBER, odd.warnings.single().kind)
        val odd2 = parse("push up 5000")!!
        assertTrue(odd2.entries.isEmpty())
    }

    @Test fun noExerciseNoParse() {
        assertNull(parse("3x8 60 kg"))
        assertNull(parse("halo nine"))
        assertNull(parse(""))
    }

    @Test fun spokenNumbers() {
        val e = parse("bench press enam puluh kg tiga kali delapan")!!.entries
        assertTrue("angka kata harus dibaca: $e", e.isNotEmpty())
    }
}

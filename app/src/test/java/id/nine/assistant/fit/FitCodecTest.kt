package id.nine.assistant.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCodecTest {
    private fun sample(): FitData {
        val d = FitData()
        d.profile = Profile(Sex.FEMALE, 29, 163.5, 58.2, Goal.RECOMP, Activity.MODERATE, 4)
        d.workouts.add(Workout(d.newId(), 1_700_000_000_000L, listOf(
            WorkoutEntry("bench_press", 3, 8, 62.5),
            WorkoutEntry("lari", 1, 0, 0.0, 30.0, 5.2, 0),
            WorkoutEntry("plank", 3, 0, 0.0, 0.0, 0.0, 45)
        )))
        d.weights.add(WeightEntry(d.newId(), 1_700_000_100_000L, 58.2))
        d.meals.add(Meal(d.newId(), 1_700_000_200_000L, MealType.LUNCH, listOf(
            MealItem("nasi goreng; \"spesial\"\nbaris dua", 1.5, "porsi", 540.5, 14.2, 70.0, 20.1, true),
            MealItem("telur", 2.0, "butir", 140.0, 12.0, 1.0, 10.0, false)
        )))
        d.inbody.add(
            InBodyReading(
                time = 1_700_000_300_000L, weightKg = 58.2, smmKg = 23.1, bodyFatKg = 14.0, pbf = 24.1, bmi = 21.8, visceral = 5.0,
                bmr = 1320.0, tbwL = 32.0, ffmKg = 44.2, score = 74.0, heightCm = 163.5, age = 29, sex = Sex.FEMALE,
                ranges = mapOf("smm" to Pair(20.0, 24.5), "pbf" to Pair(18.0, 28.0)),
                segments = mapOf("RA" to 2.1, "LA" to 2.0, "TR" to 19.4, "RL" to 6.4, "LL" to 6.3),
                segmentPct = mapOf("RA" to 108.5, "LL" to 96.0)
            )
        )
        return d
    }

    @Test fun roundTripKeepsEverything() {
        val a = sample()
        val b = FitCodec.decode(FitCodec.encode(a))
        assertEquals(a.profile, b.profile)
        assertEquals(a.workouts, b.workouts)
        assertEquals(a.weights, b.weights)
        assertEquals(a.meals, b.meals)
        assertEquals(a.inbody, b.inbody)
        assertEquals(a.nextId, b.nextId)
    }

    @Test fun encodeTwiceIsStable() {
        val once = FitCodec.encode(sample())
        assertEquals(once, FitCodec.encode(FitCodec.decode(once)))
    }

    @Test fun ids_continueAfterReload() {
        val b = FitCodec.decode(FitCodec.encode(sample()))
        val ids = (b.workouts.map { it.id } + b.weights.map { it.id } + b.meals.map { it.id })
        assertTrue(ids.toSet().size == ids.size)
        assertTrue(b.newId() > (ids.maxOrNull() ?: 0L))
    }

    @Test fun garbageDecodesToEmpty() {
        for (raw in listOf("", "   ", "not json", "{", "[1,2,3]", "{\"workouts\": 5}", "null", "\u0000\u0001", "{\"v\":1,\"meals\":[{\"id\":\"x\"}]}")) {
            val d = FitCodec.decode(raw)
            assertTrue(raw, d.workouts.isEmpty() && d.weights.isEmpty() && d.inbody.isEmpty())
            assertNull(raw, d.profile.sex)
        }
    }

    @Test fun emptyDataRoundTrips() {
        val d = FitCodec.decode(FitCodec.encode(FitData()))
        assertTrue(d.workouts.isEmpty() && d.meals.isEmpty() && d.weights.isEmpty() && d.inbody.isEmpty())
    }

    @Test fun memoryStoreRoundTrip() {
        val s = MemoryFitStore()
        s.save(sample())
        assertEquals(3, s.load().workouts.first().entries.size)
        assertEquals(1, s.load().inbody.size)
    }
}

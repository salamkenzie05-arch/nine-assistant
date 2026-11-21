package id.nine.assistant.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthIntentTest {
    private fun p(s: String) = HealthIntent.parse(s)

    @Test fun workoutLogs() {
        val r = p("bench press 60 kg 3x8") as HealthRequest.LogWorkout
        assertEquals(1, r.entries.size)
        assertEquals("bench_press", r.entries[0].exercise)
        assertEquals(3, r.entries[0].sets); assertEquals(8, r.entries[0].reps); assertEquals(60.0, r.entries[0].weightKg, 0.0)
        val y = p("kemarin squat 80 kg 5x5") as HealthRequest.LogWorkout
        assertEquals(-1, y.dayOffset)
        val c = p("lari 5 km 30 menit") as HealthRequest.LogWorkout
        assertEquals(5.0, c.entries[0].km, 0.0); assertEquals(30.0, c.entries[0].minutes, 0.0)
        assertTrue(p("I ran 5 km in 30 minutes") is HealthRequest.LogWorkout)
        assertTrue(p("habis gym") is HealthRequest.WorkoutPrompt)
        assertTrue(p("log workout") is HealthRequest.WorkoutPrompt)
    }

    @Test fun workoutQueries() {
        assertEquals("squat", (p("progres squat") as HealthRequest.ExerciseProgress).exercise)
        assertEquals("bench_press", (p("saran beban bench press") as HealthRequest.SuggestWeight).exercise)
        assertEquals(7, (p("riwayat latihan") as HealthRequest.WorkoutHistory).days)
        assertEquals(4, (p("program latihan 4 hari") as HealthRequest.Program).days)
        assertTrue(p("ringkasan mingguan") is HealthRequest.WeekSummary)
    }

    @Test fun weightAndProfile() {
        assertEquals(72.5, (p("berat badan 72,5") as HealthRequest.LogBodyWeight).kg, 0.0)
        assertEquals(72.5, (p("weight 72.5") as HealthRequest.LogBodyWeight).kg, 0.0)
        assertEquals(72.5, (p("my weight is 72.5") as HealthRequest.LogBodyWeight).kg, 0.0)
        assertTrue(p("tren berat badan") is HealthRequest.WeightTrend)
        val pr = p("aku laki laki 28 tahun tinggi 170 berat 72") as HealthRequest.SetProfile
        assertEquals(Sex.MALE, pr.sex); assertEquals(28, pr.age); assertEquals(170.0, pr.heightCm!!, 0.0); assertEquals(72.0, pr.weightKg!!, 0.0)
        val ac = p("olahraga 4x seminggu") as HealthRequest.SetProfile
        assertEquals(Activity.MODERATE, ac.activity); assertEquals(4, ac.days)
        assertEquals(Goal.CUT, (p("target saya cutting") as HealthRequest.SetGoal).goal)
        assertEquals(Goal.BULK, (p("aku mau bulking") as HealthRequest.SetGoal).goal)
        assertTrue(p("target kalori saya") is HealthRequest.ShowTargets)
        assertTrue(p("berapa kalori yang saya butuhkan") is HealthRequest.ShowTargets)
    }

    @Test fun meals() {
        val m = p("sarapan nasi goreng 1 piring dan telur ceplok 2 butir") as HealthRequest.LogMeal
        assertEquals(MealType.BREAKFAST, m.parsed.type)
        assertEquals(listOf("nasi_goreng", "telur_goreng"), m.parsed.items.map { it.name })
        assertTrue(p("makan apa hari ini") is HealthRequest.MealsDay)
        assertTrue(p("menu hari ini") is HealthRequest.MealsDay)
        assertEquals("nasi_goreng", (p("berapa kalori nasi goreng") as HealthRequest.FoodInfo).foodKey)
        assertEquals(1, ((p("kemarin makan nasi goreng") as HealthRequest.LogMeal).parsed.dayOffset * -1))
    }

    @Test fun knowledge() {
        assertEquals("deficit", (p("apa itu defisit kalori") as HealthRequest.Ask).topicKey)
        assertEquals("protein_need", (p("manfaat protein") as HealthRequest.Ask).topicKey)
        assertEquals("creatine", (p("kreatin aman gak") as HealthRequest.Ask).topicKey)
        assertTrue(p("how much protein should I eat") is HealthRequest.Ask)
    }

    @Test fun inbodyAndUndo() {
        assertTrue(p("analisis inbody") is HealthRequest.InBodyStart)
        val m = p("inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600") as HealthRequest.InBodyManual
        assertEquals(70.0, m.reading.weightKg!!, 0.0); assertEquals(1600.0, m.reading.bmr!!, 0.0)
        assertEquals(HealthRequest.UndoKind.MEAL, (p("hapus makan terakhir") as HealthRequest.Undo).kind)
        assertEquals(HealthRequest.UndoKind.INBODY, (p("hapus inbody terakhir") as HealthRequest.Undo).kind)
        assertEquals(HealthRequest.UndoKind.WORKOUT, (p("hapus latihan terakhir") as HealthRequest.Undo).kind)
        assertTrue(p("pelatih fitness") is HealthRequest.Help)
    }

    /** Perintah dan obrolan lain tidak boleh diserobot. */
    @Test fun notHealth() {
        val others = listOf(
            "makan siang 25 ribu", "mau makan nasi goreng", "3x8", "5 x 5", "berapa 12 kali 8", "catat beli tiket 3x",
            "ingatkan aku gym jam 5", "ingatkan aku lari jam 5", "timer 30 menit lari", "alarm jam 5 pagi untuk lari 5 km", "buka gym app",
            "aku mau curhat", "aku capek banget makan hati", "nasi goreng enak", "protein", "hitung 3x8", "makan nasi yuk",
            "hapus catatan terakhir", "catat makan malam bareng keluarga", "ingatkan makan siang jam 12", "makan malam jam 7",
            "beli telur 2 butir", "pesan nasi goreng", "kamu sudah makan?", "aku diet", "aku gemuk", "berat badan", "kalori", "volume 50",
            "set volume ke 70", "gym jam 5", "aku akan gym besok", "buka aplikasi gym", "putar lagu workout", "suhu 20 derajat",
            "alarm jam 6", "telepon ibu", "jam berapa sekarang", "cuaca hari ini", "halo nine"
        )
        for (s in others) assertNull("seharusnya bukan kesehatan: $s", p(s))
    }

    @Test fun eatingConcernGuard() {
        val bad = listOf("aku muntah setelah makan biar kurus", "makan 500 kalori sehari", "aku sengaja tidak makan seharian", "aku habis binge lagi", "aku takut banget gemuk", "aku takut makan")
        for (s in bad) assertTrue("harus terdeteksi: $s", HealthIntent.eatingConcern(s))
        for (s in bad) assertTrue(p(s) is HealthRequest.EatingConcern)
        val ok = listOf("aku makan 2000 kalori sehari", "makan siang nasi goreng", "aku sedang diet", "target kalori saya", "puasa senin kamis", "aku lapar", "aku takut gemuk lagi", "mau cutting biar gak gemuk")
        for (s in ok) assertTrue("tidak boleh terdeteksi: $s", !HealthIntent.eatingConcern(s))
    }

    @Test fun parseNeverThrows() {
        val junk = listOf("", " ", "???", "😀", "a".repeat(5000), "9".repeat(400), "kg kg kg", "x x x", "squat " + "9".repeat(30) + " kg", "sarapan " + "nasi ".repeat(300),
            "berat badan 99999999999999999999", "umur 0", "inbody " + "berat ".repeat(100))
        for (s in junk) { p(s) }
        assertNotNull(p("bench press 60 kg 3x8"))
    }

    @Test fun englishMealQueries() {
        assertEquals(0, (p("Today's meals") as HealthRequest.MealsDay).daysAgo)
        assertEquals(1, (p("yesterday's meals") as HealthRequest.MealsDay).daysAgo)
        assertEquals(0, (p("show me today's menu") as HealthRequest.MealsDay).daysAgo)
        assertEquals(1, (p("my meals yesterday") as HealthRequest.MealsDay).daysAgo)
        // pernyataan makan tidak boleh menjadi pertanyaan
        assertTrue(p("I ate fried rice and eggs") is HealthRequest.LogMeal)
        assertTrue(p("I had two eggs today") is HealthRequest.LogMeal)
    }

    @Test fun mentionsInBodyCaption() {
        assertTrue(HealthIntent.mentionsInBody("hasil inbody saya"))
        assertTrue(HealthIntent.mentionsInBody("Analisis InBody"))
        assertTrue(HealthIntent.mentionsInBody("in body result"))
        assertTrue(HealthIntent.mentionsInBody("my InBody scan"))
        assertFalse(HealthIntent.mentionsInBody(""))
        assertFalse(HealthIntent.mentionsInBody("ini gambar apa?"))
        assertFalse(HealthIntent.mentionsInBody("what is in body of text"))
    }

    @Test fun phrasesNineSuggestsAreRecognised() {
        // Setiap contoh yang disarankan Nine dalam balasannya harus benar-benar dikenali.
        val health = listOf(
            "4 day workout plan", "I train 4 times a week", "I'm female 28 years old height 160 weight 55", "aktivitas saya sedang",
            "aku laki-laki 28 tahun tinggi 170 berat 72", "my activity is moderate", "olahraga 4 kali seminggu", "analyse inbody",
            "delete last meal", "delete last workout", "delete last inbody", "delete weight", "hapus berat badan", "hapus makan terakhir",
            "bulking", "maintenance", "target saya cutting", "my goal is bulking", "martabak 400 kalori", "martabak 400 calories",
            "ate martabak 400 calories", "suggest squat weight", "squat progress", "weight trend", "workout history", "what did I eat today",
            "calories in fried chicken", "my calorie target", "weight 72.5", "run 5 km 30 minutes", "breakfast fried rice 1 plate and 2 fried eggs",
            "makan nasi padang 700 kalori", "aku sedang cutting", "tujuanku cutting"
        )
        for (s in health) assertNotNull(s, p(s))
        assertEquals(HealthRequest.UndoKind.MEAL, (p("delete last meal") as HealthRequest.Undo).kind)
        assertEquals(HealthRequest.UndoKind.WORKOUT, (p("delete last workout") as HealthRequest.Undo).kind)
        assertEquals(HealthRequest.UndoKind.INBODY, (p("delete last inbody") as HealthRequest.Undo).kind)
        assertEquals(Goal.BULK, (p("bulking") as HealthRequest.SetGoal).goal)
        assertEquals(Goal.MAINTAIN, (p("maintenance") as HealthRequest.SetGoal).goal)
        assertEquals(Goal.CUT, (p("aku sedang cutting") as HealthRequest.SetGoal).goal)
        assertEquals(Goal.CUT, (p("tujuanku cutting") as HealthRequest.SetGoal).goal)
        val prof = p("I train 4 times a week") as HealthRequest.SetProfile
        assertEquals(Activity.MODERATE, prof.activity)
    }

    @Test fun bareCalorieMealDoesNotCatchOtherThings() {
        for (s in listOf("alarm 500 kalori", "bakar 500 kalori", "jalan 500 kalori", "berapa 500 kalori", "yoga 300 kalori", "renang 400 kalori", "sisa 500 kalori")) {
            assertFalse(s, p(s) is HealthRequest.LogMeal)
        }
        assertTrue(p("martabak 400 kalori") is HealthRequest.LogMeal)
    }
}

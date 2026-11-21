package id.nine.assistant.fit

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthEngineTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private var now = LocalDate.of(2026, 11, 21).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val store = MemoryFitStore()
    private val engine = HealthEngine(store, { now }, zone)
    private val id = AppLang.ID
    private val en = AppLang.EN

    private fun say(s: String, l: AppLang = id): String {
        engine.draftTurn(s, l)?.let { return it }
        val req = HealthIntent.parse(s) ?: error("tidak dikenali: $s")
        return engine.handle(req, l)
    }

    private fun day(n: Int) { now += n * 86_400_000L }

    // ------------------------------------------------------------------ latihan

    @Test fun workoutLoggingPrsAndProgress() {
        val a = say("bench press 60 kg 3x8")
        assertTrue(a, a.contains("Latihan tercatat") && a.contains("Bench press 3×8 @ 60 kg"))
        assertTrue("catatan pertama: $a", a.contains("Catatan pertama"))
        assertTrue(a.contains("Volume angkat: 1440 kg"))
        day(3)
        val b = say("bench press 65 kg 3x8")
        assertTrue("rekor beban: $b", b.contains("Rekor beban baru"))
        day(3)
        val c = say("bench press 60 kg 3x8")
        assertFalse(c.contains("Rekor"))
        val p = say("progres bench press")
        assertTrue(p, p.contains("3 sesi") && p.contains("65 kg"))
        val s = say("saran beban bench press")
        assertTrue(s, s.contains("Saran berikutnya"))
        assertEquals(3, store.load().workouts.size)
    }

    @Test fun cardioAndBodyweightAndYesterday() {
        val a = say("lari 5 km 30 menit")
        assertTrue(a, a.contains("Lari 5 km, 30 menit") && a.contains("6:00/km"))
        val b = say("kemarin push up 3x20")
        assertTrue(b, b.contains("Latihan kemarin tercatat"))
        val w = store.load().workouts.maxByOrNull { it.id }!!
        assertEquals(LocalDate.of(2026, 11, 20), FitTime.day(w.time, zone))
        val h = say("riwayat latihan")
        assertTrue(h, h.contains("2 sesi"))
        assertTrue(say("progres lari").contains("Total jarak 5 km"))
    }

    @Test fun warningsExplainWhatWasNotLogged() {
        val a = say("squat 3x8")
        assertTrue(a, a.contains("bebannya tidak disebut"))
        assertEquals(1, store.load().workouts.size)
        val b = say("bench press 60 kg")
        assertTrue(b, b.contains("Belum ada yang tercatat") && b.contains("tidak dicatat"))
        assertEquals(1, store.load().workouts.size)
    }

    @Test fun suggestWithoutDataDoesNotInvent() {
        val s = say("saran beban squat")
        assertTrue(s, s.contains("Belum ada catatan"))
        assertTrue(say("progres squat").contains("Belum ada catatan"))
        assertTrue(say("riwayat latihan").contains("Belum ada latihan"))
    }

    @Test fun workoutPrompt() { assertTrue(say("habis gym").contains("Latihan apa tadi")) }

    // ------------------------------------------------------------------ berat

    @Test fun weightLogAndTrend() {
        assertTrue(say("berat badan 72,5").contains("72,5 kg tercatat"))
        day(1)
        val b = say("berat badan 72")
        assertTrue(b, b.contains("Turun 0,5 kg"))
        val t = say("tren berat badan")
        assertTrue(t, t.contains("Berat terakhir 72 kg") && t.contains("naik-turun"))
        assertTrue(say("hapus berat badan").contains("dihapus"))
        assertEquals(1, store.load().weights.size)
    }

    @Test fun hugeWeightJumpIsQueried() {
        say("berat badan 72")
        day(2)
        assertTrue(say("berat badan 80").contains("tidak biasa"))
    }

    @Test fun noWeightYet() { assertTrue(say("tren berat badan").contains("Belum ada catatan berat badan")) }

    // ------------------------------------------------------------------ profil & target

    @Test fun targetNeedsProfileThenShowsNumbers() {
        val a = say("target kalori saya")
        assertTrue(a, a.contains("butuh data dulu") && a.contains("jenis kelamin"))
        val p = say("aku laki laki 28 tahun tinggi 170 berat 70")
        assertTrue(p, p.contains("Profil disimpan") && p.contains("laki-laki") && p.contains("Perkiraan target"))
        val g = say("target saya cutting")
        assertTrue(g, g.contains("menurunkan lemak") && g.contains("Perkiraan target"))
        val t = say("target kalori saya")
        assertTrue(t, t.contains("Protein ±140 g") && t.contains("Mifflin"))
        assertTrue(t.contains("gangguan makan"))
        say("aktivitas saya sedang")
        assertEquals(Activity.MODERATE, store.load().profile.activity)
    }

    @Test fun minorGetsNoDietTargets() {
        say("aku perempuan 16 tahun tinggi 160 berat 50")
        val t = say("target kalori saya")
        assertTrue(t, t.contains("di bawah 18 tahun") && !t.contains("kkal"))
        val g = say("target saya cutting")
        assertTrue(g, g.contains("di bawah 18 tahun") && !g.contains("Perkiraan target"))
    }

    @Test fun underweightCannotCut() {
        say("aku laki laki 25 tahun tinggi 175 berat 52")
        val g = say("target saya cutting")
        assertTrue(g, g.contains("tidak membuat target menurunkan berat"))
        assertTrue("tujuan CUT tidak boleh tersimpan", store.load().profile.goal != Goal.CUT)
        val b = say("target saya bulking")
        assertTrue(b, b.contains("Perkiraan target"))
    }

    // ------------------------------------------------------------------ makanan

    @Test fun mealLoggingTotalsAndRemaining() {
        say("aku laki laki 28 tahun tinggi 170 berat 70")
        val m = say("sarapan nasi goreng 1 piring dan telur ceplok 2 butir")
        assertTrue(m, m.contains("Tercatat (sarapan") && m.contains("Nasi goreng 1 piring") && m.contains("Telur dadar/ceplok 2 butir"))
        assertTrue(m, m.contains("Total: 630 kkal"))
        assertTrue("sisa target: $m", m.contains("Hari ini:") && m.contains("sisa"))
        val day = say("makan apa hari ini")
        assertTrue(day, day.contains("sarapan: Nasi goreng, Telur dadar/ceplok") && day.contains("perkiraan"))
        assertTrue("saran protein: $day", day.contains("Protein masih kurang") && day.contains("Ide:"))
    }

    @Test fun mealWithoutProfileStillWorks() {
        val m = say("makan siang ayam goreng, nasi 1 piring, es teh")
        assertTrue(m, m.contains("makan siang") && m.contains("Atur profil"))
    }

    @Test fun unknownFoodIsFlaggedAndNotInvented() {
        val m = say("makan nasi dan sambal petai spesial")
        assertTrue(m, m.contains("Tidak kukenali"))
        assertTrue(store.load().meals.single().items.all { it.name == "nasi_putih" })
    }

    @Test fun yesterdayMeal() {
        say("kemarin makan nasi goreng")
        val y = say("makan apa kemarin")
        assertTrue(y, y.contains("kemarin") && y.contains("Nasi goreng"))
        assertTrue(say("makan apa hari ini").contains("Belum ada makanan"))
    }

    @Test fun foodInfoAndKnowledge() {
        val f = say("berapa kalori nasi goreng")
        assertTrue(f, f.contains("Nasi goreng") && f.contains("Per 100 g") && f.contains("Perkiraan"))
        val k = say("apa itu defisit kalori")
        assertTrue(k, k.contains("bukan nasihat medis"))
    }

    @Test fun absurdMealWarns() {
        val m = say("makan nasi goreng 12 piring")
        assertTrue(m, m.contains("besar"))
    }

    // ------------------------------------------------------------------ ringkasan, program, hapus

    @Test fun weeklySummary() {
        assertTrue(say("ringkasan mingguan").contains("Belum ada catatan 7 hari"))
        say("bench press 60 kg 3x8"); say("squat 80 kg 3x5"); say("berat badan 72"); say("sarapan nasi goreng 1 piring")
        val w = say("ringkasan mingguan")
        assertTrue(w, w.contains("Latihan: 1 hari") && w.contains("Set per kelompok") && w.contains("Berat:") && w.contains("Makan:"))
        assertTrue(w.contains("Belum dilatih minggu ini: punggung, bahu"))
    }

    @Test fun programTemplates() {
        for (d in 2..6) {
            val t = say("program latihan $d hari")
            assertTrue(t, t.contains("Program $d hari") && t.contains("dokter"))
        }
        say("olahraga 4x seminggu")
        assertTrue(say("program latihan").contains("Program 4 hari"))
    }

    @Test fun undo() {
        assertTrue(say("hapus makan terakhir").contains("Tidak ada"))
        say("sarapan nasi goreng 1 piring"); say("makan siang ayam goreng")
        assertTrue(say("hapus makan terakhir").contains("Ayam goreng"))
        assertEquals(1, store.load().meals.size)
        say("bench press 60 kg 3x8")
        assertTrue(say("hapus latihan terakhir").contains("Bench press"))
        assertEquals(0, store.load().workouts.size)
    }

    // ------------------------------------------------------------------ InBody

    private val reading = InBodyReading(
        weightKg = 72.0, smmKg = 33.4, bodyFatKg = 14.6, pbf = 20.3, bmi = 24.9, visceral = 7.0, bmr = 1690.0,
        heightCm = 170.0, age = 28, sex = Sex.MALE
    )

    @Test fun inbodyPhotoNeedsConfirmationBeforeSaving() {
        val start = say("analisis inbody")
        assertTrue(start, start.contains("Kirim foto") && engine.isAwaitingPhoto())
        val d = engine.proposeInBody(reading, id)
        assertTrue(d, d.contains("Aku membaca dari foto") && d.contains("Benar?") && d.contains("otot rangka 33,4"))
        assertTrue(engine.hasDraft()); assertFalse(engine.isAwaitingPhoto())
        assertEquals("belum tersimpan sebelum konfirmasi", 0, store.load().inbody.size)
        val ok = say("ya")
        assertTrue(ok, ok.contains("Hasil InBody tersimpan") && ok.contains("bukan diagnosis") && ok.contains("hapus inbody terakhir"))
        assertEquals(1, store.load().inbody.size)
        assertFalse(engine.hasDraft())
        // profil terisi dari lembar, berat masuk catatan berat
        val data = store.load()
        assertEquals(Sex.MALE, data.profile.sex); assertEquals(28, data.profile.age); assertEquals(170.0, data.profile.heightCm!!, 0.0)
        assertEquals(72.0, data.weights.single().kg, 0.0)
    }

    @Test fun inbodyCorrectionThenSave() {
        engine.proposeInBody(reading.copy(weightKg = 77.0), id)
        val c = engine.draftTurn("berat 72,5", id)!!
        assertTrue(c, c.contains("Diperbarui") && c.contains("berat 72,5"))
        say("ya")
        assertEquals(72.5, store.load().inbody.single().weightKg!!, 0.0)
    }

    @Test fun inbodyCancel() {
        engine.proposeInBody(reading, id)
        assertTrue(engine.draftTurn("batal", id)!!.contains("dibuang"))
        assertEquals(0, store.load().inbody.size)
        assertFalse(engine.hasDraft())
    }

    @Test fun draftIgnoresUnrelatedTalkAndExpires() {
        engine.proposeInBody(reading, id)
        assertNull(engine.draftTurn("jam berapa sekarang", id))
        assertNull(engine.draftTurn("bench press 60 kg 3x8", id))
        assertTrue(engine.hasDraft())
        now += HealthEngine.DRAFT_TTL_MS + 1000
        assertFalse(engine.hasDraft())
        assertNull(engine.draftTurn("ya", id))
        assertEquals(0, store.load().inbody.size)
    }

    @Test fun unreadablePhotoAsksForRetry() {
        val r = engine.proposeInBody(InBodyReading(weightKg = 70.0), id)
        assertTrue(r, r.contains("hanya bisa membaca 1 angka") && r.contains("ketik angkanya"))
        assertFalse(engine.hasDraft())
        val none = engine.proposeInBody(InBodyReading(), id)
        assertTrue(none, none.contains("tidak bisa membaca"))
    }

    @Test fun inconsistentPhotoReadingIsFlagged() {
        val d = engine.proposeInBody(reading.copy(pbf = 45.0), id)
        assertTrue(d, d.contains("tampak janggal"))
    }

    @Test fun manualInbodySavesDirectlyUnlessInconsistent() {
        val ok = say("inbody berat 72 otot 33.4 lemak 14.6 pbf 20.3 visceral 7 bmr 1690")
        assertTrue(ok, ok.contains("Tersimpan") && ok.contains("Salah simpan"))
        assertEquals(1, store.load().inbody.size)
        val bad = say("inbody berat 72 lemak 14.6 pbf 45")
        assertTrue(bad, bad.contains("Benar?"))
        assertEquals(1, store.load().inbody.size)
    }

    @Test fun inbodyHistoryAndCompare() {
        assertTrue(say("inbody terakhir").contains("Belum ada hasil InBody"))
        engine.proposeInBody(reading, id); say("ya")
        assertTrue(say("bandingkan inbody").contains("minimal dua"))
        day(30)
        engine.proposeInBody(reading.copy(weightKg = 70.5, smmKg = 33.9, bodyFatKg = 12.4, pbf = 17.6), id); say("ya")
        val cmp = say("bandingkan inbody")
        assertTrue(cmp, cmp.contains("→") && cmp.contains("rekomposisi"))
        assertTrue(say("inbody terakhir").contains("Hasil InBody terakhir"))
        assertTrue(say("hapus inbody terakhir").contains("dihapus"))
        assertEquals(1, store.load().inbody.size)
    }

    @Test fun inbodyBmrFeedsTargets() {
        say("aku laki laki 28 tahun tinggi 170 berat 72")
        engine.proposeInBody(reading, id); say("ya")
        assertTrue(say("target kalori saya").contains("BMR memakai hasil InBody"))
    }

    // ------------------------------------------------------------------ keselamatan & bahasa

    @Test fun eatingConcernGetsHumanSupportNoNumbers() {
        val r = engine.handle(HealthRequest.EatingConcern, id)
        assertTrue(r, r.contains("tidak akan menghitung kalori") && r.contains("112") && r.contains("Healing119") && r.contains("aku mau curhat"))
        assertTrue(!Regex("""\d{3,4}\s*kkal""").containsMatchIn(r))
        val e = engine.handle(HealthRequest.EatingConcern, en)
        assertTrue(e, e.contains("won't count calories") && e.contains("112"))
        assertEquals(0, store.load().meals.size)
    }

    @Test fun englishReplies() {
        val a = say("bench press 60 kg 3x8", en)
        assertTrue(a, a.contains("Workout logged") && a.contains("Lifting volume"))
        val m = say("breakfast 2 eggs and toast", en)
        assertTrue(m, m.contains("Logged (breakfast") && m.contains("estimated nutrition"))
        assertTrue(engine.help(en).contains("Fitness coach"))
        assertTrue(say("weight 72.5", en).contains("Weight 72.5 kg logged"))
    }

    @Test fun helpMentionsLimits() {
        val h = engine.help(id)
        assertTrue(h.contains("perkiraan") && h.contains("bukan nasihat medis"))
        assertEquals(h, say("bantuan fitness"))
    }

    @Test fun dataPersistsAcrossEngines() {
        say("bench press 60 kg 3x8"); say("berat badan 72"); say("sarapan nasi goreng 1 piring")
        val again = HealthEngine(store, { now }, zone)
        val d = store.load()
        assertEquals(1, d.workouts.size); assertEquals(1, d.weights.size); assertEquals(1, d.meals.size)
        assertTrue(again.handle(HealthRequest.WeightTrend, id).contains("72"))
    }

    @Test fun corruptStoreDoesNotCrash() {
        store.raw = "{ not json ]]]"
        assertTrue(say("riwayat latihan").contains("Belum ada latihan"))
        store.raw = ""
        assertTrue(say("berat badan 70").contains("tercatat"))
    }

    @Test fun everyRequestKindProducesText() {
        val all = listOf(
            HealthRequest.WorkoutPrompt, HealthRequest.WeightTrend, HealthRequest.ShowTargets, HealthRequest.WeekSummary,
            HealthRequest.InBodyStart, HealthRequest.InBodyLast, HealthRequest.InBodyCompare, HealthRequest.EatingConcern, HealthRequest.Help,
            HealthRequest.WorkoutHistory(7), HealthRequest.MealsDay(0), HealthRequest.Program(null), HealthRequest.Ask("nope"),
            HealthRequest.FoodInfo("nope"), HealthRequest.ExerciseProgress("nope"), HealthRequest.SuggestWeight("nope"),
            HealthRequest.Undo(HealthRequest.UndoKind.MEAL), HealthRequest.Undo(HealthRequest.UndoKind.INBODY),
            HealthRequest.SetProfile(), HealthRequest.SetGoal(Goal.RECOMP)
        )
        for (l in AppLang.values()) for (r in all) assertTrue("$r $l", engine.handle(r, l).isNotBlank())
    }

    @Test fun photoDisabledGivesTypedOnlyAndDoesNotAwait() {
        engine.photoEnabled = false
        val t = say("analisis inbody")
        assertTrue(t, t.contains("dimatikan") && t.contains("inbody berat"))
        assertFalse(engine.isAwaitingPhoto())
        val e = say("analyse my inbody", en)
        assertTrue(e, e.contains("switched off"))
        assertFalse(engine.isAwaitingPhoto())
        engine.photoEnabled = true
        val ok = say("analisis inbody")
        assertTrue(ok, ok.contains("tombol +"))
        assertTrue(engine.isAwaitingPhoto())
    }
}

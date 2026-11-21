package id.nine.assistant.fit

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InBodyTest {
    private val english = listOf(
        "InBody570",
        "ID 1234 Height 170cm Age 28 Gender Male Test Date / Time 2026.11.01. 08:30",
        "Total Body Water (L) 42.1 (39.4 ~ 48.2)",
        "Protein (kg) 11.3 (10.5 ~ 12.9)",
        "Minerals (kg) 4.0 (3.64 ~ 4.45)",
        "Body Fat Mass (kg) 14.6 (8.7 ~ 13.9)",
        "Weight (kg) 72.0 (58.7 ~ 79.4)",
        "Skeletal Muscle Mass (kg) 33.4 (29.5 ~ 36.1)",
        "BMI (kg/m2) 24.9 (18.5 ~ 25.0)",
        "Percent Body Fat (%) 20.3 (10.0 ~ 20.0)",
        "Right Arm 3.45 kg 105.2 % Left Arm 3.40 kg 103.8 %",
        "Trunk 26.1 kg 98.0 %",
        "Right Leg 10.2 kg 101.0 % Left Leg 10.0 kg 99.5 %",
        "Visceral Fat Level 7 (Level 1-9)",
        "Basal Metabolic Rate 1690 kcal",
        "InBody Score 78/100"
    )

    private val indonesian = listOf(
        "Tinggi Badan 160 cm Usia 30 Jenis Kelamin Perempuan",
        "Air Tubuh Total 30,5 (28,0 ~ 34,2)",
        "Massa Lemak Tubuh 18,2 (9,5 ~ 15,2)",
        "Berat Badan 58,4 (44,0 ~ 59,5)",
        "Massa Otot Rangka 21,3 (19,5 ~ 23,8)",
        "IMT 22,8",
        "Persentase Lemak Tubuh 31,2 (18,0 ~ 28,0)",
        "Tingkat Lemak Viseral 6",
        "Laju Metabolisme Basal 1.280 kkal"
    )

    @Test fun readsEnglishSheet() {
        val r = InBodyParser.parseRows(english)
        assertEquals(72.0, r.weightKg!!, 0.0); assertEquals(33.4, r.smmKg!!, 0.0); assertEquals(14.6, r.bodyFatKg!!, 0.0)
        assertEquals(20.3, r.pbf!!, 0.0); assertEquals(24.9, r.bmi!!, 0.0); assertEquals(7.0, r.visceral!!, 0.0)
        assertEquals(1690.0, r.bmr!!, 0.0); assertEquals(42.1, r.tbwL!!, 0.0); assertEquals(78.0, r.score!!, 0.0)
        assertEquals(170.0, r.heightCm!!, 0.0); assertEquals(28, r.age); assertEquals(Sex.MALE, r.sex)
        assertEquals(29.5 to 36.1, r.ranges["smm"]); assertEquals(8.7 to 13.9, r.ranges["bfm"]); assertEquals(10.0 to 20.0, r.ranges["pbf"])
        assertEquals(3.45, r.segments["RA"]!!, 0.0); assertEquals(10.0, r.segments["LL"]!!, 0.0)
        assertEquals(105.2, r.segmentPct["RA"]!!, 0.0)
        assertTrue(InBodyParser.issues(r).isEmpty())
    }

    @Test fun readsIndonesianSheetWithCommaDecimalsAndThousands() {
        val r = InBodyParser.parseRows(indonesian)
        assertEquals(58.4, r.weightKg!!, 0.0); assertEquals(21.3, r.smmKg!!, 0.0); assertEquals(18.2, r.bodyFatKg!!, 0.0)
        assertEquals(31.2, r.pbf!!, 0.0); assertEquals(22.8, r.bmi!!, 0.0); assertEquals(6.0, r.visceral!!, 0.0)
        assertEquals(1280.0, r.bmr!!, 0.0); assertEquals(30.5, r.tbwL!!, 0.0)
        assertEquals(Sex.FEMALE, r.sex); assertEquals(160.0, r.heightCm!!, 0.0); assertEquals(30, r.age)
        assertTrue(InBodyParser.issues(r).isEmpty())
    }

    @Test fun missingValuesStayNullNotGuessed() {
        val r = InBodyParser.parseRows(listOf("Weight (kg) 72.0", "Skeletal Muscle Mass (kg)"))
        assertEquals(72.0, r.weightKg!!, 0.0)
        assertNull(r.smmKg); assertNull(r.bmr); assertNull(r.visceral)
        assertEquals(1, r.fieldCount())
    }

    @Test fun implausibleValuesAreDropped() {
        val r = InBodyParser.parseRows(listOf("Weight (kg) 7.2", "Percent Body Fat (%) 250", "Visceral Fat Level 99"))
        assertNull(r.weightKg); assertNull(r.pbf); assertNull(r.visceral)
    }

    @Test fun pbfDerivedFromFatMassWhenMissing() {
        val r = InBodyParser.parseRows(listOf("Weight (kg) 80.0", "Body Fat Mass (kg) 16.0"))
        assertEquals(20.0, r.pbf!!, 0.0)
    }

    @Test fun inconsistentReadingIsFlagged() {
        val r = InBodyReading(weightKg = 72.0, bodyFatKg = 14.6, pbf = 45.0)
        assertTrue(InBodyParser.issues(r).contains("PBF_MISMATCH"))
        assertTrue(InBodyParser.issues(InBodyReading(weightKg = 70.0, smmKg = 60.0)).contains("SMM_TOO_HIGH"))
        assertTrue(InBodyParser.issues(InBodyReading(weightKg = 70.0, heightCm = 170.0, bmi = 35.0)).contains("BMI_MISMATCH"))
        assertTrue(InBodyParser.issues(InBodyReading(smmKg = 40.0, ffmKg = 38.0)).contains("SMM_GT_FFM"))
        assertTrue(InBodyAnalysis.issueText(listOf("PBF_MISMATCH"), AppLang.ID).isNotEmpty())
        assertEquals("", InBodyAnalysis.issueText(emptyList(), AppLang.ID))
    }

    @Test fun groupsOcrLinesIntoRows() {
        val lines = listOf(
            InBodyParser.OcrLine("72.0", 400, 100, 460, 120),
            InBodyParser.OcrLine("Weight", 20, 98, 120, 122),
            InBodyParser.OcrLine("BMI", 20, 200, 70, 222),
            InBodyParser.OcrLine("24.9", 400, 201, 460, 221)
        )
        val rows = InBodyParser.rows(lines)
        assertEquals(listOf("Weight 72.0", "BMI 24.9"), rows)
        val r = InBodyParser.parseRows(rows)
        assertEquals(72.0, r.weightKg!!, 0.0); assertEquals(24.9, r.bmi!!, 0.0)
    }

    @Test fun manualEntry() {
        val r = InBodyParser.parseManual("inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600")!!
        assertEquals(70.0, r.weightKg!!, 0.0); assertEquals(32.0, r.smmKg!!, 0.0); assertEquals(14.0, r.bodyFatKg!!, 0.0)
        assertEquals(20.0, r.pbf!!, 0.0); assertEquals(7.0, r.visceral!!, 0.0); assertEquals(1600.0, r.bmr!!, 0.0)
        val pct = InBodyParser.parseManual("berat 70 lemak 22 persen")!!
        assertEquals(22.0, pct.pbf!!, 0.0)
        assertNull(InBodyParser.parseManual("berat 70"))
        assertNull(InBodyParser.parseManual("halo nine"))
        val c = InBodyParser.parseCorrection("berat 70,5")!!
        assertEquals(70.5, c.weightKg!!, 0.0)
        assertNull(InBodyParser.parseCorrection("latihan otot dada bench press 60 kg"))
        assertNull(InBodyParser.parseCorrection("ya"))
    }

    @Test fun mergeOverridesAndDropsStaleRanges() {
        val base = InBodyParser.parseRows(english)
        val m = InBodyParser.merge(base, InBodyReading(weightKg = 71.0))
        assertEquals(71.0, m.weightKg!!, 0.0); assertEquals(33.4, m.smmKg!!, 0.0)
        assertTrue(!m.ranges.containsKey("weight")); assertTrue(m.ranges.containsKey("smm"))
    }

    // ------------------------------------------------------------------ analisis

    @Test fun fatBandsBySex() {
        assertEquals(InBodyAnalysis.Band.STANDARD, InBodyAnalysis.fatBand(20.0, Sex.MALE))
        assertEquals(InBodyAnalysis.Band.HIGH, InBodyAnalysis.fatBand(22.0, Sex.MALE))
        assertEquals(InBodyAnalysis.Band.VERY_HIGH, InBodyAnalysis.fatBand(30.0, Sex.MALE))
        assertEquals(InBodyAnalysis.Band.STANDARD, InBodyAnalysis.fatBand(22.0, Sex.FEMALE))
        assertEquals(InBodyAnalysis.Band.LOW, InBodyAnalysis.fatBand(8.0, Sex.MALE))
        assertEquals(InBodyAnalysis.Band.LOW, InBodyAnalysis.fatBand(15.0, Sex.FEMALE))
    }

    @Test fun bmiAsiaPacificLabels() {
        assertEquals("berat badan kurang", InBodyAnalysis.bmiLabel(17.0, AppLang.ID))
        assertEquals("normal", InBodyAnalysis.bmiLabel(22.0, AppLang.ID))
        assertEquals("berat badan lebih", InBodyAnalysis.bmiLabel(24.0, AppLang.ID))
        assertEquals("obesitas I", InBodyAnalysis.bmiLabel(27.0, AppLang.ID))
        assertEquals("obese class II", InBodyAnalysis.bmiLabel(31.0, AppLang.EN))
    }

    @Test fun analysisIsHonestAndCautious() {
        val r = InBodyParser.parseRows(english)
        val t = InBodyAnalysis.analyze(r, Profile(), null, AppLang.ID)
        assertTrue(t.contains("33,4")); assertTrue(t.contains("20,3"))
        assertTrue("harus menyebut bukan diagnosis: $t", t.contains("bukan diagnosis"))
        assertTrue(t.contains("di atas rentang standar") || t.contains("dalam rentang standar"))
        val en = InBodyAnalysis.analyze(r, Profile(), null, AppLang.EN)
        assertTrue(en.contains("isn't a diagnosis"))
        // tanpa jenis kelamin tidak menebak kategori lemak tubuh
        val noSex = InBodyAnalysis.analyze(InBodyReading(weightKg = 70.0, pbf = 40.0, smmKg = 25.0), Profile(), null, AppLang.ID)
        assertTrue(!noSex.contains("jauh di atas rentang standar"))
        assertTrue(noSex.contains("jenis kelamin"))
    }

    @Test fun analysisDoesNotDiagnoseSarcopenia() {
        val low = InBodyReading(weightKg = 60.0, smmKg = 15.0, sex = Sex.FEMALE, pbf = 25.0)
        val t = InBodyAnalysis.analyze(low, Profile(), null, AppLang.ID).lowercase()
        assertTrue(!t.contains("sarkopenia") && !t.contains("sarcopenia"))
    }

    @Test fun highFatGetsModerateAdviceNotCrashDiet() {
        val r = InBodyReading(weightKg = 90.0, pbf = 32.0, smmKg = 30.0, sex = Sex.MALE)
        val t = InBodyAnalysis.analyze(r, Profile(), null, AppLang.ID)
        assertTrue(t.contains("defisit kalori moderat"))
        assertTrue(!t.contains("puasa") && !t.contains("1000 kkal"))
    }

    @Test fun lowFatSuggestsDoctorNotDiet() {
        val r = InBodyReading(weightKg = 55.0, pbf = 8.0, smmKg = 30.0, sex = Sex.MALE)
        val t = InBodyAnalysis.analyze(r, Profile(goal = Goal.CUT), null, AppLang.ID)
        assertTrue(t.contains("dokter"))
    }

    @Test fun compareReadings() {
        val a = InBodyReading(weightKg = 75.0, smmKg = 32.0, bodyFatKg = 17.0, pbf = 22.7)
        val b = InBodyReading(weightKg = 73.0, smmKg = 32.8, bodyFatKg = 14.5, pbf = 19.9)
        val t = InBodyAnalysis.compare(a, b, AppLang.ID)
        assertTrue(t.contains("rekomposisi"))
        val c = InBodyReading(weightKg = 70.0, smmKg = 30.0, bodyFatKg = 15.0)
        val loss = InBodyAnalysis.compare(a.copy(smmKg = 32.0, bodyFatKg = 17.0), c, AppLang.ID)
        assertTrue(loss.contains("otot ikut turun"))
        val tiny = InBodyAnalysis.compare(a, a.copy(smmKg = 32.1, bodyFatKg = 17.1), AppLang.ID)
        assertTrue(tiny.contains("variasi pengukuran"))
        assertTrue(InBodyAnalysis.compare(InBodyReading(), InBodyReading(), AppLang.ID).contains("Belum ada"))
    }

    @Test fun readbackListsValues() {
        val s = InBodyAnalysis.readback(InBodyParser.parseRows(english), AppLang.ID)
        assertTrue(s.contains("berat 72") && s.contains("otot rangka 33,4") && s.contains("laki-laki"))
        assertNotNull(InBodyAnalysis.readback(InBodyReading(), AppLang.EN))
    }
}

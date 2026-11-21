package id.nine.assistant.coach

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachSessionTest {

    private fun started(lang: AppLang = AppLang.ID): CoachSession = CoachSession().also { it.start(lang) }

    @Test
    fun mulaiMenyapaDanBertanya() {
        val s = CoachSession()
        assertFalse(s.active)
        val intro = s.start(AppLang.EN)
        assertTrue(s.active)
        assertTrue(intro.contains("Let's practise English"))
        assertTrue(intro.contains("stop English practice"))
        assertTrue(intro.trimEnd().endsWith("?"))
    }

    @Test
    fun penggunaIndonesiaMendapatTerjemahanDanInfoMikrofon() {
        val id = CoachSession().start(AppLang.ID)
        assertTrue(id.contains("Ayo latihan English"))
        assertTrue(id.contains("mikrofon mendengarkan bahasa Inggris"))
        val en = CoachSession().start(AppLang.EN)
        assertFalse(en.contains("Ayo latihan"))
    }

    @Test
    fun kalimatSalahDikoreksiLaluDiberiPertanyaan() {
        val s = started(AppLang.EN)
        val t = s.handle("i goes to school yesterday", AppLang.EN)
        assertFalse(t.ended)
        assertTrue(t.reply, t.reply.startsWith("Better: \"I went to school yesterday.\""))
        assertTrue(t.reply.contains("i goes → i went"))
        assertTrue(t.reply.trimEnd().endsWith("?"))
        assertEquals(1, s.sentenceCount)
        assertEquals(0, s.perfectCount)
    }

    @Test
    fun penjelasanMengikutiBahasaAplikasi() {
        val id = started().handle("she go to work", AppLang.ID).reply
        assertTrue(id, id.contains("Dengan he/she/it"))
        val en = started(AppLang.EN).handle("she go to work", AppLang.EN).reply
        assertTrue(en, en.contains("With he/she/it"))
    }

    @Test
    fun kalimatBenarDipuji() {
        val s = started(AppLang.EN)
        val t = s.handle("I went to the market yesterday and bought some fruit", AppLang.EN)
        assertFalse(t.reply, t.reply.contains("Better:"))
        assertTrue(t.reply, t.reply.contains("No mistakes") || t.reply.contains("no mistakes") || t.reply.contains("correct"))
        assertEquals(Mood.HAPPY, t.mood)
        assertEquals(1, s.perfectCount)
    }

    @Test
    fun saranKataMuncul() {
        val s = started(AppLang.EN)
        val t = s.handle("The food is very good", AppLang.EN)
        assertTrue(t.reply, t.reply.contains("Word tip: \"very good\""))
        assertTrue(t.reply.contains("excellent"))
    }

    @Test
    fun pertanyaanLanjutanNyambung() {
        val s = started(AppLang.EN)
        val t = s.handle("I like to eat fried rice for dinner", AppLang.EN)
        val food = CoachSession.TOPIC_PROMPTS.first { it.second[0].en.contains("favourite dish") }.second.map { it.en }
        assertTrue(t.reply, food.any { t.reply.contains(it) })
    }

    @Test
    fun jawabanTerlaluPendekTidakDihitung() {
        val s = started(AppLang.EN)
        val t = s.handle("yes", AppLang.EN)
        assertTrue(t.reply.contains("full sentence"))
        assertEquals(0, s.sentenceCount)
    }

    @Test
    fun bahasaIndonesiaDiminta() {
        val s = started(AppLang.ID)
        val t = s.handle("saya mau makan nasi goreng", AppLang.ID)
        assertTrue(t.reply, t.reply.contains("Indonesian"))
        assertTrue(t.reply.contains("bahasa Indonesia"))
        assertEquals(0, s.sentenceCount)
    }

    @Test
    fun pertanyaanBerikutnyaBerbeda() {
        val s = started(AppLang.EN)
        val seen = HashSet<String>()
        var last = ""
        repeat(10) {
            val q = s.handle("next question", AppLang.EN).reply
            assertNotEquals("tidak boleh mengulang berturut-turut", last, q)
            last = q
            seen.add(q)
        }
        assertEquals(10, seen.size)
    }

    @Test
    fun skorDanRingkasan() {
        val s = started(AppLang.EN)
        s.handle("i goes to school yesterday", AppLang.EN)
        s.handle("she go to work", AppLang.EN)
        s.handle("I like music", AppLang.EN)
        val p = s.handle("my progress", AppLang.EN)
        assertTrue(p.reply, p.reply.contains("3 sentences"))
        assertTrue(p.reply.contains("1 without mistakes"))
        assertTrue(p.reply.contains("33%"))
        val end = s.handle("stop English practice", AppLang.EN)
        assertTrue(end.ended)
        assertFalse(s.active)
        assertTrue(end.reply, end.reply.contains("3 sentences"))
        assertTrue(end.reply, end.reply.contains("Most common:"))
    }

    @Test
    fun berhentiTanpaBicaraBerpesanRamah() {
        val s = started(AppLang.EN)
        val end = s.handle("stop", AppLang.EN)
        assertTrue(end.ended)
        assertTrue(end.reply, end.reply.contains("Come back any time"))
    }

    @Test
    fun kataLainDiDalamSesi() {
        val s = started(AppLang.EN)
        val t = s.handle("another word for good", AppLang.EN)
        assertTrue(t.reply, t.reply.contains("excellent"))
        assertTrue("pertanyaan yang tadi diulang agar percakapan berlanjut", t.reply.trimEnd().endsWith("?"))
        assertEquals(0, s.sentenceCount)
    }

    @Test
    fun koreksiLangsungDiDalamSesi() {
        val s = started(AppLang.EN)
        val t = s.handle("correct my sentence: he don't like it", AppLang.EN)
        assertTrue(t.reply, t.reply.contains("He doesn't like it."))
        assertEquals(1, s.sentenceCount)
    }

    @Test
    fun mulaiLagiSaatAktifTidakMengulang() {
        val s = started(AppLang.EN)
        s.handle("I like music", AppLang.EN)
        val t = s.handle("be my English mentor", AppLang.EN)
        assertTrue(t.reply, t.reply.contains("already practising"))
        assertEquals(1, s.sentenceCount)
        assertTrue(s.active)
    }

    @Test
    fun sesiBaruMengulangSkor() {
        val s = started(AppLang.EN)
        s.handle("i goes to school yesterday", AppLang.EN)
        s.start(AppLang.EN)
        assertEquals(0, s.sentenceCount)
    }

    @Test
    fun semuaPertanyaanPunyaTerjemahanDanTandaTanya() {
        val all = CoachSession.GENERIC + CoachSession.TOPIC_PROMPTS.flatMap { it.second }
        for (p in all) {
            assertTrue(p.en, p.en.trimEnd().let { it.endsWith("?") || it.endsWith(".") })
            assertTrue(p.id, p.id.isNotBlank())
        }
        assertTrue(CoachSession.GENERIC.size >= 12)
    }

    @Test
    fun pertanyaanBerputarSampaiHabisLaluMulaiLagi() {
        val s = started(AppLang.EN)
        repeat(CoachSession.GENERIC.size * 2 + 3) {
            val r = s.handle("next question", AppLang.EN).reply
            assertTrue(r.isNotBlank())
        }
        assertTrue(s.active)
    }

    @Test
    fun kalimatTanpaIssueTidakMenghasilkanPerbaikan() {
        val s = started(AppLang.EN)
        val t = s.handle("My brother lives in Bandung and he works at a bank", AppLang.EN)
        assertFalse(t.reply.contains("Better:"))
    }
}

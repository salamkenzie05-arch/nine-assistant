package id.nine.assistant.coach

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachFeedbackTest {

    @Test
    fun deteksiBahasaIndonesia() {
        assertTrue(CoachFeedback.looksIndonesian("saya mau makan nasi goreng"))
        assertTrue(CoachFeedback.looksIndonesian("terima kasih"))
        assertTrue(CoachFeedback.looksIndonesian("aku pergi ke pasar kemarin"))
        assertFalse(CoachFeedback.looksIndonesian("I like to eat nasi goreng"))
        assertFalse(CoachFeedback.looksIndonesian("Dan went home"))
        assertFalse(CoachFeedback.looksIndonesian("I went to the market yesterday"))
        assertFalse(CoachFeedback.looksIndonesian("yes"))
        assertFalse(CoachFeedback.looksIndonesian(""))
    }

    @Test
    fun reviewMenyusunKoreksiDenganTipSesuaiBahasa() {
        val id = CoachFeedback.review("he don't like it", AppLang.ID)
        assertFalse(id.perfect)
        assertTrue(id.text.startsWith("Better: \"He doesn't like it.\""))
        assertTrue(id.text.contains("Dengan he/she/it pakai"))
        val en = CoachFeedback.review("he don't like it", AppLang.EN)
        assertTrue(en.text.contains("With he/she/it use"))
    }

    @Test
    fun maksimalTigaPerbaikanDanTanpaSaranKataBila3Kesalahan() {
        val r = CoachFeedback.review("a apple and an book and she go to an university very good", AppLang.EN)
        assertTrue(r.text, r.text.contains("more)"))
        assertFalse(r.text, r.text.contains("Word tip"))
        assertEquals(r.text, 3, r.text.lines().count { it.startsWith("•") })
    }

    @Test
    fun pujianBerputar() {
        val a = CoachFeedback.review("I like music", AppLang.EN, 0).text
        val b = CoachFeedback.review("I like music", AppLang.EN, 1).text
        assertTrue(a != b)
        assertTrue(CoachFeedback.review("I like music", AppLang.EN, -3).perfect)
    }

    @Test
    fun balasanKoreksiTunggalMenawarkanLatihan() {
        val r = CoachFeedback.correctionReply("i goes to school", AppLang.ID)
        assertTrue(r.contains("I go to school."))
        assertTrue(r.contains("jadi mentor bahasa Inggris saya"))
        val ok = CoachFeedback.correctionReply("I like music", AppLang.EN)
        assertTrue(ok.contains("Your sentence: \"I like music\""))
        assertTrue(ok.contains("be my English mentor"))
    }

    @Test
    fun kataLainTidakDikenaliMemberiContoh() {
        val r = CoachFeedback.betterWordReply("xylophone", AppLang.EN)
        assertTrue(r.contains("xylophone"))
        assertTrue(r.contains("good"))
        val ok = CoachFeedback.betterWordReply("nice", AppLang.ID)
        assertTrue(ok.contains("lovely"))
        assertTrue(ok.contains("Sebagai pengganti"))
    }
}

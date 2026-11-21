package id.nine.assistant.speech

import id.nine.assistant.core.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun shortTextIsUntouched() {
        assertEquals("Halo, Tuan.", SpeechText.cap("  Halo, Tuan.  ", 600))
    }

    @Test
    fun cutsAtTheLastSentenceEnd() {
        val text = "Kalimat satu panjang sekali. Kalimat dua juga panjang. Kalimat tiga terpotong di tengah jalan"
        val out = SpeechText.cap(text, 60)
        assertEquals("Kalimat satu panjang sekali. Kalimat dua juga panjang.", out)
    }

    @Test
    fun fallsBackToWordBoundaryWithoutSentenceEnd() {
        val text = "satu dua tiga empat lima enam tujuh delapan sembilan sepuluh sebelas"
        val out = SpeechText.cap(text, 30)
        assertTrue(out.length <= 30)
        assertTrue(text.startsWith(out))
        assertTrue("jangan memotong kata: '$out'", text[out.length] == ' ')
    }

    @Test
    fun decimalPointIsNotASentenceEnd() {
        val text = "Nilainya 3.14159 kira-kira segitu untuk pi dan seterusnya sampai panjang sekali ya"
        val out = SpeechText.cap(text, 20)
        assertTrue(out.length <= 20)
        assertTrue("tidak boleh berakhir di '3.': '$out'", out != "Nilainya 3.")
    }

    @Test
    fun singleHugeWordIsHardCut() {
        assertEquals("aaaaa", SpeechText.cap("a".repeat(50), 5))
    }

    @Test
    fun nonPositiveLimitGivesEmpty() {
        assertEquals("", SpeechText.cap("Halo", 0))
    }

    @Test
    fun onlyTwoGentleStylesExist() {
        assertNull(SpeechText.style(null))
        assertNull(SpeechText.style(Mood.NEUTRAL))
        // hanya dua gaya halus supaya timbre suara tidak berubah-ubah
        val styles = Mood.values().mapNotNull { SpeechText.style(it) }.toSet()
        assertEquals(setOf("warm and friendly", "gentle and calm"), styles)
        assertNull(SpeechText.style(Mood.CURIOUS))
        // gaya harus pendek dan satu baris (dikirim ke API)
        for (m in Mood.values()) SpeechText.style(m)?.let { assertTrue(it.length < 60 && !it.contains('\n')) }
    }
}

package id.nine.assistant.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidVoiceTest {

    @Test
    fun rendersAudibleBoundedSound() {
        val pcm = DroidVoice.render("Selamat malam, Tuan. Semua sistem berjalan normal.")
        val seconds = pcm.size.toDouble() / DroidVoice.SAMPLE_RATE
        assertTrue("terlalu pendek: $seconds", seconds > 0.3)
        assertTrue("terlalu panjang: $seconds", seconds < 3.0)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("senyap", peak > 2000)
        assertTrue("terpotong (clipping)", peak < Short.MAX_VALUE.toInt())
    }

    @Test
    fun isDeterministicSoSameTextSoundsTheSame() {
        assertArrayEquals(DroidVoice.render("Halo Tuan."), DroidVoice.render("Halo Tuan."))
    }

    @Test
    fun punctuationChangesTheEnding() {
        val statement = DroidVoice.render("Apakah semua sudah siap.")
        val question = DroidVoice.render("Apakah semua sudah siap?")
        assertFalse(statement.contentEquals(question))
    }

    @Test
    fun emptyTextStillBlips() {
        assertTrue(DroidVoice.render("").isNotEmpty())
        assertTrue(DroidVoice.render("...").isNotEmpty())
    }
}

package id.nine.assistant.nlu

import id.nine.assistant.support.SupportIntent.Exercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SupportParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }
    private fun p(s: String) = parser.parse(s)

    @Test
    fun mulaiCurhat() {
        assertEquals(Command.Support(true), p("aku mau curhat"))
        assertEquals(Command.Support(true), p("Nine, aku mau curhat dong"))
        assertEquals(Command.Support(true), p("kamu jadi psikologku ya"))
        assertEquals(Command.Support(true), p("temani aku"))
        assertEquals(Command.Support(true), p("I need to vent"))
        assertEquals(Command.Support(true), p("apakah boleh curhat?"))
    }

    @Test
    fun latihanLangsung() {
        assertEquals(Command.Support(true, Exercise.BREATHING), p("latihan napas"))
        assertEquals(Command.Support(true, Exercise.BREATHING), p("tenangkan aku"))
        assertEquals(Command.Support(true, Exercise.GROUNDING), p("teknik grounding"))
        assertEquals(Command.Support(true, Exercise.BREATHING), p("breathing exercise"))
    }

    @Test
    fun akhiriCurhat() {
        assertEquals(Command.Support(false), p("stop curhat"))
        assertEquals(Command.Support(false), p("keluar dari mode curhat"))
        assertEquals(Command.Support(false), p("end counseling"))
    }

    @Test
    fun perintahLainTidakTergeser() {
        assertFalse(p("ingatkan aku curhat ke ibu jam 5") is Command.Support)
        assertFalse(p("cari psikolog terdekat") is Command.Support)
        assertFalse(p("putar lagu sedih") is Command.Support)
        assertFalse(p("ceritakan dongeng") is Command.Support)
        assertFalse(p("aku mau curhat ke ibuku") is Command.Support)
        assertFalse(p("catat pengeluaran 50 ribu") is Command.Support)
        assertTrue(p("buat alarm jam 7") is Command.SetAlarm)
        assertEquals(Command.EnglishCoach(true), p("jadi mentor bahasa inggris saya"))
        assertEquals(Command.EnglishCoach(false), p("stop english practice"))
    }

    @Test
    fun curhatBiasaBukanPerintah() {
        // Kalimat perasaan biasa tidak membuka mode; ditangani seperti ucapan umum.
        assertTrue(p("aku sedih banget hari ini") is Command.Unknown)
        assertTrue(p("aku capek") is Command.Unknown)
        assertTrue(p("i feel lonely") is Command.Unknown)
    }
}

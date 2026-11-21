package id.nine.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Parser mengenali permintaan mentor bahasa Inggris, tanpa membajak perintah lain. */
class CoachParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }

    @Test
    fun memulaiDanMengakhiriLatihan() {
        for (s in listOf(
            "jadi mentor bahasa inggris saya", "Jadilah mentor bahasa Inggris saya", "ayo latihan bahasa inggris", "tolong ajari aku bahasa inggris",
            "be my English mentor", "can you be my english tutor", "let's practice English", "teach me English", "english practice",
            "nine, jadi mentor english", "saya mau belajar bahasa inggris"
        )) {
            assertEquals("start: $s", Command.EnglishCoach(true), parser.parse(s))
        }
        for (s in listOf("stop english practice", "selesai latihan bahasa inggris", "keluar dari mode mentor", "end the lesson", "stop English practice please")) {
            assertEquals("stop: $s", Command.EnglishCoach(false), parser.parse(s))
        }
    }

    @Test
    fun koreksiKalimat() {
        assertEquals(Command.CorrectEnglish("i goes to school yesterday"), parser.parse("correct my sentence: i goes to school yesterday"))
        assertEquals(Command.CorrectEnglish("i goes to school yesterday"), parser.parse("Can you correct my sentence: i goes to school yesterday?"))
        assertEquals(Command.CorrectEnglish("I am agree"), parser.parse("is this sentence correct: I am agree"))
        assertEquals(Command.CorrectEnglish("she don't like it"), parser.parse("check my grammar: she don't like it"))
        assertEquals(Command.CorrectEnglish("aku pergi ke pasar kemarin"), parser.parse("koreksi kalimat saya: aku pergi ke pasar kemarin"))
        // kalimat yang diawali "i want to ..." tidak boleh terpotong oleh pembersih awalan
        assertEquals(Command.CorrectEnglish("i want to went home"), parser.parse("correct my sentence: i want to went home"))
    }

    @Test
    fun kataYangLebihBagus() {
        assertEquals(Command.BetterWord("nice"), parser.parse("what is a better word for nice"))
        assertEquals(Command.BetterWord("big"), parser.parse("what's another word for big"))
        assertEquals(Command.BetterWord("very good"), parser.parse("another word for very good"))
        assertEquals(Command.BetterWord("good"), parser.parse("kata lain dari good"))
    }

    @Test
    fun perintahLainTidakTerganggu() {
        assertEquals(Command.SwitchLanguage(id.nine.assistant.core.AppLang.EN), parser.parse("ganti bahasa ke inggris"))
        assertEquals(Command.SwitchLanguage(id.nine.assistant.core.AppLang.EN), parser.parse("speak english"))
        assertEquals(Command.SwitchLanguage(id.nine.assistant.core.AppLang.ID), parser.parse("switch language to indonesian"))
        for (s in listOf(
            "putar video belajar bahasa inggris", "tambah tugas belajar bahasa inggris", "ingatkan saya belajar english jam 7",
            "terjemahkan good morning ke bahasa inggris", "alarm belajar english jam 6", "what time is it", "status affinity", "kata lain dari bagus"
        )) {
            val c = parser.parse(s)
            assertFalse("$s -> $c", c is Command.EnglishCoach || c is Command.CorrectEnglish || c is Command.BetterWord)
        }
        assertTrue(parser.parse("status affinity") == Command.AffinityStatus)
    }

    @Test
    fun kalimatLatihanBiasaBukanPerintahMentor() {
        for (s in listOf("I went to school yesterday", "She goes to work every day", "My brother likes football")) {
            val c = parser.parse(s)
            assertFalse("$s -> $c", c is Command.EnglishCoach || c is Command.CorrectEnglish || c is Command.BetterWord)
        }
    }
}

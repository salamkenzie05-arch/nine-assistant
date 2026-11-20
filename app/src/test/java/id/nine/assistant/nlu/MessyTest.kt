package id.nine.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Tes pemahaman kalimat berantakan: hasilnya harus sama dengan kalimat rapi yang setara. */
class MessyTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }

    private fun p(s: String): Command = parser.parse(s)

    private fun same(messy: String, tidy: String) {
        val want = p(tidy)
        assertTrue("kalimat rapi \"$tidy\" saja tidak dikenali ($want)", want !is Command.Unknown)
        assertEquals("\"$messy\"", want, p(messy))
    }

    @Test
    fun objectBeforeVerb() {
        same("youtube buka dong", "buka youtube")
        same("alarm jam 6 pagi pasang", "pasang alarm jam 6 pagi")
        same("senter nyalain dong nine", "nyalakan senter")
    }

    @Test
    fun stateWordAtTheEnd() {
        same("wifi nyala", "nyalakan wifi")
        same("bluetooth mati", "matikan bluetooth")
    }

    @Test
    fun smallTypos() {
        same("matikan bluetoth", "matikan bluetooth")
        same("buka yutub", "buka youtube")
    }

    @Test
    fun storiesAndQuestionsAreNotGuessed() {
        assertTrue(p("tadi aku buka youtube") is Command.Unknown)
        assertTrue(p("kenapa langit biru") is Command.Unknown)
        assertTrue(p("hmm anu gitu deh") is Command.Unknown)
    }

    @Test
    fun candidateListIsBounded() {
        assertTrue(Messy.candidates("a b c d e f g h i j k l").isEmpty())
        assertTrue(Messy.candidates("buka").isEmpty())
        assertTrue(Messy.candidates("youtube buka dong").size <= 5)
    }
}

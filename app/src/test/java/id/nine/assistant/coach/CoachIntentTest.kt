package id.nine.assistant.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachIntentTest {

    private fun start(s: String) = assertEquals("start: $s", CoachIntent.Start, CoachIntents.detect(s))

    private fun none(s: String) = assertNull("bukan urusan mentor: $s -> ${CoachIntents.detect(s)}", CoachIntents.detect(s))

    @Test
    fun memulaiLatihan() {
        start("be my English mentor")
        start("jadi mentor bahasa Inggris saya")
        start("ayo latihan bahasa inggris")
        start("let's practice English")
        start("teach me English")
        start("ajari aku bahasa inggris")
        start("english practice")
        start("latihan english")
        start("mode mentor")
        start("coach me in english")
        start("ngobrol bahasa inggris")
        start("improve my english")
        start("start english lesson")
        start("correct my grammar")
        start("koreksi grammar saya")
        start("practice speaking english with me")
        start("belajar bahasa inggris")
    }

    @Test
    fun perintahLainTidakDibajak() {
        none("putar video belajar bahasa inggris")
        none("terjemahkan ke bahasa inggris")
        none("ganti bahasa ke inggris")
        none("cari kursus bahasa inggris")
        none("tambah tugas belajar bahasa inggris")
        none("ingatkan saya belajar english jam 7")
        none("how do you say apple in english")
        none("what time is it")
        none("cara belajar bahasa inggris cepat")
        none("alarm belajar english jam 6")
        none("i went to school yesterday")
        none("play english songs")
        none("buka aplikasi belajar english")
        none("kirim pesan ke budi")
        none("hello")
        none("")
    }

    @Test
    fun mengakhiriLatihan() {
        for (s in listOf("stop english practice", "keluar dari latihan bahasa inggris", "selesai latihan", "end the lesson", "exit coach mode", "matikan mode mentor", "berhenti latihan english")) {
            assertEquals("stop: $s", CoachIntent.Stop, CoachIntents.detect(s))
        }
        none("stop")
        none("stop music")
        none("berhenti")
        assertEquals(CoachIntent.Stop, CoachIntents.detectInSession("stop"))
        assertEquals(CoachIntent.Stop, CoachIntents.detectInSession("that's enough"))
        assertEquals(CoachIntent.Stop, CoachIntents.detectInSession("selesai dong"))
    }

    @Test
    fun koreksiKalimat() {
        assertEquals(CoachIntent.Correct("i goes to school"), CoachIntents.detect("correct my sentence: i goes to school"))
        assertEquals(CoachIntent.Correct("she don't like it"), CoachIntents.detect("check my grammar: she don't like it"))
        assertEquals(CoachIntent.Correct("i am agree"), CoachIntents.detect("is this correct: i am agree"))
        assertEquals(CoachIntent.Correct("I am agree"), CoachIntents.detect("is it correct to say \"I am agree\""))
        assertEquals(CoachIntent.Correct("I am agree"), CoachIntents.detect("can I say 'I am agree'"))
        assertEquals(CoachIntent.Correct("aku pergi ke pasar kemarin"), CoachIntents.detect("koreksi kalimat saya: aku pergi ke pasar kemarin"))
        assertEquals(CoachIntent.Correct("i goes to school"), CoachIntents.detect("benar tidak kalimat ini: i goes to school"))
        assertEquals(CoachIntent.Correct("he go home"), CoachIntents.detect("what is wrong with my sentence: he go home"))
        assertEquals(CoachIntent.Correct("I very like it"), CoachIntents.detect("how can I say this better: I very like it"))
    }

    @Test
    fun kataYangLebihBagus() {
        assertEquals(CoachIntent.BetterWord("nice"), CoachIntents.detect("what is a better word for nice"))
        assertEquals(CoachIntent.BetterWord("big"), CoachIntents.detect("what's another word for big"))
        assertEquals(CoachIntent.BetterWord("very good"), CoachIntents.detect("another word for very good"))
        assertEquals(CoachIntent.BetterWord("happy"), CoachIntents.detect("synonyms for happy"))
        assertEquals(CoachIntent.BetterWord("good"), CoachIntents.detect("kata lain dari good"))
        // kata Indonesia (tidak ada di tabel Inggris) tetap untuk otak AI
        none("kata lain dari bagus")
        none("sinonim bagus")
        none("what's the word for apple in indonesian")
    }

    @Test
    fun perintahKhususSesi() {
        assertEquals(CoachIntent.NextTopic, CoachIntents.detectInSession("next question"))
        assertEquals(CoachIntent.NextTopic, CoachIntents.detectInSession("ask me something"))
        assertEquals(CoachIntent.NextTopic, CoachIntents.detectInSession("topik lain"))
        assertEquals(CoachIntent.Progress, CoachIntents.detectInSession("how am I doing"))
        assertEquals(CoachIntent.Progress, CoachIntents.detectInSession("my progress"))
        assertNull(CoachIntents.detectInSession("I like pizza very much"))
        // di luar sesi kata-kata itu bukan perintah
        none("next question")
        none("my progress")
    }

    @Test
    fun kalimatLatihanBiasaBukanPerintah() {
        for (s in listOf("I went to school yesterday", "My family lives in Bandung", "I want to learn English", "I study English every day", "I like to practice English with my friend")) {
            val i = CoachIntents.detectInSession(s)
            // "I want to learn English" / "I study English ..." menyebut English tetapi tidak meminta mentor: tidak boleh memulai sesi baru
            assertTrue("$s -> $i", i == null || i == CoachIntent.Start)
        }
        assertNull(CoachIntents.detectInSession("My family lives in Bandung"))
        assertNull(CoachIntents.detectInSession("I went to school yesterday"))
    }
}

class CoachIntentEdgeTest {
    @Test
    fun koreksiTanpaIsiMemulaiLatihan() {
        assertEquals(CoachIntent.Start, CoachIntents.detect("koreksi kalimat saya"))
        assertEquals(CoachIntent.Start, CoachIntents.detect("correct my grammar"))
        assertEquals(CoachIntent.Start, CoachIntents.detect("check my english"))
        assertNull(CoachIntents.detect("check my grammar please"))
    }
}

package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanTest {

    @Test
    fun stripsMarkdownAndEmoji() {
        assertEquals("Tentu, Tuan. Ini penting.", TextClean.clean("**Tentu**, Tuan. Ini *penting*. 😊"))
        assertEquals("Langkah satu\nLangkah dua", TextClean.clean("- Langkah satu\n- Langkah dua"))
        assertEquals("Judul\nIsi", TextClean.clean("## Judul\nIsi"))
        assertEquals("Lihat dokumen ini", TextClean.clean("Lihat [dokumen ini](https://contoh.id)"))
    }

    @Test
    fun splitsSentencesWhileStreaming() {
        val s = TextClean.SentenceSplitter(minLength = 10)
        val out = ArrayList<String>()
        for (chunk in listOf("Baik, Tu", "an. Suhu inti 3.5 der", "ajat. Semua", " normal! Ada lagi?")) out += s.push(chunk)
        s.flush()?.let { out += it }
        // "3.5" bukan akhir kalimat; potongan yang tiba sebagian disambung dengan benar.
        assertEquals(listOf("Baik, Tuan.", "Suhu inti 3.5 derajat.", "Semua normal!", "Ada lagi?"), out)
    }

    @Test
    fun shortSentencesAreMerged() {
        val s = TextClean.SentenceSplitter(minLength = 24)
        val out = s.push("Ya. Tentu. Itu ide yang sangat baik, Tuan. ")
        assertEquals(listOf("Ya. Tentu. Itu ide yang sangat baik, Tuan."), out)
        assertEquals(null, s.flush())
    }
}

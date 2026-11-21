package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanTest {

    @Test
    fun stripsMarkdownAndEmoji() {
        assertEquals("Tentu, Tuan. Ini penting.", TextClean.clean("**Tentu**, Tuan. Ini *penting*. 😊"))
        assertEquals("• Langkah satu\n• Langkah dua", TextClean.clean("- Langkah satu\n- Langkah dua"))
        assertEquals("Judul\nIsi", TextClean.clean("## Judul\nIsi"))
        assertEquals("Lihat dokumen ini", TextClean.clean("Lihat [dokumen ini](https://contoh.id)"))
    }

    @Test
    fun keepsListsReadable() {
        assertEquals("• satu\n• dua\n• tiga", TextClean.clean("* satu\n  - dua\n• tiga"))
        assertEquals("1. Buka\n2. Tekan\n3. Selesai", TextClean.clean("1) Buka\n 2. Tekan\n3.  Selesai"))
        assertEquals("Tebal dan miring", TextClean.clean("**Tebal** dan *miring*"))
        assertEquals("• Tebal item", TextClean.clean("* **Tebal** item"))
        assertEquals("Suhu -5 derajat dan 3.5 persen", TextClean.clean("Suhu -5 derajat dan 3.5 persen"))
        assertEquals("Tahun 2026. Bagus", TextClean.clean("Tahun 2026. Bagus"))
    }

    @Test
    fun speechDropsListMarkers() {
        assertEquals("Langkah satu\nLangkah dua", TextClean.speech("- Langkah satu\n- Langkah dua"))
        assertEquals("Buka\nTekan", TextClean.speech("1. Buka\n2. Tekan"))
        assertEquals("Biasa saja.", TextClean.speech("**Biasa** saja."))
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

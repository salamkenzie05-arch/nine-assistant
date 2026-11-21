package id.nine.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractorTest {

    @Test
    fun picksLastingFactsIndonesian() {
        assertEquals(listOf("Nama saya Budi"), FactExtractor.extract("Nama saya Budi"))
        assertEquals(listOf("aku suka nasi goreng"), FactExtractor.extract("oh ya, aku suka nasi goreng"))
        assertEquals(listOf("Aku alergi kacang"), FactExtractor.extract("Aku alergi kacang"))
        assertEquals(listOf("Ibuku bernama Sari"), FactExtractor.extract("Ibuku bernama Sari"))
        assertEquals(listOf("kucingku namanya Mochi"), FactExtractor.extract("kucingku namanya Mochi"))
        assertEquals(listOf("aku tinggal di Jakarta.", "aku kerja di bank"), FactExtractor.extract("aku tinggal di Jakarta. aku kerja di bank"))
    }

    @Test
    fun picksLastingFactsEnglish() {
        assertEquals(listOf("my name is Dewi"), FactExtractor.extract("my name is Dewi"))
        assertEquals(listOf("I live in Bandung"), FactExtractor.extract("I live in Bandung"))
        assertEquals(listOf("I have two cats"), FactExtractor.extract("I have two cats"))
        assertEquals(listOf("My birthday is May 3rd"), FactExtractor.extract("My birthday is May 3rd"))
    }

    @Test
    fun ignoresQuestionsTransientStuffAndCommands() {
        assertTrue(FactExtractor.extract("apa kabar?").isEmpty())
        assertTrue(FactExtractor.extract("aku lagi lapar").isEmpty())
        assertTrue(FactExtractor.extract("besok aku suka nonton").isEmpty())
        assertTrue(FactExtractor.extract("I'm a bit tired").isEmpty())
        assertTrue(FactExtractor.extract("I have a meeting").isEmpty())
        assertTrue(FactExtractor.extract("tolong putar lagu").isEmpty())
        assertTrue(FactExtractor.extract("I'm going to the store").isEmpty())
    }

    @Test
    fun secretsAreNeverKept() {
        assertTrue(FactExtractor.looksSensitive("Kata sandi wifi saya rahasia123"))
        assertTrue(FactExtractor.looksSensitive("Nomor kartu saya 4111 1111 1111 1111"))
        assertTrue(FactExtractor.looksSensitive("Nomor HP saya 081234567890"))
        assertTrue(FactExtractor.looksSensitive("My PIN is 1234"))
        assertTrue(!FactExtractor.looksSensitive("Ulang tahun saya 17-08-1990"))
        assertTrue(!FactExtractor.looksSensitive("Aku tinggal di Bandung"))
        assertTrue(!FactExtractor.looksSensitive("Aku suka pinjam buku"))
        assertTrue(FactExtractor.extract("my password is hunter2, i live in Bandung").isEmpty())
    }
}

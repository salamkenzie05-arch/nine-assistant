package id.nine.assistant.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordChoiceTest {

    @Test
    fun penguatDiganti() {
        val u = WordChoice.suggest("It is very good")
        assertEquals("very good", u.first().from)
        assertTrue(u.first().options.contains("excellent"))
        assertEquals("exhausted", WordChoice.suggest("I am very tired").first().options.first())
        assertEquals("very big", WordChoice.suggest("They live in a very big house").first().from)
        assertEquals("really good", WordChoice.suggest("That was really good").first().from)
    }

    @Test
    fun kataBiasaSetelahBe() {
        val u = WordChoice.suggest("The food is good")
        assertEquals("good", u.first().from)
        assertTrue(u.first().options.contains("great"))
    }

    @Test
    fun frasaUmum() {
        assertEquals("a lot of", WordChoice.suggest("There are a lot of people here").first().from)
        assertEquals("i want", WordChoice.suggest("I want a coffee").first().from)
        assertEquals("i think", WordChoice.suggest("I think we should go now").first().from)
        assertEquals("maybe", WordChoice.suggest("Maybe we can meet later").first().from)
    }

    @Test
    fun tidakMemberiSaranYangTidakPerlu() {
        assertTrue(WordChoice.suggest("Good morning").isEmpty())
        assertTrue(WordChoice.suggest("I think so").isEmpty())
        assertTrue(WordChoice.suggest("I do not think so").isEmpty())
        assertTrue(WordChoice.suggest("She goes to school").isEmpty())
        assertTrue(WordChoice.suggest("good").isEmpty())
        assertTrue(WordChoice.suggest("").isEmpty())
    }

    @Test
    fun jumlahSaranDibatasi() {
        val u = WordChoice.suggest("It is very good and very big, I want a lot of it", max = 2)
        assertEquals(2, u.size)
        assertTrue(WordChoice.suggest("It is very good and very big", max = 0).isEmpty())
    }

    @Test
    fun penguatDahuluSebelumFrasa() {
        val u = WordChoice.suggest("I want a very good phone", max = 2)
        assertEquals("very good", u[0].from)
        assertEquals("i want", u[1].from)
    }

    @Test
    fun kataLain() {
        val a = WordChoice.alternatives("good")
        assertNotNull(a)
        assertTrue(a!!.options.contains("excellent"))
        assertTrue(a.example.isNotBlank())
        assertEquals("very good", WordChoice.alternatives("really good")!!.word.removePrefix("really ").let { "very $it" })
        assertNotNull(WordChoice.alternatives("Very Good"))
        assertNotNull(WordChoice.alternatives("\"happy\""))
        assertNotNull(WordChoice.alternatives("the word nice"))
        assertNull(WordChoice.alternatives("bagus"))
        assertNull(WordChoice.alternatives("xylophone"))
    }
}

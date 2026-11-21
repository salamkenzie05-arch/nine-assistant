package id.nine.assistant.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {

    @Test
    fun bentukOrangKetiga() {
        assertEquals("goes", Lexicon.third("go"))
        assertEquals("studies", Lexicon.third("study"))
        assertEquals("plays", Lexicon.third("play"))
        assertEquals("watches", Lexicon.third("watch"))
        assertEquals("has", Lexicon.third("have"))
        assertEquals("does", Lexicon.third("do"))
        assertEquals("likes", Lexicon.third("like"))
    }

    @Test
    fun bentukLampauDanIng() {
        assertEquals("went", Lexicon.past("go"))
        assertEquals("liked", Lexicon.past("like"))
        assertEquals("studied", Lexicon.past("study"))
        assertEquals("stopped", Lexicon.past("stop"))
        assertEquals("played", Lexicon.past("play"))
        assertEquals("planned", Lexicon.past("plan"))
        assertEquals("making", Lexicon.ing("make"))
        assertEquals("running", Lexicon.ing("run"))
        assertEquals("studying", Lexicon.ing("study"))
        assertEquals("going", Lexicon.ing("go"))
        assertEquals("seeing", Lexicon.ing("see"))
    }

    @Test
    fun kembaliKeBentukDasar() {
        assertEquals("go", Lexicon.baseFromThird("goes"))
        assertEquals("study", Lexicon.baseFromThird("studies"))
        assertEquals("watch", Lexicon.baseFromThird("watches"))
        assertEquals("have", Lexicon.baseFromThird("has"))
        assertEquals("do", Lexicon.baseFromThird("does"))
        assertEquals("use", Lexicon.baseFromThird("uses"))
        assertEquals("play", Lexicon.baseFromThird("plays"))
        assertNull(Lexicon.baseFromThird("this"))
        assertNull(Lexicon.baseFromThird("is"))
        assertNull(Lexicon.baseFromThird("news"))
        assertNull(Lexicon.baseFromThird("dress"))

        assertEquals("go", Lexicon.baseFromPast("went"))
        assertEquals("like", Lexicon.baseFromPast("liked"))
        assertEquals("study", Lexicon.baseFromPast("studied"))
        assertEquals("stop", Lexicon.baseFromPast("stopped"))
        assertEquals("go", Lexicon.baseFromPast("gone"))
        assertEquals("use", Lexicon.baseFromPast("used"))
        assertNull(Lexicon.baseFromPast("need"))
        assertNull(Lexicon.baseFromPast("read"))
        assertNull(Lexicon.baseFromPast("red"))
        assertNull(Lexicon.baseFromPast("world"))
        assertNull(Lexicon.baseFromPast("hundred"))

        assertEquals("go", Lexicon.baseFromIng("going"))
        assertEquals("make", Lexicon.baseFromIng("making"))
        assertEquals("run", Lexicon.baseFromIng("running"))
        assertEquals("study", Lexicon.baseFromIng("studying"))
        assertNull(Lexicon.baseFromIng("morning"))
        assertNull(Lexicon.baseFromIng("evening"))
        assertNull(Lexicon.baseFromIng("thing"))
        assertNull(Lexicon.baseFromIng("king"))
    }

    @Test
    fun participleDanKelasKata() {
        assertEquals("gone", Lexicon.participle("go"))
        assertEquals("eaten", Lexicon.participle("eat"))
        assertEquals("written", Lexicon.participle("write"))
        assertNull(Lexicon.participle("walk"))
        assertTrue(Lexicon.isBase("go"))
        assertTrue(Lexicon.isBase("study"))
        assertFalse(Lexicon.isBase("went"))
        assertTrue(Lexicon.pastEqualsBase("read"))
        assertTrue(Lexicon.pastEqualsBase("put"))
        assertFalse(Lexicon.pastEqualsBase("go"))
        assertTrue(Lexicon.isIrregular("go"))
        assertFalse(Lexicon.isIrregular("walk"))
    }

    @Test
    fun setiapBentukBolakBalikKonsisten() {
        val verbs = listOf(
            "go", "do", "have", "say", "get", "make", "know", "think", "take", "see", "come", "give", "find", "tell", "feel", "buy",
            "eat", "drink", "drive", "write", "speak", "run", "sleep", "meet", "leave", "bring", "begin", "keep", "sit", "stand",
            "want", "like", "love", "need", "play", "work", "study", "live", "watch", "help", "use", "try", "call", "ask", "talk",
            "walk", "cook", "clean", "open", "close", "start", "stop", "listen", "learn", "visit", "enjoy", "plan", "carry", "cry"
        )
        for (v in verbs) {
            assertEquals("third($v)", v, Lexicon.baseFromThird(Lexicon.third(v)))
            assertEquals("ing($v)", v, Lexicon.baseFromIng(Lexicon.ing(v)))
            val p = Lexicon.past(v)
            if (p != v) assertEquals("past($v)=$p", v, Lexicon.baseFromPast(p))
        }
    }
}

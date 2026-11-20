package id.nine.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowTest {

    private fun log(vararg e: Pair<Boolean, String>) = ConversationLog().also { l -> e.forEach { l.add(it.first, it.second) } }

    @Test
    fun disabledShowsEverything() {
        val l = log(false to "halo", true to "a", false to "b")
        val w = HistoryWindow(l)
        assertEquals(0, w.start)
        assertEquals(3, w.count)
        l.add(true, "c")
        w.onAdded()
        assertEquals(0, w.start)
        assertEquals(4, w.count)
    }

    @Test
    fun enabledHidesOldHistory() {
        val l = log(false to "halo", true to "tanya lama", false to "jawab lama")
        val w = HistoryWindow(l)
        w.enable(true)
        assertEquals(3, w.start)
        assertEquals(0, w.count)
    }

    @Test
    fun repliesBeforeAnyQuestionAreShown() {
        val l = log(false to "lama")
        val w = HistoryWindow(l)
        w.enable(true)
        l.add(false, "salam")
        assertFalse(w.onAdded())
        assertEquals(1, w.start)
        assertEquals(1, w.count)
    }

    @Test
    fun newQuestionStartsNewWindow() {
        val l = log(false to "lama")
        val w = HistoryWindow(l)
        w.enable(true)
        l.add(true, "q1"); assertFalse(w.onAdded())
        l.add(false, "a1"); assertFalse(w.onAdded())
        assertEquals(1, w.start)
        assertEquals(2, w.count)
        l.add(true, "q2")
        assertTrue(w.onAdded())
        assertEquals(3, w.start)
        assertEquals(1, w.count)
        l.add(false, "a2a"); l.add(false, "a2b")
        w.onAdded(); w.onAdded()
        assertEquals(3, w.count)
    }

    @Test
    fun removalKeepsWindowConsistent() {
        val l = log(false to "lama")
        val w = HistoryWindow(l)
        w.enable(true)
        l.add(true, "q1"); w.onAdded()
        l.add(false, "a1"); w.onAdded()
        l.add(true, "q2"); w.onAdded()
        assertEquals(3, w.start)
        // hapus jawaban terakhir dari pertanyaan 1: awal jendela bergeser
        l.remove(2)
        assertTrue(w.onRemoved(2))
        assertEquals(2, w.start)
        assertEquals(1, w.count)
        // hapus entri lama sebelum jendela: lantai ikut turun
        l.remove(0)
        assertTrue(w.onRemoved(0))
        assertEquals(1, w.start)
        assertEquals(1, w.count)
    }

    @Test
    fun clearThenNewEntriesAreVisible() {
        val l = log(false to "lama", true to "q", false to "a")
        val w = HistoryWindow(l)
        w.enable(true)
        l.clear()
        w.onCleared()
        l.add(false, "baru")
        w.onAdded()
        assertEquals(0, w.start)
        assertEquals(1, w.count)
    }
}

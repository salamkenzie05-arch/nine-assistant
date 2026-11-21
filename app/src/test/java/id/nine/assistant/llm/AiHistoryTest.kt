package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHistoryTest {

    @Test
    fun emptyAndBlankInput() {
        assertTrue(AiHistory.build(emptyList()).isEmpty())
        assertTrue(AiHistory.build(listOf(true to "  ", false to "")).isEmpty())
    }

    @Test
    fun alternatesAndStartsWithUser() {
        val h = AiHistory.build(listOf(false to "salam", true to "a", true to "b", false to "c", true to "d"))
        assertEquals(listOf("user" to "a\nb", "model" to "c", "user" to "d"), h)
    }

    @Test
    fun keepsOnlyLastTurns() {
        val turns = (1..40).map { (it % 2 == 1) to "pesan $it" }
        val h = AiHistory.build(turns, maxTurns = 6)
        assertEquals(listOf("user", "model", "user", "model", "user", "model"), h.map { it.first }.let { if (it.first() == "user") it else it })
        assertTrue(h.last().second.contains("pesan 40") || h.last().second.contains("pesan 39"))
        assertTrue(h.size <= 6)
    }

    @Test
    fun budgetDropsOldestFirstAndKeepsNewest() {
        val big = "x".repeat(900)
        val turns = (1..20).map { (it % 2 == 1) to "$it $big" }
        val h = AiHistory.build(turns, maxTurns = 24, maxChars = 3000, perTurn = 1500)
        val total = h.sumOf { it.second.length }
        assertTrue("total $total", total <= 3100)
        assertTrue(h.last().second.startsWith("20 ") || h.last().second.contains("\n20 ") || h.last().second.startsWith("19 "))
        assertEquals("user", h.first().first)
    }

    @Test
    fun longTurnIsClippedOnWordBoundary() {
        val long = ("kata " .repeat(600)).trim()
        val h = AiHistory.build(listOf(true to long), perTurn = 100)
        assertTrue(h.single().second.endsWith("…"))
        assertTrue(h.single().second.length <= 101)
        assertTrue(!h.single().second.dropLast(1).endsWith(" "))
    }

    @Test
    fun newestTurnAlwaysKeptEvenIfOverBudget() {
        val h = AiHistory.build(listOf(true to "a".repeat(200)), maxChars = 10, perTurn = 150)
        assertEquals(1, h.size)
    }
}

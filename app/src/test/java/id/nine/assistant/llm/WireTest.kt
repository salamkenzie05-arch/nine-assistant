package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    // ------------------------------------------------------------ ChatRequest

    @Test
    fun turnsMergeAndStartWithUser() {
        val r = ChatRequest(
            "sys",
            listOf("model" to "halo dulu", "user" to "a", "user" to "b", "model" to "  ", "model" to "jawab", "user" to "c"),
            "pertanyaan"
        )
        assertEquals(
            listOf("user" to "a\nb", "model" to "jawab", "user" to "c\npertanyaan"),
            r.turns()
        )
    }

    @Test
    fun turnsAlwaysEndWithCurrentUserMessage() {
        val r = ChatRequest("", emptyList(), "hai")
        assertEquals(listOf("user" to "hai"), r.turns())
        val r2 = ChatRequest("", listOf("user" to "x", "model" to "y"), "z")
        assertEquals("user" to "z", r2.turns().last())
    }

    // ------------------------------------------------------------ Gemini

    @Test
    fun geminiBodyShape() {
        val v = MiniJson.parse(GeminiWire.body(ChatRequest("SYS", listOf("user" to "a", "model" to "b"), "c"), true))
        assertEquals("SYS", MiniJson.str(v, "system_instruction", "parts", 0, "text"))
        assertEquals("user", MiniJson.str(v, "contents", 0, "role"))
        assertEquals("model", MiniJson.str(v, "contents", 1, "role"))
        assertEquals("c", MiniJson.str(v, "contents", 2, "parts", 0, "text"))
        assertTrue(MiniJson.obj(v, "tools", 0, "google_search")!!.isEmpty())
        assertNull(MiniJson.get(v, "generationConfig"))
        val v2 = MiniJson.parse(GeminiWire.body(ChatRequest("", emptyList(), "x"), false))
        assertNull(MiniJson.get(v2, "tools"))
        assertNull(MiniJson.get(v2, "system_instruction"))
    }

    @Test
    fun geminiParsesText() {
        val e = GeminiWire.parseLine("""data: {"candidates":[{"content":{"parts":[{"text":"Ha"},{"text":"lo"}],"role":"model"}}]}""")
        assertEquals(SseEvent.Text("Halo"), e)
    }

    @Test
    fun geminiSkipsThoughtParts() {
        val e = GeminiWire.parseLine("""data: {"candidates":[{"content":{"parts":[{"text":"mikir","thought":true},{"text":"Jawab"}]}}]}""")
        assertEquals(SseEvent.Text("Jawab"), e)
        assertEquals(SseEvent.Skip, GeminiWire.parseLine("""data: {"candidates":[{"content":{"parts":[{"text":"mikir","thought":true}]}}]}"""))
    }

    @Test
    fun geminiBlockedAndErrors() {
        assertEquals(SseEvent.Blocked("SAFETY"), GeminiWire.parseLine("""data: {"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(SseEvent.Blocked("SAFETY"), GeminiWire.parseLine("""data: {"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertEquals(SseEvent.Skip, GeminiWire.parseLine("""data: {"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}]}"""))
        assertEquals(
            SseEvent.Error("Quota", 429),
            GeminiWire.parseLine("""data: {"error":{"code":429,"message":"Quota","status":"RESOURCE_EXHAUSTED"}}""")
        )
        // teks yang sudah ada tetap dipakai walau finishReason memblokir
        assertEquals(
            SseEvent.Text("x"),
            GeminiWire.parseLine("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"SAFETY"}]}""")
        )
    }

    @Test
    fun thinkingLevelIsSentOnlyWhenAsked() {
        val req = ChatRequest("sys", emptyList(), "halo")
        assertFalse(GeminiWire.body(req, false).contains("thinkingConfig"))
        val json = GeminiWire.body(req, false, thinking = true)
        val cfg = MiniJson.get(MiniJson.parseOrNull(json), "generationConfig", "thinkingConfig")
        assertEquals("MINIMAL", MiniJson.str(cfg, "thinkingLevel"))
    }
}

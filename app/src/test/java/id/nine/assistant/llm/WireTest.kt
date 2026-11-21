package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

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

    // ------------------------------------------------------------ OpenAI

    @Test
    fun openAiBodyHasSystemAndRoles() {
        val body = OpenAiWire.body("m1", ChatRequest("SYS", listOf("user" to "q1", "model" to "a1"), "q2"), "low")
        val v = MiniJson.parse(body)
        assertEquals("m1", MiniJson.str(v, "model"))
        assertEquals(true, MiniJson.get(v, "stream"))
        assertEquals("low", MiniJson.str(v, "reasoning_effort"))
        assertEquals("system", MiniJson.str(v, "messages", 0, "role"))
        assertEquals("SYS", MiniJson.str(v, "messages", 0, "content"))
        assertEquals("assistant", MiniJson.str(v, "messages", 2, "role"))
        assertEquals("q2", MiniJson.str(v, "messages", 3, "content"))
        assertNull(MiniJson.get(v, "temperature"))
    }

    @Test
    fun openAiBodyOmitsEffortWhenNull() {
        val v = MiniJson.parse(OpenAiWire.body("m", ChatRequest("", emptyList(), "hi"), null))
        assertNull(MiniJson.get(v, "reasoning_effort"))
        assertEquals("user", MiniJson.str(v, "messages", 0, "role"))
    }

    @Test
    fun reasoningEffortOnlyForGptOssOnGroqAndCerebras() {
        assertEquals("low", OpenAiWire.reasoningEffortFor("groq", "openai/gpt-oss-120b"))
        assertEquals("low", OpenAiWire.reasoningEffortFor("cerebras", "gpt-oss-120b"))
        assertNull(OpenAiWire.reasoningEffortFor("openrouter", "openai/gpt-oss-120b:free"))
        assertNull(OpenAiWire.reasoningEffortFor("groq", "llama-3.1-8b-instant"))
    }

    @Test
    fun openAiParsesTextChunks() {
        val e = OpenAiWire.parseLine("""data: {"choices":[{"delta":{"content":"Halo"},"index":0}]}""")
        assertEquals(SseEvent.Text("Halo"), e)
        assertEquals(SseEvent.Text("x"), OpenAiWire.parseLine("""data:{"choices":[{"delta":{"content":"x"}}]}"""))
    }

    @Test
    fun openAiSkipsNonTextLines() {
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine(": OPENROUTER PROCESSING"))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("event: ping"))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("data: "))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("data: {not json"))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("""data: {"choices":[{"delta":{"content":""}}]}"""))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("""data: {"choices":[{"delta":{"reasoning":"mikir"}}]}"""))
        assertEquals(SseEvent.Skip, OpenAiWire.parseLine("""data: {"choices":[]}"""))
        assertEquals(SseEvent.Done, OpenAiWire.parseLine("data: [DONE]"))
    }

    @Test
    fun openAiParsesInStreamError() {
        val e = OpenAiWire.parseLine("""data: {"error":{"code":429,"message":"Rate limit exceeded"}}""")
        assertEquals(SseEvent.Error("Rate limit exceeded", 429), e)
        val e2 = OpenAiWire.parseLine("""data: {"error":{"code":"server_error","message":"boom"},"choices":[{"finish_reason":"error"}]}""")
        assertEquals(SseEvent.Error("boom", null), e2)
        assertEquals(SseEvent.Error("plain", null), OpenAiWire.parseLine("""data: {"error":"plain"}"""))
    }

    @Test
    fun parseModelsFiltersInactiveAndNonTextAndPaid() {
        val groq = """{"object":"list","data":[{"id":"openai/gpt-oss-120b","active":true},{"id":"old-model","active":false},{"id":"whisper-large-v3"}]}"""
        assertEquals(listOf("openai/gpt-oss-120b", "whisper-large-v3"), OpenAiWire.parseModels(groq, "groq"))
        val or = """{"data":[{"id":"meta/llama:free","architecture":{"output_modalities":["text"]}},{"id":"paid/model"},
            {"id":"img:free","architecture":{"output_modalities":["image"]}},{"id":"openrouter/free"}]}"""
        assertEquals(listOf("meta/llama:free", "openrouter/free"), OpenAiWire.parseModels(or, "openrouter"))
        assertTrue(OpenAiWire.parseModels("not json", "groq").isEmpty())
        assertTrue(OpenAiWire.parseModels("""{"data":"x"}""", "groq").isEmpty())
    }

    // ------------------------------------------------------------ ThinkFilter

    private fun runFilter(chunks: List<String>): String {
        val f = ThinkFilter()
        val sb = StringBuilder()
        for (c in chunks) sb.append(f.feed(c))
        sb.append(f.finish())
        return sb.toString()
    }

    @Test
    fun thinkFilterRemovesBlocks() {
        assertEquals("Jawaban.", runFilter(listOf("<think>mikir keras</think>\n\nJawaban.")))
        assertEquals("A B", runFilter(listOf("A <think>x</think>B")))
        assertEquals("Halo", runFilter(listOf("Halo")))
        assertEquals("", runFilter(listOf("<think>tidak selesai")))
        assertEquals("Satu  Dua", runFilter(listOf("Satu <think>a</think> <think>b</think>Dua")))
    }

    @Test
    fun thinkFilterKeepsLiteralAngleBrackets() {
        assertEquals("5 < 6 dan <b>tebal</b>", runFilter(listOf("5 < 6 dan <b>tebal</b>")))
        assertEquals("akhir <thin", runFilter(listOf("akhir <thin")))
    }

    @Test
    fun thinkFilterHandlesAnySplit() {
        val text = "<think>pikir dulu <b>ya</b></think>\n\nHalo dunia, 5 < 6 dan <i>ok</i>.<think>lagi</think> Selesai."
        val expected = runFilter(listOf(text))
        assertEquals("Halo dunia, 5 < 6 dan <i>ok</i>. Selesai.", expected)
        val rnd = Random(42)
        repeat(500) {
            val chunks = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val n = 1 + rnd.nextInt(9)
                chunks.add(text.substring(i, minOf(text.length, i + n)))
                i += n
            }
            assertEquals("split ${chunks}", expected, runFilter(chunks))
        }
        // per karakter
        assertEquals(expected, runFilter(text.map { it.toString() }))
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
}

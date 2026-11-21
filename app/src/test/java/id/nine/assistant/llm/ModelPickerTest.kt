package id.nine.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerTest {

    @Test
    fun excludesNonChatModels() {
        for (id in listOf(
            "whisper-large-v3", "whisper-large-v3-turbo", "playai-tts", "canopylabs/orpheus-v1-english", "meta-llama/llama-prompt-guard-2-86m",
            "openai/gpt-oss-safeguard-20b", "text-embedding-004", "gemini-2.5-flash-image", "meta-llama/llama-3.2-90b-vision-preview",
            "qwen2.5-vl-72b", "groq/compound", "groq/compound-mini", "gemini-2.5-flash-native-audio", "veo-3", "imagen-4"
        )) {
            assertFalse("harus dibuang: $id", ModelPicker.isChatModel(id))
        }
        for (id in listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "qwen/qwen3-32b", "moonshotai/kimi-k2-instruct-0905", "gpt-oss-120b", "zai-glm-4.7")) {
            assertTrue("harus dipakai: $id", ModelPicker.isChatModel(id))
        }
    }

    @Test
    fun groqPrefersGptOss120bThenKimi() {
        val ids = listOf(
            "whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-20b", "moonshotai/kimi-k2-instruct-0905",
            "openai/gpt-oss-120b", "groq/compound", "qwen/qwen3-32b", "meta-llama/llama-4-scout-17b-16e-instruct"
        )
        assertEquals(listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905"), ModelPicker.pick("groq", ids, 2))
        assertEquals(
            listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905", "qwen/qwen3-32b", "openai/gpt-oss-20b"),
            ModelPicker.pick("groq", ids, 4)
        )
    }

    @Test
    fun deprecatedModelAbsentFromListIsNeverPicked() {
        val ids = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")
        assertFalse(ModelPicker.pick("groq", ids, 5).any { it.contains("llama-3.3") })
    }

    @Test
    fun cerebrasRanking() {
        val ids = listOf("llama3.1-8b", "qwen-3-235b-a22b-instruct-2507", "gpt-oss-120b", "zai-glm-4.7")
        assertEquals(listOf("gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507"), ModelPicker.pick("cerebras", ids, 2))
    }

    @Test
    fun openRouterTopFreeThenRouter() {
        val ids = listOf(
            "meta-llama/llama-3.2-3b-instruct:free", "openrouter/free", "openai/gpt-oss-120b:free", "qwen/qwen3-235b-a22b:free",
            "google/gemma-3-27b-it:free", "meta-llama/llama-3.3-70b-instruct:free"
        )
        val p = ModelPicker.pick("openrouter", ids, 3)
        assertEquals(listOf("openai/gpt-oss-120b:free", "meta-llama/llama-3.3-70b-instruct:free", "openrouter/free"), p)
    }

    @Test
    fun unknownModelsStillUsedWhenNothingRanked() {
        assertEquals(listOf("brand-new-model-x", "another-one"), ModelPicker.pick("groq", listOf("brand-new-model-x", "whisper-x", "another-one"), 3))
    }

    @Test
    fun emptyListFallsBackToDefaultsElsewhere() {
        assertTrue(ModelPicker.pick("groq", emptyList(), 2).isEmpty())
        assertEquals(listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"), ModelPicker.defaults("groq"))
        assertEquals(listOf("openrouter/free"), ModelPicker.defaults("openrouter"))
        assertTrue(ModelPicker.defaults("gemini").isEmpty())
    }
}

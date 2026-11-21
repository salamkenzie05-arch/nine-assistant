package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OnlinePromptTest {
    private val now = LocalDateTime.of(2026, 11, 21, 22, 13)
    private val p = Persona("Tuan", "sir")

    @Test
    fun keepsActionProtocolInBothLanguages() {
        for (lang in AppLang.values()) {
            val s = p.onlinePrompt(lang, now, "Bandung")
            assertTrue(s.contains("CMD: <"))
            assertTrue(s.contains("CMD: tambah acara rapat tim besok jam 11"))
            assertTrue(s.contains("CMD: tuning gitar"))
            assertTrue(s.contains("Bandung"))
            assertTrue(s.contains("2026"))
        }
    }

    @Test
    fun withoutSearchAdmitsNoWebAndUsesUnsurePhrase() {
        val id = p.onlinePrompt(AppLang.ID, now, "")
        assertTrue(id.contains("TIDAK punya akses internet"))
        assertTrue(id.contains("tidak bisa memeriksa informasi terkini secara langsung"))
        assertFalse(id.contains("terhubung ke internet lewat"))
        val en = p.onlinePrompt(AppLang.EN, now, "")
        assertTrue(en.contains("NO internet access"))
        assertTrue(en.contains("can't check current information directly"))
    }

    @Test
    fun withSearchToolSaysConnected() {
        val id = p.onlinePrompt(AppLang.ID, now, "", "Google Search")
        assertTrue(id.contains("terhubung ke internet lewat Google Search"))
        assertFalse(id.contains("TIDAK punya akses internet"))
    }

    @Test
    fun unsurePhraseTriggersAutoSearchDetector() {
        // kalimat yang diminta prompt harus dikenali ChitChat.looksUnsure agar Nine membuka Google
        assertTrue(id.nine.assistant.core.ChitChat.looksUnsure("Saya tidak bisa memeriksa informasi terkini secara langsung."))
        assertTrue(id.nine.assistant.core.ChitChat.looksUnsure("I can't check current information directly."))
    }

    @Test
    fun languageRuleAndPersonaPresent() {
        val s = p.onlinePrompt(AppLang.ID, now, "")
        assertTrue(s.contains("ATURAN BAHASA"))
        assertTrue(s.contains("\"Tuan\""))
        assertTrue(p.onlinePrompt(AppLang.EN, now, "").contains("LANGUAGE RULE"))
    }

    @Test
    fun whoAreYouNoLongerClaimsEverythingStaysOnDevice() {
        assertFalse(p.whoAreYou(AppLang.ID).contains("tidak pernah dikirim ke internet"))
        assertFalse(p.whoAreYou(AppLang.EN).contains("never leaves"))
    }

    @Test
    fun expressionRuleListsEveryMoodAndComesBeforeLanguageRule() {
        for (lang in AppLang.values()) {
            val online = p.onlinePrompt(lang, now, "")
            val gemma = p.systemPrompt(lang, now)
            for (s in listOf(online, gemma)) {
                assertTrue(s.contains("[happy]"))
                for (name in ExpressionTag.PROMPT_NAMES) assertTrue("$name belum ada di prompt $lang", s.contains(name))
                val rule = s.indexOf(if (lang == AppLang.ID) "EKSPRESI WAJAH" else "FACIAL EXPRESSION")
                val language = s.indexOf(if (lang == AppLang.ID) "ATURAN BAHASA" else "LANGUAGE RULE")
                assertTrue(rule in 0 until language) // aturan bahasa tetap instruksi terakhir
            }
        }
    }

    @Test
    fun everyPromptedMoodNameIsUnderstood() {
        for (name in ExpressionTag.PROMPT_NAMES) {
            assertTrue("$name tidak dikenali", ExpressionTag.parse("[$name] x").text == "x")
        }
    }

    @Test
    fun memoryRuleIsOptionalAndKeepsLanguageRuleLast() {
        for (lang in AppLang.values()) {
            val marker = if (lang == AppLang.ID) "INGATAN OTOMATIS" else "AUTOMATIC MEMORY"
            val language = if (lang == AppLang.ID) "ATURAN BAHASA" else "LANGUAGE RULE"
            for (s in listOf(p.onlinePrompt(lang, now, ""), p.systemPrompt(lang, now))) {
                assertTrue(s.indexOf(marker) in 0 until s.indexOf(language))
                assertTrue(s.contains(if (lang == AppLang.ID) "[ingat:" else "[remember:"))
            }
            assertFalse(p.onlinePrompt(lang, now, "", autoMemory = false).contains(marker))
            assertFalse(p.systemPrompt(lang, now, autoMemory = false).contains(marker))
        }
    }

    @Test
    fun theExamplesInTheMemoryRuleAreParsedByTheTagParser() {
        val id = ExpressionTag.parse("[happy] [ingat: Nama pengguna Budi] Halo, Budi.")
        assertEquals(listOf("Nama pengguna Budi"), id.facts)
        val en = ExpressionTag.parse("[happy] [remember: User's name is Budi] Hello, Budi.")
        assertEquals(listOf("User's name is Budi"), en.facts)
    }
}

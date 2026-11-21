package id.nine.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tes logika murni Nine 2.7: jatah hati harian, hari pemakaian kedekatan, deteksi hinaan, obrolan ringan. */
class Round2LogicTest {

    // ------------------------------------------------------------------ jatah harian (muncratan hati)

    @Test
    fun heartQuotaAllowsThreeBurstsPerDay() {
        var day = ""
        var count = 0
        for (i in 1..3) {
            val used = DailyQuota.tryUse("2026-11-20", day, count, 3)
            assertNotNull("pemakaian ke-$i harus diizinkan", used)
            day = used!!.first
            count = used.second
            assertEquals(i, count)
        }
        assertNull("pemakaian ke-4 harus ditolak", DailyQuota.tryUse("2026-11-20", day, count, 3))
        assertEquals(0, DailyQuota.remaining("2026-11-20", day, count, 3))
    }

    @Test
    fun heartQuotaResetsOnNewDay() {
        val used = DailyQuota.tryUse("2026-11-21", "2026-11-20", 3, 3)
        assertEquals("2026-11-21" to 1, used)
        assertEquals(3, DailyQuota.remaining("2026-11-21", "2026-11-20", 3, 3))
        assertEquals(2, DailyQuota.remaining("2026-11-20", "2026-11-20", 1, 3))
    }

    // ------------------------------------------------------------------ kedekatan: 90 hari

    @Test
    fun affinityTopLevelNeedsNinetyDays() {
        assertEquals(1, AffinityRules.levelByDays(0))
        assertEquals(2, AffinityRules.levelByDays(3))
        assertEquals(8, AffinityRules.levelByDays(89))
        assertEquals(9, AffinityRules.levelByDays(90))
        assertEquals(9, AffinityRules.levelByDays(400))
    }

    @Test
    fun affinityEffectiveLevelTakesTheLowerOfXpAndDays() {
        assertEquals("XP tinggi tetapi baru 10 hari", 3, AffinityRules.effectiveLevel(9, 10))
        assertEquals("hari banyak tetapi XP rendah", 2, AffinityRules.effectiveLevel(2, 100))
        assertEquals(9, AffinityRules.effectiveLevel(9, 90))
        assertEquals("nilai di luar rentang dipangkas", 9, AffinityRules.effectiveLevel(10, 90))
        assertEquals(1, AffinityRules.effectiveLevel(0, 0))
    }

    @Test
    fun affinityDaysLeftAndProgress() {
        assertEquals(90, AffinityRules.daysLeft(0))
        assertEquals(60, AffinityRules.daysLeft(30))
        assertEquals(0, AffinityRules.daysLeft(120))
        assertEquals(1f, AffinityRules.daysProgress(9, 10), 0f)
        val p = AffinityRules.daysProgress(1, 0)
        assertTrue(p in 0f..1f)
        assertEquals(1f, AffinityRules.daysProgress(1, 3), 0f)
    }

    // ------------------------------------------------------------------ hinaan & amarah

    @Test
    fun insultsAreDetectedWhenAimedAtNine() {
        assertEquals(Insults.Kind.INSULT, Insults.detect("kamu bodoh"))
        assertEquals(Insults.Kind.INSULT, Insults.detect("you are stupid"))
        assertEquals(Insults.Kind.ANGRY, Insults.detect("dasar anjing kamu"))
        assertEquals(Insults.Kind.ANGRY, Insults.detect("aku marah sama kamu"))
        assertEquals(Insults.Kind.ANGRY, Insults.detect("shut up"))
    }

    @Test
    fun insultsIgnoreNormalSentences() {
        assertEquals(Insults.Kind.NONE, Insults.detect("cuaca hari ini bagus sekali"))
        assertEquals(Insults.Kind.NONE, Insults.detect("anjing saya lucu"))
        assertEquals(Insults.Kind.NONE, Insults.detect("kenapa langit biru"))
        assertEquals(Insults.Kind.NONE, Insults.detect(""))
    }

    @Test
    fun insultMoodsAreSadOrScaredAndRepliesNeverEmpty() {
        assertEquals(Mood.SAD, Insults.mood(Insults.Kind.INSULT))
        assertEquals(Mood.SCARED, Insults.mood(Insults.Kind.ANGRY))
        for (kind in listOf(Insults.Kind.INSULT, Insults.Kind.ANGRY)) {
            for (lang in AppLang.values()) {
                for (pick in -5..12) {
                    assertTrue(Insults.reply(kind, lang, ", Tuan", pick).isNotBlank())
                }
            }
        }
    }

    // ------------------------------------------------------------------ obrolan ala SimSimi

    @Test
    fun chitChatAnswersCommonWhyQuestions() {
        assertTrue(ChitChat.reply("kenapa langit biru", AppLang.ID)!!.contains("Rayleigh"))
        assertTrue(ChitChat.reply("why is the sky blue", AppLang.EN)!!.contains("Rayleigh"))
        assertNotNull(ChitChat.reply("kenapa laut asin", AppLang.ID))
        assertNotNull(ChitChat.reply("ceritakan lelucon dong", AppLang.ID))
        assertNull(ChitChat.reply("qwertyuiop asdfgh", AppLang.ID))
    }

    @Test
    fun chitChatVariationsNeverCrash() {
        for (pick in -7..20) {
            assertNotNull(ChitChat.reply("ceritakan lelucon dong", AppLang.ID, pick))
            assertTrue(ChitChat.playful(AppLang.ID, pick).isNotBlank())
            assertTrue(ChitChat.playful(AppLang.EN, pick).isNotBlank())
        }
    }

    @Test
    fun unknownQuestionsAreWorthSearchingButPersonalOnesAreNot() {
        assertTrue(ChitChat.worthSearching("siapa presiden amerika sekarang"))
        assertTrue(ChitChat.worthSearching("what is the tallest mountain in the world"))
        assertFalse(ChitChat.worthSearching("apa nama anjing saya"))
        assertFalse(ChitChat.worthSearching("halo"))
        assertTrue(ChitChat.looksLikeQuestion("kenapa ikan bisa berenang"))
        assertFalse(ChitChat.looksLikeQuestion("nyalakan senter"))
    }

    @Test
    fun unsureAnswersTriggerSearchFallback() {
        assertTrue(ChitChat.looksUnsure("Maaf, saya tidak tahu soal itu."))
        assertTrue(ChitChat.looksUnsure("I don't know the answer to that."))
        assertTrue(ChitChat.looksUnsure("I can't access real-time news."))
        assertFalse(ChitChat.looksUnsure("Ibu kota Prancis adalah Paris."))
        assertFalse(ChitChat.looksUnsure("Paris is the capital of France."))
        assertTrue(ChitChat.searchNotice(AppLang.ID, ", Tuan").contains("Google"))
        assertTrue(ChitChat.searchNotice(AppLang.EN, "").contains("Google"))
    }
}

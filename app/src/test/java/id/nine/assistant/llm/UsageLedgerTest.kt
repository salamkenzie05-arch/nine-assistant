package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class UsageLedgerTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private val ok = Attempt(null, TokenUsage(100, 40))
    private fun limited(daily: Int? = null) = Attempt(Failure(FailKind.QUOTA_DAILY, 429, "x", null, daily), null)

    private class Clock(var now: Long)

    // ------------------------------------------------------------ QuotaClock

    @Test
    fun quotaDayFollowsPacificMidnight() {
        // November = waktu standar (UTC-8): hari baru mulai 08.00 UTC = 15.00 WIB
        assertEquals("2026-11-21", QuotaClock.dayKey(utc(2026, 11, 22, 7, 59)))
        assertEquals("2026-11-22", QuotaClock.dayKey(utc(2026, 11, 22, 8, 0)))
        assertEquals(utc(2026, 11, 22, 8, 0), QuotaClock.nextResetMs(utc(2026, 11, 21, 17, 0)))
    }

    @Test
    fun quotaDayHandlesDaylightSavingTime() {
        // Juli = waktu musim panas (UTC-7): reset 07.00 UTC = 14.00 WIB
        assertEquals(utc(2026, 7, 16, 7, 0), QuotaClock.nextResetMs(utc(2026, 7, 15, 12, 0)))
        // hari pergantian jam (8 Nov 2026): hari itu 25 jam, reset tetap tengah malam Pasifik
        assertEquals(utc(2026, 11, 8, 8, 0), QuotaClock.nextResetMs(utc(2026, 11, 7, 20, 0)))
    }

    // ------------------------------------------------------------ UsageLedger

    @Test
    fun countsRequestsAndTokensPerModel() {
        val c = Clock(utc(2026, 11, 21, 20))
        val l = UsageLedger(MemoryUsageStore()) { c.now }
        assertTrue(l.snapshot().isEmpty)
        l.record("gemini-flash-latest", ok)
        l.record("gemini-flash-latest", ok)
        l.record("gemini-flash-lite-latest", Attempt(null, null)) // penyedia tak melaporkan token
        val s = l.snapshot()
        assertEquals(3, s.ok)
        assertEquals(280L, s.tokens)
        val flash = s.models.first { it.model == "gemini-flash-latest" }
        assertEquals(2, flash.ok)
        assertEquals(200L, flash.inTokens)
        assertEquals(80L, flash.outTokens)
        assertNull(flash.dailyLimit)
        assertNull(flash.remaining)
    }

    @Test
    fun networkFailuresAreNotCountedButServerErrorsAre() {
        val l = UsageLedger(MemoryUsageStore()) { utc(2026, 11, 21, 20) }
        l.record("m", Attempt(Failure(FailKind.NETWORK, null, "offline"), null))
        assertTrue(l.snapshot().isEmpty)
        l.record("m", Attempt(Failure(FailKind.SERVER, 503, "busy"), null))
        val m = l.snapshot().models.single()
        assertEquals(1, m.failed)
        assertEquals(0, m.ok)
    }

    @Test
    fun learnsDailyLimitFromQuotaErrorsAndEstimatesRemaining() {
        val c = Clock(utc(2026, 11, 21, 20))
        val l = UsageLedger(MemoryUsageStore()) { c.now }
        repeat(3) { l.record("m", ok) }
        l.record("m", limited(250))
        val m = l.snapshot().models.single()
        assertEquals(1, m.limited)
        assertEquals(250, m.dailyLimit)
        assertEquals(247, m.remaining)
        assertEquals(c.now, m.lastLimitAt)
        // batas tanpa angka tidak menghapus angka yang sudah dipelajari
        l.record("m", limited(null))
        assertEquals(250, l.snapshot().models.single().dailyLimit)
    }

    @Test
    fun countsResetWhenQuotaDayChangesButLearnedLimitStays() {
        val c = Clock(utc(2026, 11, 22, 7, 30)) // 23.30 Pasifik, 21 Nov
        val l = UsageLedger(MemoryUsageStore()) { c.now }
        repeat(5) { l.record("m", ok) }
        l.record("m", limited(250))
        c.now = utc(2026, 11, 22, 8, 1) // lewat tengah malam Pasifik
        val s = l.snapshot()
        val m = s.models.single()
        assertEquals(0, m.ok)
        assertEquals(0, m.limited)
        assertEquals(250, m.dailyLimit)
        assertEquals(250, m.remaining)
        // permintaan baru memulai hitungan hari baru dari nol
        l.record("m", ok)
        assertEquals(1, l.snapshot().models.single().ok)
    }

    @Test
    fun persistsAcrossInstances() {
        val store = MemoryUsageStore()
        val now = utc(2026, 11, 21, 20)
        val a = UsageLedger(store) { now }
        a.record("m", ok)
        a.record("m", limited(100))
        val b = UsageLedger(store) { now }
        val m = b.snapshot().models.single()
        assertEquals(1, m.ok)
        assertEquals(1, m.limited)
        assertEquals(100, m.dailyLimit)
        assertEquals(140L, m.tokens)
    }

    @Test
    fun corruptStoreStartsEmptyInsteadOfCrashing() {
        for (bad in listOf("", "bukan json", "[1,2", """{"m":"x","limits":5}""", """{"day":3}""")) {
            val store = MemoryUsageStore()
            store.save(bad)
            val l = UsageLedger(store) { utc(2026, 11, 21, 20) }
            assertTrue("'$bad'", l.snapshot().isEmpty)
            l.record("m", ok) // tetap bisa mencatat
            assertEquals(1, l.snapshot().ok)
        }
    }

    @Test
    fun resetTodayClearsCountsButKeepsLimit() {
        val l = UsageLedger(MemoryUsageStore()) { utc(2026, 11, 21, 20) }
        l.record("m", ok)
        l.record("m", limited(250))
        l.resetToday()
        val m = l.snapshot().models.single()
        assertEquals(0, m.ok)
        assertEquals(250, m.dailyLimit)
    }

    @Test
    fun safeUnderConcurrentUse() {
        val l = UsageLedger(MemoryUsageStore()) { utc(2026, 11, 21, 20) }
        val threads = (1..8).map { Thread { repeat(100) { l.record("m", ok); l.snapshot() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(800, l.snapshot().ok)
    }

    // ------------------------------------------------------------ UsageText

    private val wib: ZoneId = ZoneId.of("Asia/Jakarta")

    @Test
    fun formatsEmptyDay() {
        val now = utc(2026, 11, 21, 20)
        val l = UsageLedger(MemoryUsageStore()) { now }
        val t = UsageText.format(l.snapshot(), emptyList(), AppLang.ID, now, wib)
        assertTrue(t.startsWith("Belum ada permintaan"))
        assertTrue(t.contains("direset 15.00")) // 08.00 UTC = 15.00 WIB
        assertTrue(t.contains("(12 j 0 mnt lagi)")) // dari 20.00 UTC ke 08.00 UTC = 12 jam
    }

    @Test
    fun formatsUsageWithRemainingAndCooling() {
        val now = utc(2026, 11, 21, 20)
        val l = UsageLedger(MemoryUsageStore()) { now }
        repeat(20) { l.record("gemini-flash-latest", ok) }
        l.record("gemini-flash-latest", limited(250))
        l.record("gemini-flash-lite-latest", ok)
        val cool = listOf(Cooling("gemini-flash-latest", now + 30 * 60_000L, Failure(FailKind.QUOTA_DAILY, 429, "")))
        val t = UsageText.format(l.snapshot(), cool, AppLang.ID, now, wib)
        assertTrue(t, t.contains("Hari ini: 21 permintaan berhasil"))
        assertTrue(t, t.contains("1× kena batas"))
        assertTrue(t, t.contains("• gemini-flash-latest: 20 permintaan · sisa ±230 dari 250 · kena batas 1×"))
        assertTrue(t, t.contains("• gemini-flash-lite-latest: 1 permintaan"))
        assertTrue(t, t.contains("Istirahat: gemini-flash-latest · kuota harian habis · sampai 03.30")) // 20.30 UTC = 03.30 WIB
        val en = UsageText.format(l.snapshot(), cool, AppLang.EN, now, wib)
        assertTrue(en, en.contains("Today: 21 successful requests"))
        assertTrue(en, en.contains("~230 of 250 left"))
        assertTrue(en, en.contains("• gemini-flash-lite-latest: 1 request\n"))
        assertTrue(en, en.contains("resets at 15.00 (in 12 h 0 m)"))
    }

    @Test
    fun formatsTokensAndDurations() {
        assertEquals("999", UsageText.tokens(999, AppLang.ID))
        assertEquals("1 rb", UsageText.tokens(1000, AppLang.ID))
        assertEquals("41,2 rb", UsageText.tokens(41_234, AppLang.ID))
        assertEquals("41.2 k", UsageText.tokens(41_234, AppLang.EN))
        assertEquals("1,5 jt", UsageText.tokens(1_500_000, AppLang.ID))
        assertEquals("kurang dari 1 mnt", UsageText.remaining(20_000, AppLang.ID))
        assertEquals("5 mnt", UsageText.remaining(5 * 60_000L, AppLang.ID))
        assertEquals("2 j 5 mnt", UsageText.remaining(125 * 60_000L, AppLang.ID))
        assertEquals("under 1 min", UsageText.remaining(-5, AppLang.EN))
        assertEquals("03.30", UsageText.clock(Instant.parse("2026-11-21T20:30:00Z").toEpochMilli(), wib))
    }
}

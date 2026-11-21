package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FailuresTest {

    @Test
    fun authCodes() {
        assertEquals(FailKind.AUTH, Failures.fromHttp(401, """{"error":{"message":"Invalid API Key"}}""", null).kind)
        assertEquals(FailKind.AUTH, Failures.fromHttp(403, "", null).kind)
    }

    @Test
    fun geminiInvalidKeyIs400ButAuth() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        val f = Failures.fromHttp(400, body, null)
        assertEquals(FailKind.AUTH, f.kind)
        assertEquals("API key not valid. Please pass a valid API key.", f.message)
    }

    @Test
    fun modelNotFound() {
        assertEquals(FailKind.NOT_FOUND, Failures.fromHttp(404, """{"error":{"message":"models/xyz is not found"}}""", null).kind)
        assertEquals(FailKind.NOT_FOUND, Failures.fromHttp(400, """{"error":{"message":"The model `llama-3.3-70b-versatile` has been decommissioned"}}""", null).kind)
        assertEquals(FailKind.NOT_FOUND, Failures.fromHttp(402, "payment required", null).kind)
    }

    @Test
    fun rateLimitVersusDaily() {
        val minute = """{"error":{"message":"Rate limit reached for model x on tokens per minute (TPM): Limit 8000"}}"""
        assertEquals(FailKind.RATE_LIMIT, Failures.fromHttp(429, minute, "7").kind)
        assertEquals(7000L, Failures.fromHttp(429, minute, "7").retryAfterMs)
        val day = """{"error":{"message":"Rate limit reached for model x on tokens per day (TPD): Limit 100000"}}"""
        assertEquals(FailKind.QUOTA_DAILY, Failures.fromHttp(429, day, null).kind)
        val gem = """{"error":{"code":429,"message":"You exceeded your current quota","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},{"retryDelay":"34s"}]}}"""
        assertEquals(FailKind.QUOTA_DAILY, Failures.fromHttp(429, gem, null).kind)
        val gemMin = """{"error":{"code":429,"message":"quota","details":[{"@type":"RetryInfo","retryDelay":"34s"}]}}"""
        val f = Failures.fromHttp(429, gemMin, null)
        assertEquals(FailKind.RATE_LIMIT, f.kind)
        assertEquals(34000L, f.retryAfterMs)
    }

    @Test
    fun serverAndOthers() {
        assertEquals(FailKind.SERVER, Failures.fromHttp(503, "overloaded", null).kind)
        assertEquals(FailKind.SERVER, Failures.fromHttp(529, "", null).kind)
        assertEquals(FailKind.TOO_LARGE, Failures.fromHttp(413, "", null).kind)
        assertEquals(FailKind.TOO_LARGE, Failures.fromHttp(400, """{"error":{"message":"maximum context length exceeded"}}""", null).kind)
        assertEquals(FailKind.BAD_REQUEST, Failures.fromHttp(400, """{"error":{"message":"unknown parameter foo"}}""", null).kind)
        assertEquals(FailKind.OTHER, Failures.fromHttp(418, "", null).kind)
        assertEquals("HTTP 500", Failures.fromHttp(500, "", null).message)
    }

    @Test
    fun errorMessageShapes() {
        assertEquals("abc", Failures.errorMessage("""{"error":{"message":"abc"}}"""))
        assertEquals("abc", Failures.errorMessage("""{"error":"abc"}"""))
        assertEquals("abc", Failures.errorMessage("""[{"error":{"message":"abc"}}]"""))
        assertEquals("teks biasa", Failures.errorMessage("  teks   biasa "))
        assertEquals(300, Failures.errorMessage("x".repeat(1000)).length)
    }

    @Test
    fun streamErrors() {
        assertEquals(FailKind.RATE_LIMIT, Failures.fromStream("Rate limit exceeded", 429).kind)
        assertEquals(FailKind.RATE_LIMIT, Failures.fromStream("Provider returned error: rate-limited", null).kind)
        assertEquals(FailKind.QUOTA_DAILY, Failures.fromStream("free-models-per-day quota exceeded", null).kind)
        assertEquals(FailKind.OTHER, Failures.fromStream("boom", null).kind)
        assertEquals(FailKind.SERVER, Failures.fromStream("boom", 502).kind)
    }

    @Test
    fun exceptions() {
        assertEquals(FailKind.NETWORK, Failures.fromException(UnknownHostException("x")).kind)
        assertEquals(FailKind.NETWORK, Failures.fromException(ConnectException("x")).kind)
        assertEquals(FailKind.NETWORK, Failures.fromException(SocketTimeoutException("connect timed out")).kind)
        assertEquals(FailKind.TIMEOUT, Failures.fromException(SocketTimeoutException("Read timed out")).kind)
        assertEquals(FailKind.NETWORK, Failures.fromException(java.io.IOException("Socket closed")).kind)
        assertEquals(FailKind.OTHER, Failures.fromException(IllegalStateException("x")).kind)
        val pf = Failure(FailKind.AUTH, 401, "m")
        assertEquals(pf, Failures.fromException(ProviderException(pf)))
    }

    @Test
    fun cooldownRules() {
        assertEquals(60_000L, Failures.cooldownMs(Failure(FailKind.RATE_LIMIT, 429, "")))
        assertEquals(7_000L, Failures.cooldownMs(Failure(FailKind.RATE_LIMIT, 429, "", 7_000)))
        assertEquals(5_000L, Failures.cooldownMs(Failure(FailKind.RATE_LIMIT, 429, "", 100)))
        assertEquals(600_000L, Failures.cooldownMs(Failure(FailKind.RATE_LIMIT, 429, "", 9_999_999)))
        assertEquals(0L, Failures.cooldownMs(Failure(FailKind.BLOCKED, null, "")))
        assertTrue(Failures.cooldownMs(Failure(FailKind.QUOTA_DAILY, 429, "")) > Failures.cooldownMs(Failure(FailKind.RATE_LIMIT, 429, "")))
    }

    @Test
    fun providerWideRules() {
        assertTrue(Failures.providerWide(Failure(FailKind.AUTH, 401, ""), "groq"))
        assertTrue(Failures.providerWide(Failure(FailKind.NETWORK, null, ""), "groq"))
        assertFalse(Failures.providerWide(Failure(FailKind.RATE_LIMIT, 429, ""), "groq"))
        assertTrue(Failures.providerWide(Failure(FailKind.RATE_LIMIT, 429, ""), "openrouter"))
        assertFalse(Failures.providerWide(Failure(FailKind.SERVER, 503, ""), "openrouter"))
    }

    @Test
    fun cooldownBookExpires() {
        val b = CooldownBook()
        val f = Failure(FailKind.RATE_LIMIT, 429, "x")
        b.mark("k", 1000, f)
        assertEquals(f, b.active("k", 999)!!.failure)
        assertNull(b.active("k", 1000))
        assertNull(b.active("k", 500)) // sudah dihapus setelah kedaluwarsa
        b.mark("a", 5000, f)
        b.forget("a")
        assertNull(b.active("a", 0))
        b.mark("b", 5000, f)
        b.clear()
        assertNull(b.active("b", 0))
    }

    @Test
    fun describesInBothLanguages() {
        for (k in FailKind.values()) {
            val f = Failure(k, 123, "m")
            assertTrue(Failures.describe(f, AppLang.ID).isNotBlank())
            assertTrue(Failures.describe(f, AppLang.EN).isNotBlank())
        }
        assertEquals("kuota harian habis", Failures.describe(Failure(FailKind.QUOTA_DAILY, 429, ""), AppLang.ID))
    }
}

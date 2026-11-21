package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class FailKind { RATE_LIMIT, QUOTA_DAILY, AUTH, NOT_FOUND, BAD_REQUEST, TOO_LARGE, SERVER, TIMEOUT, NETWORK, BLOCKED, EMPTY, OTHER }

/** [dailyLimit]: batas permintaan per hari yang disebut Google di galat kuota (bila ada). */
data class Failure(val kind: FailKind, val code: Int?, val message: String, val retryAfterMs: Long? = null, val dailyLimit: Int? = null)

/** Kegagalan dari satu panggilan ke penyedia. */
class ProviderException(val failure: Failure) : IOException(failure.message)

/** Dibatalkan pengguna. */
class CancelledException : RuntimeException()

object Failures {

    private val DAILY = Regex("per[ -]?day|daily|\\bRPD\\b|\\bTPD\\b", RegexOption.IGNORE_CASE)
    private val RATE_WORDS = Regex("rate.?limit|quota|too many requests|resource.?exhausted|overloaded|capacity", RegexOption.IGNORE_CASE)
    private val KEY_WORDS = Regex("api.?key|api_key_invalid|invalid.?key|unauthorized|authentication|permission", RegexOption.IGNORE_CASE)
    private val MODEL_WORDS = Regex("model.*(not found|does not exist|not supported|decommission|deprecat|unavailable|no longer)|(not found|does not exist|decommission|deprecat).*model", RegexOption.IGNORE_CASE)
    private val LONG_WORDS = Regex("context|too long|too large|maximum|token", RegexOption.IGNORE_CASE)

    /** "Retry-After: 12" (detik) atau, dari badan Gemini, "retryDelay": "34s". */
    fun retryAfterMs(header: String?, body: String): Long? {
        header?.trim()?.toDoubleOrNull()?.let { if (it >= 0) return (it * 1000).toLong() }
        val m = Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    fun fromHttp(code: Int, body: String, retryAfterHeader: String?): Failure {
        val msg = errorMessage(body)
        val retry = retryAfterMs(retryAfterHeader, body)
        val kind = when {
            code == 401 || code == 403 -> FailKind.AUTH
            code == 429 -> if (DAILY.containsMatchIn(body)) FailKind.QUOTA_DAILY else FailKind.RATE_LIMIT
            code == 404 || code == 402 -> FailKind.NOT_FOUND
            code == 413 -> FailKind.TOO_LARGE
            code == 408 -> FailKind.TIMEOUT
            code in 500..599 -> FailKind.SERVER
            code == 400 || code == 422 -> when {
                KEY_WORDS.containsMatchIn(body) && !MODEL_WORDS.containsMatchIn(body) -> FailKind.AUTH
                MODEL_WORDS.containsMatchIn(body) -> FailKind.NOT_FOUND
                LONG_WORDS.containsMatchIn(msg) -> FailKind.TOO_LARGE
                else -> FailKind.BAD_REQUEST
            }
            else -> FailKind.OTHER
        }
        return Failure(kind, code, msg.ifBlank { "HTTP $code" }, retry, if (code == 429) dailyRequestLimit(body) else null)
    }

    /**
     * Batas permintaan per hari yang disebut Google di galat 429: detail "QuotaFailure" berisi "quotaId" (…PerDay…)
     * dan "quotaValue". Null bila tidak ada, bukan JSON, atau bukan batas harian.
     */
    fun dailyRequestLimit(body: String): Int? {
        val v0 = MiniJson.parseOrNull(body)
        val v = if (v0 is List<*>) v0.firstOrNull() else v0
        val details = MiniJson.arr(v, "error", "details") ?: return null
        for (d in details) {
            val violations = MiniJson.arr(d, "violations") ?: continue
            for (x in violations) {
                val id = MiniJson.str(x, "quotaId") ?: continue
                if (!id.contains("PerDay", ignoreCase = true) || !id.contains("Request", ignoreCase = true)) continue
                val value = when (val q = MiniJson.get(x, "quotaValue")) {
                    is String -> q.trim().toLongOrNull()
                    is Number -> q.toLong()
                    else -> null
                } ?: continue
                if (value in 1..10_000_000L) return value.toInt()
            }
        }
        return null
    }

    /** Galat di tengah aliran: [code] bila ada, kalau tidak lihat isi pesannya. */
    fun fromStream(message: String, code: Int?): Failure {
        if (code != null && code in 100..599) return fromHttp(code, message, null)
        val kind = when {
            DAILY.containsMatchIn(message) && RATE_WORDS.containsMatchIn(message) -> FailKind.QUOTA_DAILY
            RATE_WORDS.containsMatchIn(message) -> FailKind.RATE_LIMIT
            else -> FailKind.OTHER
        }
        return Failure(kind, code, message)
    }

    fun fromException(e: Throwable): Failure = when (e) {
        is ProviderException -> e.failure
        is UnknownHostException, is ConnectException -> Failure(FailKind.NETWORK, null, e.message ?: e.javaClass.simpleName)
        is SocketTimeoutException ->
            if (e.message?.contains("connect", ignoreCase = true) == true) Failure(FailKind.NETWORK, null, e.message ?: "connect timed out")
            else Failure(FailKind.TIMEOUT, null, e.message ?: "timed out")
        is IOException -> Failure(FailKind.NETWORK, null, e.message ?: e.javaClass.simpleName)
        else -> Failure(FailKind.OTHER, null, e.message ?: e.javaClass.simpleName)
    }

    /** Isi pesan galat dari badan JSON ({"error":{"message":…}} atau {"error":"…"}); bila bukan JSON, potongan awalnya. */
    fun errorMessage(body: String): String {
        val v0 = MiniJson.parseOrNull(body)
        val v = if (v0 is List<*>) v0.firstOrNull() else v0
        val m = MiniJson.str(v, "error", "message") ?: MiniJson.str(v, "error") ?: MiniJson.str(v, "message")
        return (m ?: body).trim().replace(Regex("\\s+"), " ").take(300)
    }

    /** Kegagalan ini berlaku untuk seluruh penyedia (kunci salah, jaringan putus), bukan hanya satu model? Kuota Gemini per model. */
    fun providerWide(f: Failure): Boolean = f.kind == FailKind.AUTH || f.kind == FailKind.NETWORK

    /** Berapa lama sebuah penyedia/model dibiarkan istirahat setelah gagal (0 = tidak perlu). */
    fun cooldownMs(f: Failure): Long = when (f.kind) {
        FailKind.RATE_LIMIT -> (f.retryAfterMs ?: 60_000L).coerceIn(5_000L, 10 * 60_000L)
        FailKind.QUOTA_DAILY -> 30 * 60_000L
        FailKind.AUTH -> 60 * 60_000L
        FailKind.NOT_FOUND -> 6 * 60 * 60_000L
        FailKind.BAD_REQUEST -> 30 * 60_000L
        FailKind.TOO_LARGE -> 5 * 60_000L
        FailKind.SERVER, FailKind.TIMEOUT, FailKind.OTHER -> 2 * 60_000L
        FailKind.EMPTY -> 60_000L
        FailKind.NETWORK -> 20_000L
        FailKind.BLOCKED -> 0L
    }

    /** Kegagalan singkat dalam bahasa pengguna, mis. "kuota harian habis". */
    fun describe(f: Failure, lang: AppLang): String = when (f.kind) {
        FailKind.AUTH -> lang.t("kunci ditolak", "key rejected")
        FailKind.RATE_LIMIT -> lang.t("sedang dibatasi, tunggu sebentar", "rate limited, wait a moment")
        FailKind.QUOTA_DAILY -> lang.t("kuota harian habis", "daily quota used up")
        FailKind.NOT_FOUND -> lang.t("model tidak tersedia", "model unavailable")
        FailKind.BAD_REQUEST -> lang.t("permintaan ditolak", "request rejected")
        FailKind.TOO_LARGE -> lang.t("percakapan terlalu panjang", "conversation too long")
        FailKind.SERVER -> lang.t("server sedang sibuk", "server busy")
        FailKind.TIMEOUT -> lang.t("terlalu lama merespons", "took too long")
        FailKind.NETWORK -> lang.t("tidak terhubung", "can't connect")
        FailKind.BLOCKED -> lang.t("jawaban diblokir", "reply blocked")
        FailKind.EMPTY -> lang.t("jawaban kosong", "empty reply")
        FailKind.OTHER -> lang.t("galat" + (f.code?.let { " $it" } ?: ""), "error" + (f.code?.let { " $it" } ?: ""))
    }
}

/** Catatan penyedia/model yang sedang "istirahat" (kuota habis, kunci salah, dll.). Aman dipakai dari banyak thread. */
class CooldownBook {
    class Entry(val until: Long, val failure: Failure)

    private val map = HashMap<String, Entry>()

    @Synchronized
    fun mark(key: String, until: Long, failure: Failure) {
        map[key] = Entry(until, failure)
    }

    /** Catatan yang masih berlaku pada [now], atau null. */
    @Synchronized
    fun active(key: String, now: Long): Entry? {
        val e = map[key] ?: return null
        if (e.until <= now) {
            map.remove(key)
            return null
        }
        return e
    }

    @Synchronized
    fun forget(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() = map.clear()
}

package id.nine.assistant.llm

import java.time.Instant
import java.time.ZoneId

/**
 * Hari kuota Gemini: kuota harian (RPD) tingkat gratis direset tengah malam waktu Pasifik (±15.00 WIB di musim dingin AS,
 * ±14.00 WIB di musim panas). Memakai aturan zona waktu sebenarnya, jadi pergantian jam musim panas ikut benar.
 */
object QuotaClock {
    private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Tanggal kuota (yyyy-MM-dd, waktu Pasifik) untuk waktu [ms]. */
    fun dayKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate().toString()

    /** Waktu (ms epoch) reset kuota harian berikutnya sesudah [ms]. */
    fun nextResetMs(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
}

/** Pemakaian satu model pada satu hari kuota. */
data class ModelUsage(
    val model: String,
    val ok: Int,
    val limited: Int,
    val failed: Int,
    val inTokens: Long,
    val outTokens: Long,
    /** Batas permintaan per hari dari Google (dipelajari dari galat kuota terakhir), atau null bila belum pernah terlihat. */
    val dailyLimit: Int?,
    /** Kapan terakhir kena batas (ms epoch), 0 = belum. */
    val lastLimitAt: Long
) {
    val tokens: Long get() = inTokens + outTokens

    /** Perkiraan sisa permintaan hari ini; null bila batasnya belum diketahui. */
    val remaining: Int? get() = dailyLimit?.let { (it - ok).coerceAtLeast(0) }
}

/** Ringkasan pemakaian hari ini. */
data class UsageSnapshot(val day: String, val resetAtMs: Long, val models: List<ModelUsage>) {
    val ok: Int get() = models.sumOf { it.ok }
    val limited: Int get() = models.sumOf { it.limited }
    val tokens: Long get() = models.sumOf { it.tokens }
    val isEmpty: Boolean get() = models.isEmpty()
}

/** Tempat menyimpan catatan pemakaian (satu teks JSON). */
interface UsageStore {
    fun load(): String?
    fun save(json: String)
}

class MemoryUsageStore : UsageStore {
    private var v: String? = null

    @Synchronized
    override fun load() = v

    @Synchronized
    override fun save(json: String) {
        v = json
    }
}

/**
 * Buku catatan pemakaian Gemini yang dihitung Nine sendiri (hanya permintaan dari Nine; pemakaian aplikasi lain dengan kunci
 * yang sama tidak terlihat). Hitungan harian dimulai dari nol saat hari kuota (waktu Pasifik) berganti; batas harian yang
 * pernah dilaporkan Google disimpan terus karena jarang berubah. Aman dipanggil dari banyak thread.
 */
class UsageLedger(private val store: UsageStore, private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Counts {
        var ok = 0
        var limited = 0
        var failed = 0
        var inTokens = 0L
        var outTokens = 0L
        var lastLimitAt = 0L
    }

    private var day = ""
    private val counts = LinkedHashMap<String, Counts>()
    private val limits = LinkedHashMap<String, Int>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val v = MiniJson.parseOrNull(store.load() ?: return) ?: return
        day = MiniJson.str(v, "day") ?: ""
        MiniJson.obj(v, "m")?.forEach { (model, c) ->
            val x = Counts()
            x.ok = (MiniJson.num(c, "ok") ?: 0L).toInt().coerceAtLeast(0)
            x.limited = (MiniJson.num(c, "lim") ?: 0L).toInt().coerceAtLeast(0)
            x.failed = (MiniJson.num(c, "fail") ?: 0L).toInt().coerceAtLeast(0)
            x.inTokens = (MiniJson.num(c, "in") ?: 0L).coerceAtLeast(0L)
            x.outTokens = (MiniJson.num(c, "out") ?: 0L).coerceAtLeast(0L)
            x.lastLimitAt = MiniJson.num(c, "last") ?: 0L
            counts[model] = x
        }
        MiniJson.obj(v, "limits")?.forEach { (model, n) ->
            val lim = (n as? Long)?.toInt() ?: (n as? Double)?.toInt()
            if (lim != null && lim > 0) limits[model] = lim
        }
    }

    /** Mulai hari baru bila hari kuota sudah berganti (hitungan dibuang, batas yang dipelajari tetap). */
    private fun rollover(now: Long) {
        val today = QuotaClock.dayKey(now)
        if (day != today) {
            day = today
            counts.clear()
        }
    }

    private fun persist() {
        val m = LinkedHashMap<String, Any?>()
        for ((model, c) in counts) {
            m[model] = mapOf(
                "ok" to c.ok, "lim" to c.limited, "fail" to c.failed,
                "in" to c.inTokens, "out" to c.outTokens, "last" to c.lastLimitAt
            )
        }
        store.save(MiniJson.write(mapOf("day" to day, "m" to m, "limits" to limits)))
    }

    /** Catat hasil satu permintaan ke [model]. Kegagalan jaringan dan pembatalan tidak dihitung (tak sampai ke server). */
    @Synchronized
    fun record(model: String, attempt: Attempt) {
        ensureLoaded()
        val f = attempt.failure
        if (f != null && f.kind == FailKind.NETWORK) return
        val now = clock()
        rollover(now)
        val c = counts.getOrPut(model) { Counts() }
        when {
            f == null -> {
                c.ok++
                attempt.usage?.let {
                    c.inTokens += it.input.coerceAtLeast(0L)
                    c.outTokens += it.output.coerceAtLeast(0L)
                }
            }
            f.kind == FailKind.RATE_LIMIT || f.kind == FailKind.QUOTA_DAILY -> {
                c.limited++
                c.lastLimitAt = now
                f.dailyLimit?.let { limits[model] = it }
            }
            else -> c.failed++
        }
        persist()
    }

    /** Ringkasan hari ini, model urut abjad. */
    @Synchronized
    fun snapshot(): UsageSnapshot {
        ensureLoaded()
        val now = clock()
        val today = QuotaClock.dayKey(now)
        val stale = day != today // hari berganti sejak catatan terakhir: hitungan kemarin tidak ikut tampil
        val names = (counts.keys + limits.keys).toSortedSet()
        val rows = names.mapNotNull { model ->
            val c = if (stale) null else counts[model]
            val lim = limits[model]
            if (c == null && lim == null) return@mapNotNull null
            ModelUsage(
                model, c?.ok ?: 0, c?.limited ?: 0, c?.failed ?: 0,
                c?.inTokens ?: 0L, c?.outTokens ?: 0L, lim, c?.lastLimitAt ?: 0L
            )
        }
        return UsageSnapshot(today, QuotaClock.nextResetMs(now), rows)
    }

    /** Hapus hitungan hari ini (batas yang dipelajari tetap). */
    @Synchronized
    fun resetToday() {
        ensureLoaded()
        counts.clear()
        day = QuotaClock.dayKey(clock())
        persist()
    }
}

package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Teks pemakaian Gemini untuk layar Pengaturan. Murni (tanpa Android) supaya bisa diuji. */
object UsageText {

    /** 1234 -> "1,2 rb" (ID) / "1.2k" (EN); di bawah seribu apa adanya. */
    fun tokens(n: Long, lang: AppLang): String {
        if (n < 1000) return n.toString()
        val v = if (n < 1_000_000) n / 1000.0 else n / 1_000_000.0
        val unit = if (n < 1_000_000) lang.t("rb", "k") else lang.t("jt", "M")
        val s = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
        return (if (lang == AppLang.ID) s.replace('.', ',') else s) + " " + unit
    }

    /** "4 j 12 mnt" / "4 h 12 m"; di bawah semenit "kurang dari 1 mnt". */
    fun remaining(ms: Long, lang: AppLang): String {
        val mins = (ms.coerceAtLeast(0) / 60_000).toInt()
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 -> lang.t("$h j $m mnt", "$h h $m m")
            m > 0 -> lang.t("$m mnt", "$m min")
            else -> lang.t("kurang dari 1 mnt", "under 1 min")
        }
    }

    private fun requests(n: Int) = if (n == 1) "request" else "requests"

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH.mm")

    fun clock(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    /**
     * Ringkasan hari ini: jumlah permintaan Nine per model, perkiraan sisa kuota (hanya bila Google pernah melaporkan batasnya),
     * model yang sedang istirahat, dan kapan kuota direset (jam setempat).
     */
    fun format(
        snap: UsageSnapshot,
        cooling: List<Cooling>,
        lang: AppLang,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val sb = StringBuilder()
        if (snap.isEmpty) {
            sb.append(lang.t("Belum ada permintaan ke Gemini hari ini.", "No requests to Gemini yet today."))
        } else {
            sb.append(lang.t("Hari ini: ${snap.ok} permintaan berhasil", "Today: ${snap.ok} successful ${requests(snap.ok)}"))
            if (snap.tokens > 0) sb.append(" · ").append(tokens(snap.tokens, lang)).append(lang.t(" token", " tokens"))
            if (snap.limited > 0) sb.append(" · ").append(lang.t("${snap.limited}× kena batas", "${snap.limited}× rate-limited"))
            for (m in snap.models) {
                sb.append("\n• ").append(m.model).append(": ")
                sb.append(lang.t("${m.ok} permintaan", "${m.ok} ${requests(m.ok)}"))
                val lim = m.dailyLimit
                if (lim != null) {
                    sb.append(lang.t(" · sisa ±${m.remaining} dari $lim", " · ~${m.remaining} of $lim left"))
                }
                if (m.limited > 0) sb.append(lang.t(" · kena batas ${m.limited}×", " · limited ${m.limited}×"))
                if (m.failed > 0) sb.append(lang.t(" · gagal ${m.failed}×", " · failed ${m.failed}×"))
            }
        }
        for (c in cooling) {
            sb.append("\n").append(lang.t("Istirahat: ", "Resting: ")).append(c.model).append(" · ")
                .append(Failures.describe(c.failure, lang)).append(" · ")
                .append(lang.t("sampai ", "until ")).append(clock(c.until, zone))
        }
        sb.append("\n").append(
            lang.t(
                "Kuota harian direset ${clock(snap.resetAtMs, zone)} (${remaining(snap.resetAtMs - nowMs, lang)} lagi).",
                "Daily quota resets at ${clock(snap.resetAtMs, zone)} (in ${remaining(snap.resetAtMs - nowMs, lang)})."
            )
        )
        return sb.toString()
    }
}

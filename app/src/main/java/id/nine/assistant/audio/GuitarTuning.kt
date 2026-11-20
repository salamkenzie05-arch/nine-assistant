package id.nine.assistant.audio

import id.nine.assistant.core.AppLang
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Penyetelan gitar standar (E A D G B E) dan perhitungan selisih nada dalam cent. Kotlin murni. */
object GuitarTuning {

    /** [number] 1 = senar paling tipis (E tinggi) … 6 = senar paling tebal (E rendah). */
    data class GString(val number: Int, val note: String, val frequency: Double, val midi: Int)

    val STRINGS = listOf(
        GString(1, "E", 329.63, 64),
        GString(2, "B", 246.94, 59),
        GString(3, "G", 196.00, 55),
        GString(4, "D", 146.83, 50),
        GString(5, "A", 110.00, 45),
        GString(6, "E", 82.41, 40)
    )

    fun byNumber(n: Int): GString? = STRINGS.firstOrNull { it.number == n }

    /** Selisih [freq] terhadap [target] dalam cent (+ = terlalu tinggi, − = terlalu rendah). */
    fun cents(freq: Double, target: Double): Double = 1200.0 * ln(freq / target) / ln(2.0)

    fun frequencyOfMidi(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Senar yang paling dekat dengan [freq] (dalam ±[maxCents] cent), atau null. */
    fun nearest(freq: Double, maxCents: Double = 330.0): Pair<GString, Double>? {
        var best: GString? = null
        var bestC = 0.0
        for (s in STRINGS) {
            val c = cents(freq, s.frequency)
            if (best == null || abs(c) < abs(bestC)) {
                best = s
                bestC = c
            }
        }
        return if (best != null && abs(bestC) <= maxCents) best to bestC else null
    }

    /** Dianggap sudah pas bila selisihnya ≤ [IN_TUNE_CENTS]. */
    const val IN_TUNE_CENTS = 6.0

    fun isInTune(cents: Double): Boolean = abs(cents) <= IN_TUNE_CENTS

    /** Nama senar untuk diucapkan, mis. "senar 3 (G)". */
    fun label(s: GString, lang: AppLang): String = lang.t("senar ${s.number} (${s.note})", "string ${s.number} (${s.note})")

    /** Saran singkat untuk satu senar. */
    fun advice(s: GString, cents: Double, lang: AppLang): String = when {
        isInTune(cents) -> lang.t("${label(s, lang)} sudah pas.", "${label(s, lang)} is in tune.")
        cents < 0 && cents > -30 -> lang.t("${label(s, lang)} sedikit rendah, kencangkan sedikit.", "${label(s, lang)} is slightly flat, tighten it a little.")
        cents < 0 -> lang.t("${label(s, lang)} terlalu rendah, kencangkan.", "${label(s, lang)} is flat, tighten it.")
        cents < 30 -> lang.t("${label(s, lang)} sedikit tinggi, kendurkan sedikit.", "${label(s, lang)} is slightly sharp, loosen it a little.")
        else -> lang.t("${label(s, lang)} terlalu tinggi, kendurkan.", "${label(s, lang)} is sharp, loosen it.")
    }
}

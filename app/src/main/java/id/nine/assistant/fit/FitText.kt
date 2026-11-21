package id.nine.assistant.fit

import id.nine.assistant.nlu.NumberWords

/** Penormalan teks untuk pengurai latihan/makanan: huruf kecil, koma desimal jadi titik, tanda baca dibuang. */
object FitText {
    private val DECIMAL_COMMA = Regex("""(?<=\d),(?=\d{1,2}(?!\d))""")
    private val NON_TEXT = Regex("""[^a-z0-9.\s]""")
    private val STRAY_DOT = Regex("""(?<!\d)\.|\.(?!\d)""")
    private val SPACES = Regex("""\s+""")
    private val NUM_UNIT = Regex("""(\d)(?=[a-z])""")

    fun norm(raw: String): String {
        var t = raw.lowercase().replace('×', 'x').replace('✕', 'x')
        t = DECIMAL_COMMA.replace(t, ".")
        // "1 000" / "1.000" tidak dipakai di sini; angka dipertahankan apa adanya.
        t = NON_TEXT.replace(t, " ")
        t = STRAY_DOT.replace(t, " ")
        t = SPACES.replace(t, " ").trim()
        t = NumberWords.normalize(t)
        // "60kg" -> "60 kg", tetapi "3x8" dan "60x10" dibiarkan.
        t = t.replace(Regex("""(\d)(kg|kilo|lbs|lb|menit|min|mnt|detik|km|set|sets|rep|reps|kali|jam|ml|gram|gr|g|kal|kkal|kcal|cm|tahun|thn)\b"""), "$1 $2")
        return SPACES.replace(t, " ").trim()
    }

    fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Format angka ringkas: 70.0 -> "70", 70.5 -> "70,5" (ID) atau "70.5" (EN). */
    fun fmt(d: Double, l: id.nine.assistant.core.AppLang, digits: Int = 1): String {
        val r = Math.round(d * Math.pow(10.0, digits.toDouble())) / Math.pow(10.0, digits.toDouble())
        val s = if (r == Math.floor(r)) r.toLong().toString() else String.format(java.util.Locale.US, "%.${digits}f", r).trimEnd('0').trimEnd('.')
        return if (l == id.nine.assistant.core.AppLang.ID) s.replace('.', ',') else s
    }
}

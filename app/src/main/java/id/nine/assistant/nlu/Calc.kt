package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Kalkulator Nine: Kotlin murni (tanpa Android) supaya bisa diuji penuh.
 *
 * Memahami ekspresi simbolik ("(12+8)*3^2"), kalimat Indonesia/Inggris ("dua puluh lima dikali empat ditambah sepuluh",
 * "15 persen dari 200 ribu", "akar 144", "10 faktorial"), fungsi (sin cos tan log ln sqrt fact avg ...),
 * persamaan satu variabel ("2x + 3 = 11", "x^2 - 5x + 6 = 0") dan konversi satuan ("5 km ke meter", "100 celsius ke fahrenheit").
 *
 * Semua dihitung dengan Double; hasil dibulatkan ke 15 angka penting. Bilangan bulat di atas 2^53 ditampilkan
 * sebagai perkiraan (bukan digit palsu).
 */
object Calc {

    /** [expression] = ekspresi yang dihitung (untuk ditampilkan), [answer] = jawaban terformat. [error] true bila jawaban berupa penjelasan galat. */
    data class Result(val expression: String, val answer: String, val error: Boolean = false)

    private enum class Kind { SYNTAX, DIV_ZERO, DOMAIN, TOO_BIG }

    private class CalcError(val kind: Kind) : RuntimeException() {
        // tanpa jejak tumpukan: galat dipakai sebagai alur biasa (mis. memindai akar persamaan) dan harus murah
        override fun fillInStackTrace(): Throwable = this
    }

    /** Tanda desimal internal agar "3.456" hasil kata tidak tertukar dengan pemisah ribuan. */
    private const val DEC = '٫'

    // ------------------------------------------------------------------ API publik

    /** Hitung [input] bila itu perhitungan; null bila bukan (biarkan parser lain mengurusnya). */
    fun solve(input: String, lang: AppLang = AppLang.ID): Result? {
        return try {
            solveInternal(input, lang)
        } catch (e: CalcError) {
            null
        } catch (e: StackOverflowError) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    fun isMath(input: String): Boolean = solve(input, AppLang.ID) != null

    /** Ubah angka yang diucapkan ("dua ratus lima puluh ribu", "twenty five") menjadi digit di dalam [s]. */
    fun wordsToDigits(s: String): String {
        val toks = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>(toks.size)
        var i = 0
        while (i < toks.size) {
            val r = readWords(toks, i)
            if (r != null) {
                out += r.text
                i += r.used
            } else {
                out += toks[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    /** Format angka untuk ditampilkan: pemisah ribuan sesuai bahasa, maksimum 15 angka penting. */
    fun format(v: Double, lang: AppLang): String {
        if (v.isNaN() || v.isInfinite()) throw CalcError(Kind.TOO_BIG)
        if (v == 0.0) return "0"
        val dec = if (lang == AppLang.ID) ',' else '.'
        val grp = if (lang == AppLang.ID) '.' else ','
        val a = abs(v)
        if (a >= 1e15 || a < 1e-9) {
            val bd = BigDecimal(v).round(MathContext(10, RoundingMode.HALF_UP))
            val unscaled = bd.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val exp10 = bd.precision() - bd.scale() - 1
            val mant = if (unscaled.length > 1) unscaled[0] + dec.toString() + unscaled.substring(1) else unscaled
            val sgn = if (v < 0) "-" else ""
            return "≈ " + sgn + mant + " × 10^" + exp10
        }
        val bd = BigDecimal(v).round(MathContext(15, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = bd.toPlainString()
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val dot = body.indexOf('.')
        val ip = if (dot >= 0) body.substring(0, dot) else body
        val fp = if (dot >= 0) body.substring(dot + 1) else ""
        val sb = StringBuilder()
        for ((n, ch) in ip.withIndex()) {
            if (n > 0 && (ip.length - n) % 3 == 0) sb.append(grp)
            sb.append(ch)
        }
        if (fp.isNotEmpty()) sb.append(dec).append(fp)
        return (if (neg) "-" else "") + sb.toString()
    }

    // ------------------------------------------------------------------ pipa utama

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private class Prep(val text: String, val trigger: Boolean, val solveKw: Boolean, val radians: Boolean)

    private val rePrefix = rx(
        """^(?:(?:tolong|coba|dong|ya|nine|hitung(?:kan|in|lah)?|kalkulasi(?:kan)?|kalkulator|calculate|calc|compute|""" +
            """berapa(?:kah)?(?:\s+sih)?(?:\s+hasil(?:nya)?(?:\s+dari)?)?|hasil(?:nya)?(?:\s+dari)?|what(?:'s|\s+is|\s+are)|how\s+much\s+is|""" +
            """selesaikan|solve|cari(?:kan)?(?:\s+nilai)?|nilai|ubah|konversi(?:kan)?|convert)\b[\s,:]*)+"""
    )
    private val reSolveKw = rx("""\b(?:selesaikan|solve|persamaan|cari(?:kan)?\s+nilai|find\s+x)\b""")
    private val reSuffix = rx(
        """(?:\s*(?:sama\s+dengan\s+berapa|hasilnya\s+(?:berapa|apa)|jawabannya\s+(?:berapa|apa)|berapa(?:kah)?|adalah\s+berapa|""" +
            """equals?(?:\s+what)?|is\s+what|=\s*\?*|\?+|ya|dong|deh|sih|nih))+\s*$"""
    )
    private val reFiller = rx("""\b(?:adalah|itu|tuh|nih|ya|dong|deh|sih|yang|jadi|hasilnya|hasil|kira-kira|sekitar)\b""")

    private fun prep(input: String): Prep? {
        var s = input.lowercase().trim()
        if (s.isEmpty() || s.length > 160) return null
        s = s.replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-').replace('—', '-')
            .replace("√", " sqrt ").replace("π", " pi ").replace("²", "^2").replace("³", "^3").replace("**", "^")
        s = s.replace(Regex("""\b(?:rp|idr)\.?\s*(?=\d)"""), "")
        var radians = false
        if (Regex("""\b(?:radian|radians|rad)\b""").containsMatchIn(s)) {
            radians = true
            s = s.replace(Regex("""\b(?:radian|radians|rad)\b"""), " ")
        }
        // spasi di sekitar simbol agar "(dua" tidak merusak pembacaan kata
        s = s.replace(Regex("""(?<=[a-z])-(?=[a-z])"""), " ")
        s = s.replace(Regex("""([()+*/^%!=])"""), " $1 ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = wordsToDigits(s)
        var trigger = false
        val pm = rePrefix.find(s)
        if (pm != null && pm.value.isNotEmpty()) {
            trigger = true
            s = s.substring(pm.value.length)
        }
        val solveKw = reSolveKw.containsMatchIn(input.lowercase())
        s = reSuffix.replaceFirst(s, "").trim()
        if (s.isEmpty()) return null
        return Prep(s, trigger, solveKw, radians)
    }

    private fun solveInternal(input: String, lang: AppLang): Result? {
        val p = prep(input) ?: return null
        if (!p.trigger && looksLikeNonMath(input.trim().lowercase())) return null
        convert(p.text, lang)?.let { return it }
        var s = symbolic(p.text)
        val hasX = Regex("""(?<![a-z])x(?![a-z])""").containsMatchIn(s)
        val solveMode = s.contains('=') && hasX
        // "5 x 3" = kali, kecuali sedang menyelesaikan persamaan dengan variabel x
        if (!solveMode && !p.solveKw) s = s.replace(Regex("""(?<=[\d)])\s*x\s*(?=[\d(])"""), " * ")
        if (solveMode || (p.solveKw && hasX)) return solveEquation(s, p, lang)
        if (s.contains('=')) return null
        val toks = tokenize(s, lang) ?: return null
        val ps = Parser(toks, Env(!p.radians, null))
        val v = try {
            ps.parseAll()
        } catch (e: CalcError) {
            if (e.kind == Kind.SYNTAX) return null
            return Result(display(s), errorText(e.kind, lang), true)
        }
        if (ps.ops < 1) return null
        if (v.isNaN()) return Result(display(s), errorText(Kind.DOMAIN, lang), true)
        if (v.isInfinite()) return Result(display(s), errorText(Kind.TOO_BIG, lang), true)
        return Result(display(s), format(v, lang))
    }

    /** Tanggal ("12-05-2026", "12/05"), nomor telepon ("0812-3456-789"), dan sejenisnya bukan perhitungan. */
    private fun looksLikeNonMath(t: String): Boolean {
        // "12-05-2026", "0812-3456-7890" bukan hitungan; "2-5" atau "100-25" tetap pengurangan
        if (Regex("""^\d+(?:-\d+)+$""").matches(t)) {
            val groups = t.split('-').size
            if (groups >= 3 || t.count { it.isDigit() } >= 7) return true
        }
        // tanggal "21/11/2026" atau "12/05" (bulan dua digit); "10/4" dan "1/0" tetap pembagian
        if (Regex("""^\d{1,4}/\d{1,4}/\d{1,4}$""").matches(t)) return true
        if (Regex("""^(?:0?[1-9]|[12]\d|3[01])/(?:0[1-9]|1[0-2])$""").matches(t)) return true
        if (Regex("""^\d{1,2}[.:]\d{2}$""").matches(t)) return true
        return false
    }

    private fun errorText(k: Kind, lang: AppLang): String = when (k) {
        Kind.DIV_ZERO -> lang.t("Tidak bisa dibagi nol.", "Can't divide by zero.")
        Kind.DOMAIN -> lang.t("Hasilnya tidak terdefinisi (di luar daerah hitung bilangan real).", "That's undefined for real numbers.")
        Kind.TOO_BIG -> lang.t("Hasilnya terlalu besar untuk saya hitung.", "That result is too large for me to compute.")
        Kind.SYNTAX -> lang.t("Saya belum paham perhitungan itu.", "I couldn't parse that calculation.")
    }

    private fun display(s: String): String =
        s.replace('*', '×').replace('/', '÷').replace(DEC, ',').replace(Regex("\\s+"), " ").replace("sqrt", "√").trim()

    // ------------------------------------------------------------------ kata → simbol

    private val reListFn = rx(
        """\b(rata[-\s]?rata|mean|average|avg|median|jumlahkan|jumlah|sum|terbesar|maksimum|maximum|max|largest|terkecil|minimum|min|smallest|fpb|gcd|kpk|lcm)\b\s*(?:dari\s+|of\s+)?((?:-?\d[\d$DEC.,]*\s*(?:,\s*|dan\s+|and\s+)?){2,})"""
    )

    private fun listFnName(w: String): String {
        val k = w.lowercase().replace(Regex("[-\\s]"), "")
        return when (k) {
            "ratarata", "mean", "average", "avg" -> "avg"
            "median" -> "median"
            "jumlahkan", "jumlah", "sum" -> "sum"
            "terbesar", "maksimum", "maximum", "max", "largest" -> "max"
            "terkecil", "minimum", "min", "smallest" -> "min"
            "fpb", "gcd" -> "gcd"
            else -> "lcm"
        }
    }

    private val reNum = Regex("""-?\d[\d$DEC.,]*""")

    private fun symbolic(input: String): String {
        var s = " $input "
        s = reListFn.replace(s) { m ->
            val nums = reNum.findAll(m.groupValues[2]).map { it.value.trimEnd('.', ',') }.filter { it.isNotEmpty() }.toList()
            if (nums.size < 2) m.value else " " + listFnName(m.groupValues[1]) + "(" + nums.joinToString(", ") + ") "
        }
        // persen & pecahan: "15 persen dari 200" -> (15/100)*200
        s = rx("""(\d[\d$DEC.,]*)\s*(?:persen|percent|%)\s*(?:dari|of)\s+""").replace(s) { " (" + it.groupValues[1] + "/100)* " }
        s = rx("""(\d[\d$DEC.,]*)\s+(?:dari|of)\s+""").replace(s) { m ->
            // hanya untuk pecahan ("0.5 dari 100"); bilangan >= 1 dibiarkan agar tidak salah paham
            val v = m.groupValues[1].replace(DEC, '.').toDoubleOrNull()
            if (v != null && v > 0 && v < 1) " " + m.groupValues[1] + " * " else m.value
        }
        s = rx("""(\d[\d$DEC.,]*|\))\s*(?:pangkat\s+(?:dua|2)|kuadrat|squared)\b""").replace(s) { " " + it.groupValues[1] + "^2 " }
        s = rx("""(\d[\d$DEC.,]*|\))\s*(?:pangkat\s+(?:tiga|3)|kubik|cubed)\b""").replace(s) { " " + it.groupValues[1] + "^3 " }
        s = rx("""(\d[\d$DEC.,]*|\)|x)\s*(?:faktorial|factorial)\b""").replace(s) { " " + it.groupValues[1] + "! " }
        s = rx("""\b(?:faktorial|factorial)(?:\s+(?:dari|of))?\b""").replace(s, " fact ")
        s = rx("""\bakar\s+pangkat\s+(?:tiga|3)(?:\s+dari)?\b|\bcube\s+root(?:\s+of)?\b|\bakar\s+kubik(?:\s+dari)?\b""").replace(s, " cbrt ")
        s = rx("""\bakar(?:\s+kuadrat)?(?:\s+dari)?\b|\bsquare\s+root(?:\s+of)?\b|\bsqrt\s+of\b""").replace(s, " sqrt ")
        s = rx("""\bpangkat\b|\bto\s+the\s+power(?:\s+of)?\b|\braised\s+to(?:\s+the\s+power\s+of)?\b|\bpower\b""").replace(s, " ^ ")
        s = rx("""\bmodulo\b|\bsisa\s+bagi\b|\bmod\b""").replace(s, " mod ")
        s = rx("""\b(?:ditambah(?:kan)?(?:\s+dengan)?|tambah|dijumlahkan(?:\s+dengan)?|plus|added\s+to)\b""").replace(s, " + ")
        s = rx("""\b(?:dikurangi(?:\s+dengan)?|dikurang|dikurangkan|kurang|minus|subtract(?:ed\s+by)?|less)\b""").replace(s, " - ")
        s = rx("""\b(?:dikalikan(?:\s+dengan)?|dikali(?:\s+dengan)?|kali|times|multiplied\s+by|multiply\s+by)\b""").replace(s, " * ")
        s = rx("""\b(?:dibagi(?:\s+dengan|\s+oleh)?|bagi|divided\s+by|divide\s+by|over|per)\b""").replace(s, " / ")
        s = rx("""\bpersen\b|\bpercent\b""").replace(s, " % ")
        s = rx("""\bsama\s+dengan\b|\bequals?\b|\bequal\s+to\b""").replace(s, " = ")
        s = rx("""\b(?:sinus)\b""").replace(s, " sin ")
        s = rx("""\b(?:kosinus|cosinus|cosine)\b""").replace(s, " cos ")
        s = rx("""\b(?:tangen|tangent)\b""").replace(s, " tan ")
        s = rx("""\b(?:logaritma|logarithm)\b""").replace(s, " log ")
        s = rx("""\b(?:mutlak|absolute(?:\s+value)?(?:\s+of)?)\b""").replace(s, " abs ")
        s = reFiller.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------ kata bilangan

    private class WordNum(val text: String, val used: Int)

    private val idUnit = mapOf(
        "nol" to 0, "kosong" to 0, "satu" to 1, "dua" to 2, "tiga" to 3, "empat" to 4,
        "lima" to 5, "enam" to 6, "tujuh" to 7, "delapan" to 8, "sembilan" to 9
    )
    private val enUnit = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val teens = mapOf(
        "sepuluh" to 10, "sebelas" to 11, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val enTens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val bigScales = mapOf(
        "ribu" to 1e3, "juta" to 1e6, "miliar" to 1e9, "milyar" to 1e9, "triliun" to 1e12,
        "thousand" to 1e3, "million" to 1e6, "billion" to 1e9, "trillion" to 1e12
    )
    private val seScales = mapOf("seribu" to 1e3, "sejuta" to 1e6, "semiliar" to 1e9, "semilyar" to 1e9, "setriliun" to 1e12)
    private val fractions = mapOf("setengah" to 0.5, "separuh" to 0.5, "seperempat" to 0.25, "half" to 0.5, "sepertiga" to 1.0 / 3.0)

    private fun wkey(t: String): String = t.lowercase().trim { !it.isLetter() }

    private fun numText(v: Double): String =
        if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else BigDecimal(v).round(MathContext(15)).stripTrailingZeros().toPlainString().replace('.', DEC)

    private fun readWords(toks: List<String>, i: Int): WordNum? {
        // pecahan berdiri sendiri
        val k0 = wkey(toks[i])
        fractions[k0]?.let { f ->
            var used = 1
            var text = numText(f)
            val nx = if (i + 1 < toks.size) wkey(toks[i + 1]) else ""
            if (nx == "dari" || nx == "of") {
                text += " *"
                used = 2
            }
            return WordNum(text, used)
        }
        var total = 0.0
        var cur = 0.0
        var unitPending = 0.0
        // 0 kosong, 1 satuan, 2 puluhan, 3 ratusan, 4 skala, 5 belasan
        var prev = 0
        var lastScale = Double.MAX_VALUE
        var j = i
        loop@ while (j < toks.size) {
            val k = wkey(toks[j])
            if (k.isEmpty()) break
            val u = idUnit[k] ?: enUnit[k]
            when {
                u != null -> {
                    if (prev == 1 || prev == 5) break@loop
                    if (u == 0 && prev != 0) break@loop
                    cur += u
                    unitPending = u.toDouble()
                    prev = 1
                }
                teens.containsKey(k) -> {
                    if (prev != 0 && prev != 3 && prev != 4) break@loop
                    cur += teens.getValue(k)
                    prev = 5
                    unitPending = 0.0
                }
                enTens.containsKey(k) -> {
                    if (prev != 0 && prev != 3 && prev != 4) break@loop
                    cur += enTens.getValue(k)
                    prev = 2
                    unitPending = 0.0
                }
                k == "belas" -> {
                    if (prev != 1 || unitPending < 1.0) break@loop
                    cur += 10.0
                    unitPending = 0.0
                    prev = 5
                }
                k == "puluh" -> {
                    if (prev != 1 || unitPending < 1.0) break@loop
                    cur += unitPending * 9.0
                    unitPending = 0.0
                    prev = 2
                }
                k == "ratus" -> {
                    if (prev == 1 && unitPending >= 1.0) {
                        cur += unitPending * 99.0
                    } else if (prev == 0 || prev == 4) {
                        cur += 100.0
                    } else break@loop
                    unitPending = 0.0
                    prev = 3
                }
                k == "seratus" -> {
                    if (prev != 0 && prev != 4) break@loop
                    cur += 100.0
                    unitPending = 0.0
                    prev = 3
                }
                k == "hundred" -> {
                    if (prev == 3 || prev == 4) break@loop
                    cur = if (cur == 0.0) 100.0 else cur * 100.0
                    unitPending = 0.0
                    prev = 3
                }
                bigScales.containsKey(k) -> {
                    val sc = bigScales.getValue(k)
                    if (cur <= 0.0 || sc >= lastScale) break@loop
                    total += cur * sc
                    cur = 0.0
                    unitPending = 0.0
                    lastScale = sc
                    prev = 4
                }
                seScales.containsKey(k) -> {
                    val sc = seScales.getValue(k)
                    if (cur != 0.0 || sc >= lastScale) break@loop
                    total += sc
                    lastScale = sc
                    prev = 4
                }
                else -> break@loop
            }
            j++
        }
        val used = j - i
        if (used == 0) return null
        var value = total + cur
        var consumed = used
        // "tiga koma empat lima" -> 3,45
        if (j < toks.size && wkey(toks[j]).let { it == "koma" || it == "point" }) {
            val digits = StringBuilder()
            var q = j + 1
            while (q < toks.size) {
                val kk = wkey(toks[q])
                val d = idUnit[kk] ?: enUnit[kk]
                if (d != null) {
                    digits.append(d)
                } else if (toks[q].length == 1 && toks[q][0].isDigit()) {
                    digits.append(toks[q][0])
                } else break
                q++
            }
            if (digits.isNotEmpty()) {
                value += ("0." + digits).toDouble()
                consumed = q - i
            }
        }
        return WordNum(numText(value), consumed)
    }

    // ------------------------------------------------------------------ konversi satuan

    private class U(val cat: String, val f: Double)

    private val units: Map<String, U> = HashMap<String, U>().also { m ->
        fun add(cat: String, f: Double, vararg names: String) {
            for (n in names) m[n] = U(cat, f)
        }
        add("len", 0.001, "mm", "milimeter", "millimeter", "millimeters")
        add("len", 0.01, "cm", "centimeter", "senti", "centimeters", "sentimeter")
        add("len", 1.0, "m", "meter", "meters", "metre", "metres")
        add("len", 1000.0, "km", "kilometer", "kilometers", "kilometre")
        add("len", 0.0254, "inch", "inci", "inchi", "inches")
        add("len", 0.3048, "ft", "kaki", "feet", "foot")
        add("len", 0.9144, "yard", "yd", "yards")
        add("len", 1609.344, "mil", "mile", "miles")
        add("mass", 0.001, "mg", "miligram", "milligram")
        add("mass", 1.0, "g", "gram", "gr", "grams")
        add("mass", 1000.0, "kg", "kilogram", "kilo", "kilograms")
        add("mass", 100000.0, "kwintal", "quintal")
        add("mass", 1000000.0, "ton", "tons", "tonne")
        add("mass", 28.349523125, "oz", "ons", "ounce", "ounces")
        add("mass", 453.59237, "lb", "lbs", "pon", "pound", "pounds")
        add("vol", 1.0, "ml", "mililiter", "milliliter", "cc")
        add("vol", 1000.0, "l", "liter", "litre", "liters", "litres")
        add("vol", 3785.411784, "galon", "gallon", "gallons", "gal")
        add("time", 1.0, "detik", "second", "seconds", "sec", "sekon")
        add("time", 60.0, "menit", "minute", "minutes", "min")
        add("time", 3600.0, "jam", "hour", "hours", "hr", "hrs")
        add("time", 86400.0, "hari", "day", "days")
        add("time", 604800.0, "minggu", "pekan", "week", "weeks")
        add("time", 2629800.0, "bulan", "month", "months")
        add("time", 31557600.0, "tahun", "year", "years")
        add("speed", 1.0 / 3.6, "km/jam", "kmh", "km/h", "kph", "kpj")
        add("speed", 1.0, "m/s", "mps", "m/detik", "m/dtk")
        add("speed", 0.44704, "mph", "mil/jam")
        add("speed", 0.514444444, "knot", "kn", "knots")
        add("area", 1.0, "m2", "m²")
        add("area", 1e6, "km2", "km²")
        add("area", 10000.0, "ha", "hektar", "hectare", "hectares")
        add("area", 4046.8564224, "acre", "acres")
        add("temp", 0.0, "c", "celsius")
        add("temp", 0.0, "f", "fahrenheit")
        add("temp", 0.0, "kelvin")
        add("angle", 1.0, "derajat", "degree", "degrees", "deg")
        add("angle", 180.0 / PI, "radian", "radians")
    }

    private fun tempKind(name: String): Char = when (name) {
        "c", "celsius" -> 'c'
        "f", "fahrenheit" -> 'f'
        else -> 'k'
    }

    private fun toCelsius(v: Double, k: Char): Double = when (k) {
        'c' -> v
        'f' -> (v - 32.0) * 5.0 / 9.0
        else -> v - 273.15
    }

    private fun fromCelsius(c: Double, k: Char): Double = when (k) {
        'c' -> c
        'f' -> c * 9.0 / 5.0 + 32.0
        else -> c + 273.15
    }

    private val reConv = rx(
        """^(-?\d[\d$DEC.,]*)\s*([a-z][a-z0-9²/]*)\s+(?:ke|jadi|menjadi|to|in|into|dalam|=|sama\s+dengan|equals?|equal\s+to|berapa|artinya|setara\s+dengan)\s+(?:berapa\s+)?([a-z][a-z0-9²/]*)$"""
    )

    private fun convert(text: String, lang: AppLang): Result? {
        var s = text.trim()
        s = s.replace(Regex("""°\s*([cfk])\b"""), " $1").replace(Regex("""derajat\s+(celsius|fahrenheit|kelvin|c|f)\b"""), " $1 ")
        s = s.replace(Regex("""([a-z]+)\s+per\s+([a-z]+)"""), "$1/$2")
        s = s.replace(Regex("""([a-z0-9²])\s*/\s*([a-z])"""), "$1/$2").replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("""^(?:ubah|konversi(?:kan)?|convert|berapa)\s+"""), "")
        val m = reConv.find(s) ?: return null
        val numS = m.groupValues[1]
        val a = units[m.groupValues[2]] ?: return null
        val b = units[m.groupValues[3]] ?: return null
        if (a.cat != b.cat || m.groupValues[2] == m.groupValues[3]) return null
        val n = parseNumberLiteral(numS, lang) ?: return null
        val out = if (a.cat == "temp") {
            fromCelsius(toCelsius(n, tempKind(m.groupValues[2])), tempKind(m.groupValues[3]))
        } else n * a.f / b.f
        if (out.isNaN() || out.isInfinite()) return null
        val left = format(n, lang) + " " + m.groupValues[2]
        return Result(left, format(out, lang) + " " + m.groupValues[3])
    }

    // ------------------------------------------------------------------ token

    private sealed class T {
        data class N(val v: Double, val big: Double = 1.0) : T()
        data class Op(val c: Char) : T()
        data class Id(val name: String) : T()
        data object LP : T()
        data object RP : T()
        data object Comma : T()
    }

    private val scaleWords = mapOf(
        "ribu" to 1e3, "rb" to 1e3, "juta" to 1e6, "jt" to 1e6, "miliar" to 1e9, "milyar" to 1e9, "triliun" to 1e12,
        "ratus" to 100.0, "thousand" to 1e3, "million" to 1e6, "billion" to 1e9, "trillion" to 1e12
    )

    private val funcs = setOf(
        "sqrt", "cbrt", "abs", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh",
        "ln", "log", "log2", "log10", "logb", "exp", "floor", "ceil", "round", "trunc", "fact", "sign", "deg", "rad",
        "pow", "gcd", "lcm", "ncr", "npr", "avg", "mean", "sum", "min", "max", "median", "hypot"
    )

    private fun parseNumberLiteral(raw0: String, lang: AppLang): Double? {
        var raw = raw0
        val neg = raw.startsWith("-")
        raw = raw.removePrefix("-")
        if (raw.isEmpty()) return null
        val v: Double? = if (raw.contains(DEC)) {
            val idx = raw.lastIndexOf(DEC)
            val ip = raw.substring(0, idx).filter { it.isDigit() }
            val fp = raw.substring(idx + 1).filter { it.isDigit() }
            "${ip.ifEmpty { "0" }}.${fp.ifEmpty { "0" }}".toDoubleOrNull()
        } else {
            val dots = raw.count { it == '.' }
            val commas = raw.count { it == ',' }
            fun groupsOk(t: String, sep: Char): Boolean {
                val parts = t.split(sep)
                if (parts.size < 2 || parts[0].isEmpty() || parts[0].length > 3) return false
                return parts.drop(1).all { it.length == 3 && it.all(Char::isDigit) }
            }
            when {
                dots > 0 && commas > 0 -> {
                    val decChar = if (raw.lastIndexOf('.') > raw.lastIndexOf(',')) '.' else ','
                    val thouChar = if (decChar == '.') ',' else '.'
                    val idx = raw.lastIndexOf(decChar)
                    val ipart = raw.substring(0, idx)
                    if (!groupsOk(ipart, thouChar)) null
                    else (ipart.replace(thouChar.toString(), "") + "." + raw.substring(idx + 1)).toDoubleOrNull()
                }
                dots > 1 -> if (groupsOk(raw, '.')) raw.replace(".", "").toDoubleOrNull() else null
                commas > 1 -> if (groupsOk(raw, ',')) raw.replace(",", "").toDoubleOrNull() else null
                dots == 1 -> {
                    val thousands = lang == AppLang.ID && groupsOk(raw, '.') && !raw.startsWith("0")
                    if (thousands) raw.replace(".", "").toDoubleOrNull() else raw.toDoubleOrNull()
                }
                commas == 1 -> {
                    val thousands = lang == AppLang.EN && groupsOk(raw, ',') && !raw.startsWith("0")
                    if (thousands) raw.replace(",", "").toDoubleOrNull() else raw.replace(',', '.').toDoubleOrNull()
                }
                else -> raw.toDoubleOrNull()
            }
        }
        return v?.let { if (neg) -it else it }
    }

    private fun tokenize(s: String, lang: AppLang): List<T>? {
        val out = ArrayList<T>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit() && (out.isEmpty() || out.last() is T.Op || out.last() is T.LP || out.last() is T.Comma)) -> {
                    var j = i
                    while (j < n && (s[j].isDigit() || s[j] == '.' || s[j] == DEC || (s[j] == ',' && j + 1 < n && s[j + 1].isDigit()))) j++
                    while (j > i + 1 && (s[j - 1] == '.' || s[j - 1] == ',')) j--
                    var v = parseNumberLiteral(s.substring(i, j), lang) ?: return null
                    var big = 1.0
                    var k = j
                    while (true) {
                        var p = k
                        val spaced = p < n && s[p] == ' '
                        while (p < n && s[p] == ' ') p++
                        var q = p
                        while (q < n && s[q].isLetter()) q++
                        if (q == p) break
                        val w = s.substring(p, q)
                        val mult = scaleWords[w] ?: (if (w == "k" && !spaced) 1e3 else null) ?: break
                        // "ratus" hanya bermakna bila diikuti skala atau berdiri sendiri
                        v *= mult
                        if (mult >= 1e3) big = max(big, mult)
                        k = q
                    }
                    out += T.N(v, big)
                    i = k
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && s[j].isLetter() && s[j].code < 128) j++
                    if (j == i) return null
                    var name = s.substring(i, j)
                    if (name == "log" && s.startsWith("10", j) && !(j + 2 < n && s[j + 2].isDigit())) {
                        name = "log10"
                        j += 2
                    } else if (name == "log" && s.startsWith("2", j) && !(j + 1 < n && s[j + 1].isDigit())) {
                        name = "log2"
                        j += 1
                    }
                    out += T.Id(name)
                    i = j
                }
                c == '(' -> { out += T.LP; i++ }
                c == ')' -> { out += T.RP; i++ }
                c == ',' -> { out += T.Comma; i++ }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == '%' || c == '!' -> { out += T.Op(c); i++ }
                else -> return null
            }
        }
        // "2 juta 500 ribu" -> 2.500.000
        val merged = ArrayList<T>()
        for (t in out) {
            val last = merged.lastOrNull()
            if (t is T.N && last is T.N && last.big >= 1e3 && t.big >= 1e3 && t.big < last.big) {
                merged[merged.size - 1] = T.N(last.v + t.v, last.big)
            } else merged += t
        }
        for (t in merged) if (t is T.Id && t.name !in funcs && t.name !in consts && t.name != "mod" && t.name != "x") return null
        return merged
    }

    private val consts = mapOf("pi" to PI, "e" to Math.E, "tau" to 2 * PI)

    // ------------------------------------------------------------------ parser

    private class Env(val deg: Boolean, val x: Double?)

    private class V(val v: Double, val pct: Boolean = false)

    private class Parser(val toks: List<T>, val env: Env) {
        var p = 0
        var ops = 0

        fun peek(): T? = toks.getOrNull(p)

        fun parseAll(): Double {
            val r = expr()
            if (p != toks.size) throw CalcError(Kind.SYNTAX)
            return r.v
        }

        fun expr(): V {
            var left = term()
            while (true) {
                val t = peek()
                if (t is T.Op && (t.c == '+' || t.c == '-')) {
                    p++
                    val right = term()
                    ops++
                    val sgn = if (t.c == '+') 1.0 else -1.0
                    left = if (right.pct) V(left.v * (1.0 + sgn * right.v)) else V(left.v + sgn * right.v)
                } else break
            }
            return left
        }

        fun term(): V {
            var left = unary()
            while (true) {
                val t = peek()
                if (t is T.Op && (t.c == '*' || t.c == '/')) {
                    p++
                    val right = unary()
                    ops++
                    if (t.c == '/') {
                        if (right.v == 0.0) throw CalcError(Kind.DIV_ZERO)
                        left = V(left.v / right.v)
                    } else left = V(left.v * right.v)
                } else if (t is T.Id && t.name == "mod") {
                    p++
                    val right = unary()
                    ops++
                    if (right.v == 0.0) throw CalcError(Kind.DIV_ZERO)
                    val r = left.v % right.v
                    left = V(if (r != 0.0 && (r < 0) != (right.v < 0)) r + right.v else r)
                } else if (t is T.LP || (t is T.Id && t.name != "mod") || (t is T.N && toks.getOrNull(p - 1) is T.RP)) {
                    // perkalian tersirat: 2(3+4), 2pi, 3 sin 30, (1+2)(3+4)
                    val right = unary()
                    ops++
                    left = V(left.v * right.v)
                } else break
            }
            return left
        }

        fun unary(): V {
            val t = peek()
            if (t is T.Op && t.c == '-') {
                p++
                return V(-unary().v)
            }
            if (t is T.Op && t.c == '+') {
                p++
                return unary()
            }
            return power()
        }

        fun power(): V {
            val base = postfix()
            val t = peek()
            if (t is T.Op && t.c == '^') {
                p++
                val e = unary()
                ops++
                val r = base.v.pow(e.v)
                if (r.isNaN()) throw CalcError(Kind.DOMAIN)
                if (r.isInfinite()) throw CalcError(if (base.v == 0.0) Kind.DIV_ZERO else Kind.TOO_BIG)
                return V(r)
            }
            return base
        }

        fun postfix(): V {
            var v = primary()
            while (true) {
                val t = peek()
                if (t is T.Op && t.c == '!') {
                    p++
                    ops++
                    v = V(factorial(v.v))
                } else if (t is T.Op && t.c == '%') {
                    p++
                    ops++
                    v = V(v.v / 100.0, pct = true)
                } else break
            }
            return v
        }

        fun primary(): V {
            val t = peek() ?: throw CalcError(Kind.SYNTAX)
            when (t) {
                is T.N -> {
                    p++
                    return V(t.v)
                }
                is T.LP -> {
                    p++
                    val r = expr()
                    if (peek() is T.RP) p++ // kurung penutup yang hilang di akhir ucapan dimaafkan
                    return V(r.v)
                }
                is T.Id -> {
                    p++
                    val name = t.name
                    if (name == "x") return V(env.x ?: throw CalcError(Kind.SYNTAX))
                    if (name !in funcs) {
                        val c = consts[name] ?: throw CalcError(Kind.SYNTAX)
                        return V(c)
                    }
                    val args = ArrayList<Double>()
                    if (peek() is T.LP) {
                        p++
                        if (peek() is T.RP) {
                            p++
                        } else {
                            while (true) {
                                args += expr().v
                                val nx = peek()
                                if (nx is T.Comma) {
                                    p++
                                    continue
                                }
                                if (nx is T.RP) p++
                                break
                            }
                        }
                    } else {
                        args += unary().v
                    }
                    ops++
                    return V(call(name, args, env))
                }
                else -> throw CalcError(Kind.SYNTAX)
            }
        }
    }

    private fun factorial(v: Double): Double {
        if (v < 0 || abs(v - Math.rint(v)) > 1e-9) throw CalcError(Kind.DOMAIN)
        val n = Math.rint(v).toInt()
        if (n > 170) throw CalcError(Kind.TOO_BIG)
        var r = 1.0
        for (i in 2..n) r *= i
        return r
    }

    private fun isInt(v: Double) = abs(v - Math.rint(v)) < 1e-9

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun call(name: String, a: List<Double>, env: Env): Double {
        fun need(n: Int) {
            if (a.size != n) throw CalcError(Kind.SYNTAX)
        }
        fun ang(v: Double) = if (env.deg) Math.toRadians(v) else v
        fun back(v: Double) = if (env.deg) Math.toDegrees(v) else v
        fun tidy(v: Double) = if (abs(v) < 1e-12) 0.0 else v
        fun some() {
            if (a.isEmpty()) throw CalcError(Kind.SYNTAX)
        }
        val r: Double = when (name) {
            "sqrt" -> { need(1); if (a[0] < 0) throw CalcError(Kind.DOMAIN); sqrt(a[0]) }
            "cbrt" -> { need(1); Math.cbrt(a[0]) }
            "abs" -> { need(1); abs(a[0]) }
            "sin" -> { need(1); tidy(sin(ang(a[0]))) }
            "cos" -> { need(1); tidy(cos(ang(a[0]))) }
            "tan" -> {
                need(1)
                if (abs(cos(ang(a[0]))) < 1e-12) throw CalcError(Kind.DOMAIN)
                tidy(tan(ang(a[0])))
            }
            "asin" -> { need(1); if (a[0] < -1 || a[0] > 1) throw CalcError(Kind.DOMAIN); back(asin(a[0])) }
            "acos" -> { need(1); if (a[0] < -1 || a[0] > 1) throw CalcError(Kind.DOMAIN); back(acos(a[0])) }
            "atan" -> { need(1); back(atan(a[0])) }
            "atan2" -> { need(2); back(atan2(a[0], a[1])) }
            "sinh" -> { need(1); sinh(a[0]) }
            "cosh" -> { need(1); cosh(a[0]) }
            "tanh" -> { need(1); tanh(a[0]) }
            "ln" -> { need(1); if (a[0] <= 0) throw CalcError(Kind.DOMAIN); ln(a[0]) }
            "log", "log10" -> { need(1); if (a[0] <= 0) throw CalcError(Kind.DOMAIN); log10(a[0]) }
            "log2" -> { need(1); if (a[0] <= 0) throw CalcError(Kind.DOMAIN); ln(a[0]) / ln(2.0) }
            "logb" -> {
                need(2)
                if (a[0] <= 0 || a[1] <= 0 || a[1] == 1.0) throw CalcError(Kind.DOMAIN)
                ln(a[0]) / ln(a[1])
            }
            "exp" -> { need(1); exp(a[0]) }
            "floor" -> { need(1); floor(a[0]) }
            "ceil" -> { need(1); ceil(a[0]) }
            "trunc" -> { need(1); if (a[0] < 0) ceil(a[0]) else floor(a[0]) }
            "round" -> {
                if (a.size == 1) floor(a[0] + 0.5)
                else { need(2); val f = 10.0.pow(a[1]); floor(a[0] * f + 0.5) / f }
            }
            "fact" -> { need(1); factorial(a[0]) }
            "sign" -> { need(1); sign(a[0]) }
            "deg" -> { need(1); Math.toDegrees(a[0]) }
            "rad" -> { need(1); Math.toRadians(a[0]) }
            "pow" -> { need(2); a[0].pow(a[1]) }
            "hypot" -> { need(2); hypot(a[0], a[1]) }
            "gcd" -> {
                some()
                if (!a.all { isInt(it) }) throw CalcError(Kind.DOMAIN)
                a.map { Math.rint(it).toLong() }.reduce { x, y -> gcd(x, y) }.toDouble()
            }
            "lcm" -> {
                some()
                if (!a.all { isInt(it) && it != 0.0 }) throw CalcError(Kind.DOMAIN)
                var l = abs(Math.rint(a[0]).toLong())
                for (v in a.drop(1)) {
                    val b = abs(Math.rint(v).toLong())
                    val g = gcd(l, b)
                    val nl = (l / g).toDouble() * b.toDouble()
                    if (nl > 9e15) throw CalcError(Kind.TOO_BIG)
                    l = nl.toLong()
                }
                l.toDouble()
            }
            "ncr", "npr" -> {
                need(2)
                if (!isInt(a[0]) || !isInt(a[1]) || a[1] < 0 || a[0] < a[1]) throw CalcError(Kind.DOMAIN)
                val n = Math.rint(a[0]).toInt()
                val k = Math.rint(a[1]).toInt()
                if (n > 1000) throw CalcError(Kind.TOO_BIG)
                var res = 1.0
                if (name == "npr") {
                    for (i in 0 until k) res *= (n - i)
                } else {
                    val kk = min(k, n - k)
                    for (i in 1..kk) res = res * (n - kk + i) / i
                }
                Math.rint(res)
            }
            "avg", "mean" -> { some(); a.sum() / a.size }
            "sum" -> { some(); a.sum() }
            "min" -> { some(); a.min() }
            "max" -> { some(); a.max() }
            "median" -> {
                some()
                val s = a.sorted()
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            }
            else -> throw CalcError(Kind.SYNTAX)
        }
        if (r.isNaN()) throw CalcError(Kind.DOMAIN)
        if (r.isInfinite()) throw CalcError(Kind.TOO_BIG)
        return r
    }

    // ------------------------------------------------------------------ persamaan

    private fun solveEquation(s: String, p: Prep, lang: AppLang): Result? {
        val sides = s.split('=')
        if (sides.size != 2 || sides[0].isBlank() || sides[1].isBlank()) return null
        val lt = tokenize(sides[0], lang) ?: return null
        val rt = tokenize(sides[1], lang) ?: return null
        val hasX = (lt + rt).any { it is T.Id && it.name == "x" }
        if (!hasX) return null
        val deg = !p.radians
        // f(x) = kiri - kanan
        fun f(x: Double): Double {
            return try {
                val a = Parser(lt, Env(deg, x)).parseAll()
                val b = Parser(rt, Env(deg, x)).parseAll()
                a - b
            } catch (e: CalcError) {
                Double.NaN
            }
        }
        // uji sintaks sekali; bila selalu galat sintaks, bukan persamaan
        try {
            Parser(lt, Env(deg, 1.0)).parseAll()
            Parser(rt, Env(deg, 1.0)).parseAll()
        } catch (e: CalcError) {
            if (e.kind == Kind.SYNTAX) return null
        }
        val xs = ArrayList<Double>()
        run {
            var k = 0
            while (k <= 20000) {
                xs += -100.0 + k * 0.01
                k++
            }
            var g = 100.0
            while (g < 1e7) {
                g *= 1.02
                xs += g
                xs.add(0, -g)
            }
        }
        val ys = DoubleArray(xs.size) { f(xs[it]) }
        val roots = ArrayList<Double>()
        var allZero = true
        for (i in xs.indices) {
            val y = ys[i]
            if (!y.isNaN() && abs(y) > 1e-12) allZero = false
            if (y.isNaN()) continue
            if (y == 0.0) {
                roots += xs[i]
                continue
            }
            if (i + 1 < xs.size) {
                val y2 = ys[i + 1]
                if (!y2.isNaN() && y * y2 < 0) {
                    var lo = xs[i]
                    var hi = xs[i + 1]
                    var flo = y
                    for (iter in 0 until 100) {
                        val mid = (lo + hi) / 2
                        val fm = f(mid)
                        if (fm.isNaN()) break
                        if (fm == 0.0) {
                            lo = mid
                            hi = mid
                            break
                        }
                        if (flo * fm < 0) hi = mid else {
                            lo = mid
                            flo = fm
                        }
                    }
                    val r = (lo + hi) / 2
                    // buang lompatan (mis. 1/x) yang bukan akar sungguhan
                    if (abs(f(r)) < 1e-6 * (1 + abs(y) + abs(y2))) roots += r
                }
            }
        }
        // akar kembar: f menyentuh sumbu tanpa berganti tanda, mis. (x-2)^2 = 0. Cari titik balik f yang nilainya ~0.
        var tried = 0
        for (i in 1 until xs.size - 1) {
            if (tried >= 40) break
            val ya = ys[i - 1]
            val y = ys[i]
            val yb = ys[i + 1]
            if (ya.isNaN() || y.isNaN() || yb.isNaN()) continue
            val isTurn = (y - ya) * (yb - y) < 0
            if (!isTurn || ya * yb < 0) continue
            if (abs(y) >= 1e-2 * (1.0 + abs(ya) + abs(yb))) continue
            tried++
            var lo = xs[i - 1]
            var hi = xs[i + 1]
            for (iter in 0 until 80) {
                val m1 = lo + (hi - lo) / 3
                val m2 = hi - (hi - lo) / 3
                val f1 = abs(f(m1))
                val f2 = abs(f(m2))
                if (f1.isNaN() || f2.isNaN()) break
                if (f1 < f2) hi = m2 else lo = m1
            }
            val r = (lo + hi) / 2
            if (abs(f(r)) < 1e-9) roots += r
        }
        val shown = display(s)
        if (allZero) return Result(shown, lang.t("berlaku untuk semua x", "true for every x"))
        val uniq = ArrayList<Double>()
        for (r in roots.sorted()) {
            val rr = if (abs(r - Math.rint(r)) < 1e-8) Math.rint(r) else BigDecimal(r).round(MathContext(10, RoundingMode.HALF_UP)).toDouble()
            if (uniq.none { abs(it - rr) < 1e-6 * max(1.0, abs(rr)) }) uniq += rr
        }
        if (uniq.isEmpty()) return Result(shown, lang.t("tidak ada solusi bilangan real", "no real solution"))
        val or = lang.t(" atau ", " or ")
        return Result(shown, uniq.take(6).joinToString(or) { "x = " + format(it, lang) })
    }
}

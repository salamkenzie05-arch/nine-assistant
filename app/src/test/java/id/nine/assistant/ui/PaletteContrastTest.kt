package id.nine.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Penjaga palet warna: membaca colors.xml terang dan gelap yang sebenarnya dipakai aplikasi dan memastikan pasangan
 * teks/latar yang dipakai di layar memenuhi rasio kontras WCAG (teks biasa 4,5:1, teks gelap di atas lime 7:1).
 * Jadi warna playful yang terlalu pucat tidak lolos tanpa disadari.
 */
class PaletteContrastTest {

    private fun resFile(path: String): File {
        val a = File("src/main/res/$path")
        return if (a.exists()) a else File("app/src/main/res/$path")
    }

    private fun parse(path: String): Map<String, Int> {
        val text = resFile(path).readText()
        return Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{6,8})</color>""").findAll(text).associate {
            val hex = it.groupValues[2]
            it.groupValues[1] to (hex.takeLast(6).toInt(16))
        }
    }

    private val light: Map<String, Int> by lazy { parse("values/colors.xml") }
    private val night: Map<String, Int> by lazy { light + parse("values-night/colors.xml") }

    private fun lum(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val x = lum(a)
        val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun check(palette: Map<String, Int>, mode: String, fg: String, bg: String, min: Double) {
        val f = palette[fg] ?: error("$mode: warna $fg tidak ada")
        val b = palette[bg] ?: error("$mode: warna $bg tidak ada")
        val r = ratio(f, b)
        assertTrue("$mode: $fg di atas $bg kontrasnya %.2f, minimal $min".format(r), r >= min)
    }

    private fun both(fg: String, bg: String, min: Double = 4.5) {
        check(light, "terang", fg, bg, min)
        check(night, "gelap", fg, bg, min)
    }

    @Test
    fun teksUtamaDanRedupTerbacaDiSemuaPermukaan() {
        for (bg in listOf("nine_bg", "nine_card", "nine_pill")) both("nine_on_bg", bg, 7.0)
        for (bg in listOf("nine_bg", "nine_card", "nine_pill")) both("nine_muted", bg)
    }

    @Test
    fun kontrolTealTerbacaDanTombolIsiTerbaca() {
        both("nine_primary", "nine_bg")
        both("nine_primary", "nine_card")
        both("nine_on_primary", "nine_primary")
    }

    @Test
    fun aksenOranyeTerbacaSebagaiTeksDanIkon() {
        for (bg in listOf("nine_bg", "nine_card", "nine_pill", "nine_pastel_orange")) both("nine_orange", bg)
    }

    @Test
    fun teksGelapDiAtasLimeSangatJelas() {
        both("nine_on_lime", "nine_lime", 7.0)
    }

    @Test
    fun angkaKeuanganTerbacaDiKartu() {
        both("fin_income", "nine_card")
        both("fin_expense", "nine_card")
    }

    @Test
    fun batangGrafikTampakDiKartu() {
        both("fin_income_bar", "nine_card", 2.2)
        both("fin_expense_bar", "nine_card", 2.2)
        for (i in 1..5) both("cat_$i", "nine_card", 1.9)
    }

    @Test
    fun malamTetapHitamUntukHematBaterai() {
        assertTrue(night["nine_bg"] == 0x000000)
    }
}

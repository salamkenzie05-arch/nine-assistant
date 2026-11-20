package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tes kalkulator Nine (Kotlin murni). Format ID: titik = ribuan, koma = desimal. */
class CalcTest {

    private fun ans(s: String, lang: AppLang = AppLang.ID): String {
        val r = Calc.solve(s, lang)
        assertNotNull("tidak dihitung: \"$s\"", r)
        assertFalse("galat untuk \"$s\": ${r!!.answer}", r.error)
        return r.answer
    }

    @Test
    fun plainArithmeticAndPrecedence() {
        assertEquals("14", ans("2+3*4"))
        assertEquals("180", ans("(12+8)*3^2"))
        assertEquals("2,5", ans("10/4"))
        assertEquals("7", ans("15 - 8"))
        assertEquals("1.000.000", ans("1000*1000"))
        assertEquals("-3", ans("2-5"))
    }

    @Test
    fun wordsAndPercent() {
        assertEquals("36", ans("berapa 15% dari 240"))
        assertEquals("36", ans("15 persen dari 240"))
        assertEquals("110", ans("dua puluh lima dikali empat ditambah sepuluh"))
        assertEquals("12", ans("akar 144"))
        assertEquals("3.628.800", ans("10 faktorial"))
    }

    @Test
    fun englishPhrases() {
        assertEquals("36", ans("what is 15% of 240", AppLang.EN))
        assertEquals("2.5", ans("10/4", AppLang.EN))
        assertEquals("1,000,000", ans("1000*1000", AppLang.EN))
    }

    @Test
    fun trigonometryUsesDegreesByDefault() {
        assertEquals("0,5", ans("sin(30)"))
        assertEquals("1", ans("cos(0)"))
    }

    @Test
    fun equations() {
        assertEquals("x = 4", ans("2x + 3 = 11"))
        val q = ans("x^2 - 5x + 6 = 0")
        assertTrue(q, q.contains("x = 2") && q.contains("x = 3"))
    }

    @Test
    fun unitConversion() {
        assertTrue(ans("5 km ke meter").startsWith("5.000"))
        assertTrue(ans("100 celsius ke fahrenheit").startsWith("212"))
    }

    @Test
    fun errorsAreExplainedNotCrashed() {
        val r = Calc.solve("1/0", AppLang.ID)
        assertNotNull(r)
        assertTrue(r!!.error)
    }

    @Test
    fun nonMathIsLeftAlone() {
        assertNull(Calc.solve("halo apa kabar", AppLang.ID))
        assertNull(Calc.solve("buka youtube", AppLang.ID))
        // tanggal dan nomor telepon bukan hitungan
        assertNull(Calc.solve("21/11/2026", AppLang.ID))
        assertNull(Calc.solve("0812-3456-7890", AppLang.ID))
    }

    @Test
    fun hugeInputsDoNotHang() {
        val t = System.currentTimeMillis()
        Calc.solve("9".repeat(400) + "*" + "9".repeat(400), AppLang.ID)
        Calc.solve("(".repeat(500) + "1" + ")".repeat(500), AppLang.ID)
        Calc.solve("2^2^2^2^2^2", AppLang.ID)
        assertTrue("terlalu lama", System.currentTimeMillis() - t < 3000)
    }
}

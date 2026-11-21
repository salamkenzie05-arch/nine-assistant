package id.nine.assistant.llm

import id.nine.assistant.core.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ExpressionTagTest {

    @Test
    fun parsesLeadingTagAndStripsItFromText() {
        val p = ExpressionTag.parse("[happy] Selamat, Tuan!")
        assertEquals(Mood.HAPPY, p.mood)
        assertEquals("Selamat, Tuan!", p.text)
        assertEquals(Mood.SAD, ExpressionTag.parse("  [Sad]\nMaaf, Tuan.").mood)
        assertEquals("Maaf, Tuan.", ExpressionTag.parse("  [Sad]\nMaaf, Tuan.").text)
    }

    @Test
    fun acceptsIndonesianAndLooseSpellings() {
        assertEquals(Mood.HAPPY, ExpressionTag.parse("[senang] a").mood)
        assertEquals(Mood.SURPRISED, ExpressionTag.parse("[ Terkejut ] a").mood)
        assertEquals(Mood.CURIOUS, ExpressionTag.parse("[mood: curious] a").mood)
        assertEquals(Mood.LAUGH, ExpressionTag.parse("[expression=laugh] a").mood)
        assertEquals(Mood.SLEEPY, ExpressionTag.parse("[ngantuk] a").mood)
    }

    @Test
    fun unknownOrNonLeadingBracketsAreLeftAlone() {
        val a = ExpressionTag.parse("[1] satu dua")
        assertNull(a.mood)
        assertEquals("[1] satu dua", a.text)
        val b = ExpressionTag.parse("[catatan] ini bukan ekspresi")
        assertNull(b.mood)
        assertEquals("[catatan] ini bukan ekspresi", b.text)
        val c = ExpressionTag.parse("Halo [happy] dunia")
        assertNull(c.mood) // hanya tag di awal yang jadi ekspresi
        assertEquals("Halo [happy] dunia", c.text)
        assertNull(ExpressionTag.parse("").mood)
    }

    @Test
    fun partialTagIsHeldBackWhileItMayStillArrive() {
        assertTrue(ExpressionTag.maybePartial("["))
        assertTrue(ExpressionTag.maybePartial(" [ha"))
        assertTrue(ExpressionTag.maybePartial("[expression: surpri"))
        assertFalse(ExpressionTag.maybePartial("[happy] Halo")) // sudah lengkap
        assertFalse(ExpressionTag.maybePartial("Halo ["))
        assertFalse(ExpressionTag.maybePartial("Halo"))
        assertFalse(ExpressionTag.maybePartial("[ini bukan tag karena sudah terlalu panjang untuk tag")) // tidak menahan selamanya
        assertFalse(ExpressionTag.maybePartial(""))
    }

    @Test
    fun visibleHidesPartialAndLeadingTag() {
        assertEquals("", ExpressionTag.visible("[hap"))
        assertEquals("", ExpressionTag.visible("[happy]"))
        assertEquals("Halo", ExpressionTag.visible("[happy] Halo"))
        assertEquals("Halo", ExpressionTag.visible("Halo"))
        assertEquals("Satu  dua", ExpressionTag.visible("[sad] Satu [wink] dua"))
    }

    @Test
    fun stripRemovesKnownTagsAnywhereButKeepsOtherBrackets() {
        assertEquals("a  b", ExpressionTag.strip("a [happy] b"))
        assertEquals("a [1] b [catatan]", ExpressionTag.strip("a [1] b [catatan]"))
        assertEquals("tanpa kurung", ExpressionTag.strip("tanpa kurung"))
    }

    @Test
    fun cleanAlsoRemovesTags() {
        assertEquals("Selamat, Tuan!", TextClean.clean("[happy] Selamat, Tuan!"))
        assertEquals("Selamat, Tuan!", TextClean.speech("[happy] Selamat, Tuan!"))
        assertEquals("Satu dua", TextClean.clean("Satu [wink] dua"))
    }

    @Test
    fun streamedTagIsNeverShownForAnySplit() {
        val full = "[surprised] Wah, ternyata begitu."
        val rnd = Random(7)
        repeat(300) {
            val sb = StringBuilder()
            var i = 0
            while (i < full.length) {
                val n = 1 + rnd.nextInt(6)
                sb.append(full, i, minOf(full.length, i + n))
                i += n
                val shown = ExpressionTag.visible(sb.toString())
                assertFalse("tag bocor: '$shown'", shown.contains("["))
            }
            assertEquals("Wah, ternyata begitu.", ExpressionTag.visible(sb.toString()))
        }
    }

    @Test
    fun guessCoversTheCommonTones() {
        assertEquals(Mood.SAD, ExpressionTag.guess("Maaf, Tuan, saya tidak bisa melakukannya."))
        assertEquals(Mood.HAPPY, ExpressionTag.guess("Selamat atas kelulusannya!"))
        assertEquals(Mood.LAUGH, ExpressionTag.guess("Hahaha, itu lucu sekali."))
        assertEquals(Mood.SURPRISED, ExpressionTag.guess("Wow, ternyata ada lagi."))
        assertEquals(Mood.CONFUSED, ExpressionTag.guess("Maksudnya kurang jelas, bisa diperjelas?"))
        assertEquals(Mood.CURIOUS, ExpressionTag.guess("Mau yang mana, Tuan?"))
        assertEquals(Mood.NEUTRAL, ExpressionTag.guess("Ibu kota Prancis adalah Paris."))
        assertEquals(Mood.SLEEPY, ExpressionTag.guess("Selamat tidur, Tuan."))
    }
}

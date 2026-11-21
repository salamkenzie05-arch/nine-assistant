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
    fun ordinaryBracketsAndNonLeadingTagsAreLeftAlone() {
        val a = ExpressionTag.parse("[1] satu dua")
        assertNull(a.mood)
        assertEquals("[1] satu dua", a.text)
        val b = ExpressionTag.parse("[Catatan] ini kata biasa")
        assertNull(b.mood)
        assertEquals("[Catatan] ini kata biasa", b.text)
        val link = ExpressionTag.parse("[halo](https://contoh.id) tautan")
        assertNull(link.mood)
        assertEquals("[halo](https://contoh.id) tautan", link.text)
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
        assertEquals("Satu dua", ExpressionTag.visible("[sad] Satu [wink] dua"))
    }

    @Test
    fun stripRemovesTagsAnywhereButKeepsOtherBrackets() {
        assertEquals("a b", ExpressionTag.strip("a [happy] b"))
        assertEquals("a [1] b [Catatan] c[i]", ExpressionTag.strip("a [1] b [Catatan] c[i]"))
        assertEquals("tanpa kurung", ExpressionTag.strip("tanpa kurung"))
        assertEquals("Baik, Tuan.", ExpressionTag.strip("Baik [sighs], Tuan."))
        assertEquals("lihat [tautan](https://x.id) ya", ExpressionTag.strip("lihat [tautan](https://x.id) ya"))
    }

    @Test
    fun unknownStyleTagsLikeApologeticAreHiddenToo() {
        // Gemini kadang menulis tag gaya bicara yang tidak kita minta: tidak boleh tampil atau diucapkan.
        val p = ExpressionTag.parse("[apologetic] I must apologise for the sluggish performance, Sir.")
        assertEquals(Mood.SAD, p.mood)
        assertEquals("I must apologise for the sluggish performance, Sir.", p.text)
        val q = ExpressionTag.parse("[whispers] Rahasia ya.")
        assertEquals("Rahasia ya.", q.text)
        assertEquals("Baik.", ExpressionTag.parse("[sighs softly] Baik.").text)
        assertEquals(Mood.SAD, ExpressionTag.parse("[sad, apologetic] Maaf.").mood)
        assertEquals("Halo", ExpressionTag.parse("[zxqvbn] Halo").text) // tag lowercase yang sama sekali asing pun dibuang
        assertEquals("", ExpressionTag.visible("[apologet")) // masih datang sebagian
        assertEquals("Halo", ExpressionTag.visible("[apologetic] Halo"))
        assertEquals("I am sorry.", TextClean.speech("[apologetic] I am [sighs] sorry."))
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

    // ------------------------------------------------------------ tag ingatan

    @Test
    fun memoryTagsAreCollectedAndStripped() {
        val p = ExpressionTag.parse("[happy] [ingat: Nama pengguna Budi] [ingat: Suka nasi goreng] Halo Budi!")
        assertEquals(Mood.HAPPY, p.mood)
        assertEquals(listOf("Nama pengguna Budi", "Suka nasi goreng"), p.facts)
        assertEquals("Halo Budi!", p.text)
    }

    @Test
    fun memoryTagWorksInAnyOrderAndWithoutMoodTag() {
        val a = ExpressionTag.parse("[ingat: Punya kucing bernama Mimi][sad] Wah, Mimi sakit?")
        assertEquals(Mood.SAD, a.mood)
        assertEquals(listOf("Punya kucing bernama Mimi"), a.facts)
        assertEquals("Wah, Mimi sakit?", a.text)
        val b = ExpressionTag.parse("[remember: Lives in Bandung] Nice city.")
        assertNull(b.mood)
        assertEquals(listOf("Lives in Bandung"), b.facts)
        assertEquals("Nice city.", b.text)
    }

    @Test
    fun atMostThreeDistinctFactsAreTaken() {
        val p = ExpressionTag.parse("[ingat: aaa1][ingat: bbb2][ingat: bbb2][ingat: ccc3][ingat: ddd4] x")
        assertEquals(listOf("aaa1", "bbb2", "ccc3"), p.facts)
        assertEquals("x", p.text)
    }

    @Test
    fun partialMemoryTagIsHeldBackUntilItCloses() {
        assertTrue(ExpressionTag.maybePartial("[ing"))
        assertTrue(ExpressionTag.maybePartial("[ingat: Nama pengguna adalah Budi Santoso dan dia tinggal di"))
        assertTrue(ExpressionTag.maybePartial("[happy] [ingat: Nama pengguna adalah Budi Santoso dan dia tinggal di"))
        assertFalse(ExpressionTag.maybePartial("[happy] [ingat: Nama Budi] Halo"))
        assertEquals("", ExpressionTag.visible("[happy] [ingat: Nama pengguna adalah Budi Santoso dan dia tinggal di"))
        assertEquals("Halo Budi", ExpressionTag.visible("[happy] [ingat: Nama Budi] Halo Budi"))
        // bracket panjang yang bukan tag ingatan tidak ditahan selamanya
        assertFalse(ExpressionTag.maybePartial("[Catatan penting dari saya untuk Anda semua tentang ini"))
    }

    @Test
    fun memoryTagsAnywhereAreStrippedFromShownText() {
        assertEquals("Halo Budi", ExpressionTag.strip("Halo [ingat: Nama Budi] Budi"))
        assertEquals("Halo", ExpressionTag.strip("[happy][ingat: Nama Budi]Halo"))
        // bracket biasa tetap
        assertEquals("Lihat [1] ya", ExpressionTag.strip("Lihat [1] ya"))
    }

    @Test
    fun tooShortOrMultilineMemoryTagsAreNotTags() {
        assertTrue(ExpressionTag.parse("[ingat: a] x").facts.isEmpty())
        assertTrue(ExpressionTag.parse("[ingat: baris satu\nbaris dua] x").facts.isEmpty())
    }
}

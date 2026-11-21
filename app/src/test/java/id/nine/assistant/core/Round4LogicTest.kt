package id.nine.assistant.core

import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.FunKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Tes Nine 3.3 putaran 2: aturan ruang obrolan dan kejutan harian (side quest). */
class Round4LogicTest {

    // ------------------------------------------------------------ ruang obrolan

    @Test
    fun namaRuangDibersihkan() {
        assertEquals("Kerja Proyek", SpaceRules.cleanName("  Kerja\u0007   Proyek \n"))
        assertEquals("", SpaceRules.cleanName("   \t "))
        val long = SpaceRules.cleanName("a".repeat(10) + " " + "b".repeat(40))
        assertTrue(long.length <= SpaceRules.NAME_MAX)
        assertFalse(long.endsWith(" "))
    }

    @Test
    fun instruksiRuangSatuParagraf() {
        assertEquals("Jawab singkat. Pakai bahasa baku.", SpaceRules.cleanNote("Jawab singkat.\n\nPakai   bahasa baku. "))
        assertEquals(SpaceRules.NOTE_MAX, SpaceRules.cleanNote("x".repeat(1000)).length)
    }

    @Test
    fun pesanDibatasiSebelumDisimpan() {
        val many = (1..300).map { (it % 2 == 0) to "pesan $it" }
        val kept = SpaceRules.trim(many + listOf(true to "", false to "…", true to "   "))
        assertEquals(SpaceRules.MAX_MESSAGES, kept.size)
        assertEquals("pesan 300", kept.last().second)
        assertTrue(kept.none { it.second.isBlank() || it.second == "…" })
        val big = SpaceRules.trim(listOf(true to "z".repeat(SpaceRules.MAX_MESSAGE_CHARS + 500)))
        assertEquals(SpaceRules.MAX_MESSAGE_CHARS, big.single().second.length)
    }

    @Test
    fun konteksAiUntukRuang() {
        assertEquals("", SpaceRules.contextLine("", "apa saja", AppLang.ID))
        val id = SpaceRules.contextLine("Skripsi", "Bantu saya menulis formal.", AppLang.ID)
        assertTrue(id.contains("Skripsi") && id.contains("Bantu saya menulis formal."))
        val en = SpaceRules.contextLine("Thesis", "", AppLang.EN)
        assertTrue(en.contains("Thesis") && !en.contains("instructions"))
    }

    // ------------------------------------------------------------ kejutan harian

    @Test
    fun idKejutanUnikDanTeksLengkap() {
        val ids = SideQuests.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (q in SideQuests.ALL) {
            assertTrue(q.id, q.teasersId.isNotEmpty() && q.teasersEn.isNotEmpty())
            for (t in q.teasersId + q.teasersEn) assertTrue("${q.id}: $t", t.isNotBlank() && t.contains("{s}"))
        }
    }

    @Test
    fun ajakanTidakMenyisakanPenandaDanMemakaiPanggilan() {
        for (q in SideQuests.ALL) for (seed in -3..3) for (lang in AppLang.values()) {
            val plain = SideQuests.teaser(q, lang, "", seed)
            val withSfx = SideQuests.teaser(q, lang, ", Tuan", seed)
            assertFalse(plain, plain.contains("{s}") || plain.contains(" ,"))
            assertTrue(withSfx, withSfx.contains(", Tuan") && !withSfx.contains("{s}"))
        }
    }

    @Test
    fun pilihanBergantiHarianDanBerhentiSetelahSemuaDitemukan() {
        val d = LocalDate.of(2026, 11, 21)
        val a = SideQuests.pick(emptySet(), d)
        assertNotNull(a)
        assertEquals(a, SideQuests.pick(emptySet(), d)) // stabil dalam satu hari
        val seen = (0L..SideQuests.ALL.size.toLong()).map { SideQuests.pick(emptySet(), d.plusDays(it))!!.id }.toSet()
        assertEquals(SideQuests.ALL.size, seen.size) // berputar ke semua fitur
        val found = SideQuests.ALL.map { it.id }.toSet()
        assertNull(SideQuests.pick(found, d))
        // yang sudah ditemukan tidak ditawarkan lagi
        val partial = setOf("sing", "pat")
        for (i in 0L..30L) assertFalse(SideQuests.pick(partial, d.plusDays(i))!!.id in partial)
    }

    @Test
    fun perintahMenyelesaikanKejutanYangTepat() {
        assertEquals(listOf("sing"), SideQuests.idsFor(Command.Sing(null)))
        assertEquals(listOf("sing"), SideQuests.idsFor(Command.Sing("cantina")))
        assertEquals(listOf("gombal"), SideQuests.idsFor(Command.Entertain(FunKind.GOMBAL)))
        assertEquals(listOf("pantun"), SideQuests.idsFor(Command.Entertain(FunKind.PANTUN)))
        assertEquals(listOf("wisdom"), SideQuests.idsFor(Command.Entertain(FunKind.WISDOM)))
        assertEquals(listOf("calc"), SideQuests.idsFor(Command.Calculate("2+2")))
        assertTrue(SideQuests.idsFor(Command.Tuner(start = false)).isEmpty()) // menutup tuner bukan penemuan
        assertTrue(SideQuests.idsFor(Command.Tuner(start = true)).contains("tuner"))
        assertTrue(SideQuests.idsFor(Command.TodoAdd("beli susu")).contains("todo"))
        assertTrue(SideQuests.idsFor(Command.DeviceInfo).contains("phone"))
        assertTrue(SideQuests.idsFor(Command.Greeting).isEmpty())
        assertTrue(SideQuests.idsFor(Command.Unknown("apa saja")).isEmpty())
        assertFalse(SideQuests.ALL.any { it.id == "pat" && it.offer != null }) // pat lewat gestur, bukan ajakan ya/tidak
    }

    @Test
    fun ajakanYaMenjalankanFiturYangDitawarkan() {
        // Menjawab "ya" menjalankan Quest.offer; fitur itu harus tercatat sebagai penemuan yang sama.
        for (q in SideQuests.ALL) {
            val offer = q.offer ?: continue
            assertTrue("${q.id} -> $offer", SideQuests.matches(q.id, offer))
        }
    }
}

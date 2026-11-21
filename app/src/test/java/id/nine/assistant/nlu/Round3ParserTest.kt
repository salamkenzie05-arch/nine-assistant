package id.nine.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Tes perintah Nine 3.3: hitung, keuangan, Maps, tutup semua app, gombal/pantun/bijak, panggilan, affinity, daftar tugas. */
class Round3ParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }

    private fun p(s: String): Command = parser.parse(s)

    // ------------------------------------------------------------------ hitung

    @Test
    fun calculatorCommands() {
        assertTrue(p("berapa 15% dari 240") is Command.Calculate)
        assertTrue(p("2+3*4") is Command.Calculate)
        assertTrue(p("hitung 12 kali 12") is Command.Calculate)
        assertTrue(p("akar 144") is Command.Calculate)
    }

    @Test
    fun calculatorDoesNotStealOtherCommands() {
        assertFalse(p("jam berapa sekarang") is Command.Calculate)
        assertFalse(p("pasang alarm jam 6") is Command.Calculate)
        assertFalse(p("buka youtube") is Command.Calculate)
        assertFalse(p("telepon 08123456789") is Command.Calculate)
    }

    // ------------------------------------------------------------------ keuangan

    @Test
    fun logExpense() {
        val c = p("tadi beli kopi 18 ribu")
        assertTrue("got $c", c is Command.FinanceAdd)
        c as Command.FinanceAdd
        assertEquals(FinType.EXPENSE, c.type)
        assertEquals(18_000L, c.amount)
        assertEquals("food", c.category)
    }

    @Test
    fun logExpenseVariants() {
        val a = p("catat pengeluaran 50rb bensin") as Command.FinanceAdd
        assertEquals(50_000L, a.amount)
        assertEquals("transport", a.category)
        val b = p("bayar listrik 350.000") as Command.FinanceAdd
        assertEquals(350_000L, b.amount)
        assertEquals("bills", b.category)
        val d = p("belanja bulanan 1,5 juta") as Command.FinanceAdd
        assertEquals(1_500_000L, d.amount)
    }

    @Test
    fun logIncome() {
        val c = p("gajian 5 juta") as Command.FinanceAdd
        assertEquals(FinType.INCOME, c.type)
        assertEquals(5_000_000L, c.amount)
        assertEquals("salary", c.category)
        val d = p("dapat bonus 2 juta 500 ribu") as Command.FinanceAdd
        assertEquals(2_500_000L, d.amount)
        assertEquals(FinType.INCOME, d.type)
    }

    @Test
    fun financeReports() {
        val a = p("pengeluaran bulan ini")
        assertTrue("got $a", a is Command.FinanceReport)
        assertEquals(FinPeriod.MONTH, (a as Command.FinanceReport).period)
        assertEquals(FinType.EXPENSE, a.type)
        val b = p("berapa pemasukan hari ini") as Command.FinanceReport
        assertEquals(FinPeriod.TODAY, b.period)
        assertEquals(FinType.INCOME, b.type)
        val c = p("laporan keuangan minggu ini") as Command.FinanceReport
        assertEquals(FinPeriod.WEEK, c.period)
    }

    @Test
    fun financeListUndoOpenClear() {
        assertTrue(p("daftar pengeluaran hari ini") is Command.FinanceList)
        assertEquals(Command.FinanceUndo, p("hapus pengeluaran terakhir"))
        assertEquals(Command.FinanceOpen, p("buka catatan keuangan"))
        assertEquals(Command.FinanceClear, p("hapus semua catatan keuangan"))
    }

    @Test
    fun moneyTalkThatIsNotALog() {
        for (t in listOf("aku mau beli kopi 18 ribu besok", "berapa harga kopi 18 ribu", "alarm jam 6 pagi", "terima kasih nine")) {
            val c = p(t)
            assertFalse("\"$t\" -> $c", c is Command.FinanceAdd)
        }
    }

    @Test
    fun financeMathSummarizesByPeriod() {
        val zone = ZoneId.of("Asia/Jakarta")
        val day = LocalDate.of(2026, 11, 21)
        fun at(d: LocalDate, h: Int = 10) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val all = listOf(
            Txn(1, at(day), FinType.EXPENSE, 18_000, "food", "Kopi"),
            Txn(2, at(day), FinType.EXPENSE, 50_000, "transport", "Bensin"),
            Txn(3, at(day.minusDays(1)), FinType.INCOME, 5_000_000, "salary", ""),
            Txn(4, at(day.minusMonths(1)), FinType.EXPENSE, 99_000, "bills", "")
        )
        val (f, t) = FinanceMath.range(FinPeriod.TODAY, day, zone)
        val today = FinanceMath.summarize(all, f, t)
        assertEquals(68_000L, today.expense)
        assertEquals(0L, today.income)
        assertEquals("transport", today.expenseByCat.first().first)
        val (mf, mt) = FinanceMath.range(FinPeriod.MONTH, day, zone)
        val month = FinanceMath.summarize(all, mf, mt)
        assertEquals(5_000_000L - 68_000L, month.balance)
        val (af, at) = FinanceMath.range(FinPeriod.ALL, day, zone)
        assertEquals(4, FinanceMath.summarize(all, af, at).count)
    }

    @Test
    fun moneyFormatting() {
        assertEquals("Rp 25.000", FinanceMath.money(25_000, id.nine.assistant.core.AppLang.ID))
        assertEquals("Rp 1,500,000", FinanceMath.money(1_500_000, id.nine.assistant.core.AppLang.EN))
        assertEquals("-Rp 1.000", FinanceMath.money(-1_000, id.nine.assistant.core.AppLang.ID))
    }

    // ------------------------------------------------------------------ Google Maps

    @Test
    fun mapsDestination() {
        assertEquals(Command.Navigate("monas", true), p("navigasi ke monas"))
        val c = p("set google maps ke bandara soekarno hatta") as Command.Navigate
        assertEquals("bandara soekarno hatta", c.destination)
    }

    @Test
    fun mapsModesOriginAndTolls() {
        val a = p("arahkan ke blok m naik motor") as Command.Navigate
        assertEquals("two_wheeler", a.mode)
        assertEquals("blok m", a.destination)
        val b = p("rute dari bogor ke bandung tanpa tol") as Command.Navigate
        assertEquals("bogor", b.origin)
        assertEquals("bandung", b.destination)
        assertTrue(b.avoidTolls)
        val c = p("navigasi ke pasar baru jalan kaki") as Command.Navigate
        assertEquals("walking", c.mode)
    }

    // ------------------------------------------------------------------ tutup semua app

    @Test
    fun closeAllApps() {
        assertEquals(Command.CloseApps(false), p("tutup semua aplikasi"))
        assertEquals(Command.CloseApps(true), p("tutup semua aplikasi termasuk kamu"))
        assertEquals(Command.CloseApps(false), p("matikan semua app yang sedang menyala"))
    }

    @Test
    fun closingOneAppIsNotCloseAll() {
        assertFalse(p("tutup youtube") is Command.CloseApps)
        assertFalse(p("buka semua catatan") is Command.CloseApps)
    }

    // ------------------------------------------------------------------ hiburan & panggilan

    @Test
    fun funCommands() {
        assertEquals(Command.Entertain(FunKind.GOMBAL), p("gombalin aku dong"))
        assertEquals(Command.Entertain(FunKind.PANTUN), p("buatkan pantun"))
        assertEquals(Command.Entertain(FunKind.WISDOM), p("kasih kata kata bijak"))
        assertFalse(p("apa itu pantun") is Command.Entertain)
    }

    @Test
    fun setCallName() {
        assertEquals(Command.SetCallName("Bos"), p("panggil aku bos"))
        assertEquals(Command.SetCallName("Pak Budi"), p("panggil saya pak budi"))
        assertFalse(p("panggil aku nanti jam 5") is Command.SetCallName)
    }

    // ------------------------------------------------------------------ affinity & tugas

    @Test
    fun affinityQuestions() {
        assertEquals(Command.AffinityStatus, p("status affinity"))
        assertEquals(Command.AffinityStatus, p("berapa level kedekatan kita"))
        assertEquals(Command.AffinityStatus, p("progress affinity nine"))
        assertEquals(Command.AffinityStatus, p("level kita apa sekarang"))
        assertEquals(Command.AffinityStatus, p("apa saja level kedekatan"))
        assertEquals(Command.AffinityStatus, p("daftar level"))
        assertEquals(Command.AffinityStatus, p("what level are we"))
    }

    @Test
    fun showTodayTodoList() {
        assertEquals(Command.TodoList, p("tampilkan to do list hari ini"))
        assertEquals(Command.TodoList, p("apa saja tugas hari ini"))
    }

    @Test
    fun assistiveTouchIsGone() {
        val c = p("nyalakan assistive touch")
        assertFalse("got $c", c is Command.AppSetting)
    }
}

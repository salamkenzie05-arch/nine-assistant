package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Tes data grafik rekap keuangan dan persentase kategori. */
class FinanceRecapTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private var nextId = 1L

    private fun tx(date: LocalDate, hour: Int, amount: Long, type: FinType = FinType.EXPENSE) =
        Txn(nextId++, date.atTime(hour, 0).atZone(utc).toInstant().toEpochMilli(), type, amount, "other", "")

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun persenTotalnyaSeratus() {
        assertEquals(listOf(50, 30, 20), FinanceMath.shares(listOf(500L, 300L, 200L)))
        assertEquals(listOf(34, 33, 33), FinanceMath.shares(listOf(1L, 1L, 1L)))
        assertEquals(listOf(0, 100), FinanceMath.shares(listOf(1L, 999L)))
        assertEquals(listOf(100), FinanceMath.shares(listOf(7L)))
        for (v in listOf(listOf(3L, 3L, 3L, 1L), listOf(17L, 5L, 5L, 5L, 1L), listOf(123_456L, 7L, 99L))) {
            assertEquals(100, FinanceMath.shares(v).sum())
        }
    }

    @Test
    fun persenTanpaNilaiPositifSemuaNol() {
        assertEquals(emptyList<Int>(), FinanceMath.shares(emptyList()))
        assertEquals(listOf(0, 0), FinanceMath.shares(listOf(0L, 0L)))
        assertEquals(listOf(0, 100), FinanceMath.shares(listOf(-5L, 40L)))
    }

    @Test
    fun mingguMulaiSeninDanHanyaJenisYangDipilih() {
        val today = d(2026, 11, 21) // Sabtu
        assertEquals(d(2026, 11, 16), FinanceMath.weekStart(today))
        val all = listOf(
            tx(d(2026, 11, 16), 10, 100),
            tx(d(2026, 11, 21), 23, 50),
            tx(d(2026, 11, 23), 9, 999),                 // Senin depan: di luar rentang
            tx(d(2026, 11, 15), 9, 888),                 // Minggu lalu
            tx(d(2026, 11, 16), 11, 777, FinType.INCOME) // jenis lain
        )
        val b = FinanceMath.buckets(all, FinPeriod.WEEK, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min"), b.map { it.label })
        assertEquals(listOf(100L, 0L, 0L, 0L, 0L, 50L, 0L), b.map { it.value })
        val inc = FinanceMath.buckets(all, FinPeriod.WEEK, FinType.INCOME, today, AppLang.EN, utc)
        assertEquals("Mon", inc.first().label)
        assertEquals(777L, inc.first().value)
    }

    @Test
    fun bulanDibagiBlokTujuhHari() {
        val today = d(2026, 11, 21) // November: 30 hari
        val all = listOf(
            tx(d(2026, 11, 1), 8, 1), tx(d(2026, 11, 7), 8, 2), tx(d(2026, 11, 8), 8, 4),
            tx(d(2026, 11, 29), 8, 8), tx(d(2026, 11, 30), 8, 16),
            tx(d(2026, 10, 31), 8, 1000), tx(d(2026, 12, 1), 8, 2000)
        )
        val b = FinanceMath.buckets(all, FinPeriod.MONTH, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(listOf("1", "8", "15", "22", "29"), b.map { it.label })
        assertEquals(listOf(3L, 4L, 0L, 0L, 24L), b.map { it.value })
        // Februari 2027 (28 hari) hanya empat blok.
        val feb = FinanceMath.buckets(emptyList(), FinPeriod.MONTH, FinType.EXPENSE, d(2027, 2, 10), AppLang.ID, utc)
        assertEquals(listOf("1", "8", "15", "22"), feb.map { it.label })
    }

    @Test
    fun hariIniEmpatBlokEnamJam() {
        val today = d(2026, 11, 21)
        val all = listOf(
            tx(today, 0, 1), tx(today, 5, 2), tx(today, 6, 4), tx(today, 12, 8), tx(today, 23, 16),
            tx(d(2026, 11, 20), 7, 1000)
        )
        val b = FinanceMath.buckets(all, FinPeriod.TODAY, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(listOf("00", "06", "12", "18"), b.map { it.label })
        assertEquals(listOf(3L, 4L, 8L, 16L), b.map { it.value })
        val y = FinanceMath.buckets(all, FinPeriod.YESTERDAY, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(listOf(0L, 1000L, 0L, 0L), y.map { it.value })
    }

    @Test
    fun semuaMemakaiEnamBulanTerakhir() {
        val today = d(2026, 11, 21)
        val all = listOf(
            tx(d(2026, 11, 2), 8, 5), tx(d(2026, 6, 30), 8, 7), tx(d(2026, 5, 31), 8, 999),
            tx(d(2025, 11, 5), 8, 888) // bulan sama tahun lalu: bukan Nov 2026
        )
        val b = FinanceMath.buckets(all, FinPeriod.ALL, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(listOf("Jun", "Jul", "Agu", "Sep", "Okt", "Nov"), b.map { it.label })
        assertEquals(listOf(7L, 0L, 0L, 0L, 0L, 5L), b.map { it.value })
        val en = FinanceMath.buckets(all, FinPeriod.ALL, FinType.EXPENSE, today, AppLang.EN, utc)
        assertEquals("Aug", en[2].label)
    }

    @Test
    fun tahunDuaBelasBulan() {
        val today = d(2026, 11, 21)
        val all = listOf(tx(d(2026, 1, 1), 0, 3), tx(d(2026, 12, 31), 23, 9), tx(d(2025, 12, 31), 23, 1000))
        val b = FinanceMath.buckets(all, FinPeriod.YEAR, FinType.EXPENSE, today, AppLang.ID, utc)
        assertEquals(12, b.size)
        assertEquals(3L, b.first().value)
        assertEquals(9L, b.last().value)
        assertEquals("Des", b.last().label)
    }

    @Test
    fun jumlahBatangSamaDenganRingkasan() {
        val today = d(2026, 11, 21)
        val all = (0..40).map { tx(today.minusDays(it.toLong()), (it * 5) % 24, 1000L + it) } +
            listOf(tx(d(2026, 11, 3), 3, 50_000, FinType.INCOME))
        for (p in listOf(FinPeriod.TODAY, FinPeriod.WEEK, FinPeriod.MONTH, FinPeriod.LAST_MONTH, FinPeriod.YEAR)) {
            val (from, to) = FinanceMath.range(p, today, utc)
            val s = FinanceMath.summarize(all, from, to)
            val sumExp = FinanceMath.buckets(all, p, FinType.EXPENSE, today, AppLang.ID, utc).sumOf { it.value }
            val sumInc = FinanceMath.buckets(all, p, FinType.INCOME, today, AppLang.ID, utc).sumOf { it.value }
            assertEquals("pengeluaran $p", s.expense, sumExp)
            assertEquals("pemasukan $p", s.income, sumInc)
        }
        assertTrue(FinanceMath.buckets(emptyList(), FinPeriod.WEEK, FinType.EXPENSE, today, AppLang.ID, utc).all { it.value == 0L })
    }
}

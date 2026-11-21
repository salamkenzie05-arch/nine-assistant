package id.nine.assistant.core

import id.nine.assistant.core.Perf.Pace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tes pengatur laju gambar: tidak pernah lebih cepat dari yang diminta, dan makin lambat saat berat/hemat/panas. */
class PerfTest {

    @Test
    fun lajuDasarMengikutiPace() {
        assertEquals(10L, Perf.intervalFor(Pace.ACTIVE, heavy = false, thermal = 0, saver = false))
        assertEquals(25L, Perf.intervalFor(Pace.CALM, heavy = false, thermal = 0, saver = false))
        assertEquals(42L, Perf.intervalFor(Pace.SLEEP, heavy = false, thermal = 0, saver = false))
    }

    @Test
    fun beratDanHematMenaikkanBatasBawahJadi30fps() {
        assertEquals(25L, Perf.intervalFor(Pace.ACTIVE, heavy = true, thermal = 0, saver = false))
        assertEquals(25L, Perf.intervalFor(Pace.ACTIVE, heavy = false, thermal = 0, saver = true))
        // Yang sudah lebih lambat tidak dipercepat.
        assertEquals(42L, Perf.intervalFor(Pace.SLEEP, heavy = true, thermal = 0, saver = true))
    }

    @Test
    fun panasMemperlambatBertahap() {
        assertEquals(25L, Perf.intervalFor(Pace.ACTIVE, false, 1, false))
        assertEquals(42L, Perf.intervalFor(Pace.ACTIVE, false, 2, false))
        assertEquals(42L, Perf.intervalFor(Pace.CALM, false, 2, false))
    }

    @Test
    fun tidakAdaKombinasiYangLebihCepatDariPace() {
        for (p in Pace.values()) for (h in listOf(false, true)) for (t in 0..2) for (s in listOf(false, true)) {
            assertTrue(Perf.intervalFor(p, h, t, s) >= p.ms)
        }
    }

    @Test
    fun jedaDihitungDariAwalFrame() {
        assertEquals(7L, Perf.delayFor(25, 18))
        assertEquals(0L, Perf.delayFor(10, 14)) // menggambar lebih lama dari jatah: langsung frame berikutnya
        assertEquals(10L, Perf.delayFor(10, 0))
    }

    @Test
    fun statusTermalDipetakan() {
        assertEquals(0, Perf.thermalLevel(0)) // NONE
        assertEquals(0, Perf.thermalLevel(1)) // LIGHT
        assertEquals(1, Perf.thermalLevel(2)) // MODERATE
        assertEquals(2, Perf.thermalLevel(3)) // SEVERE
        assertEquals(2, Perf.thermalLevel(6)) // SHUTDOWN
    }
}

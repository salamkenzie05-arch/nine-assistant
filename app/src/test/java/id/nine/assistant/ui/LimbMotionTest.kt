package id.nine.assistant.ui

import id.nine.assistant.core.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Tes gerak tangan dan kaki Nine: terbatas, kembali tenang, bervariasi, dan mengikuti suasana hati. */
class LimbMotionTest {

    private fun run(m: LimbMotion, from: Float, to: Float, hz: Int = 60, onFrame: (Float) -> Unit = {}) {
        val n = ((to - from) * hz).toInt()
        for (i in 1..n) {
            val t = from + i.toFloat() / hz
            m.step(t, 1f / hz)
            onFrame(t)
        }
    }

    private fun armMeanY(mood: Mood?): Float {
        val m = LimbMotion(autoGestures = false)
        if (mood != null) m.setMood(mood, 1f, 100_000L)
        var sum = 0f
        var n = 0
        run(m, 0f, 12f) { t ->
            if (t >= 2f) {
                sum += (m.limbs[0].y + m.limbs[1].y) / 2f
                n++
            }
        }
        return sum / n
    }

    @Test
    fun gerakSelaluTerbatasDanTidakNaN() {
        val m = LimbMotion(seed = 1L)
        var worst = 0f
        run(m, 0f, 120f) { t ->
            if (t.toInt() % 17 == 3 && abs(t - t.toInt()) < 0.02f) m.hop(t)
            for (l in m.limbs) {
                assertTrue(l.x.isFinite() && l.y.isFinite())
                worst = maxOf(worst, abs(l.x), abs(l.y))
                assertTrue("skala ${l.scaleX} ${l.scaleY}", l.scaleX in 0.8f..1.25f && l.scaleY in 0.8f..1.25f)
            }
            assertTrue(abs(m.bodyY) < 20f)
        }
        assertTrue("simpangan terbesar $worst dp", worst < 60f)
    }

    @Test
    fun selangWaktuBesarTidakMeledak() {
        val m = LimbMotion()
        m.step(100f, 5f)
        m.step(100.5f, 0.5f)
        for (l in m.limbs) assertTrue(l.x.isFinite() && l.y.isFinite() && abs(l.x) < 60f && abs(l.y) < 60f)
        assertTrue(m.bodyY.isFinite())
    }

    @Test
    fun melompatNaikLaluKembaliTenang() {
        val m = LimbMotion(autoGestures = false)
        run(m, 0f, 2f)
        m.hop(2f)
        var top = 0f
        run(m, 2f, 5f) { t -> if (t <= 2.6f) top = minOf(top, m.bodyY) }
        assertTrue("badan harus naik, terendah $top", top <= -5f)
        assertTrue("badan harus kembali, ${m.bodyY}", abs(m.bodyY) < 1.6f)
    }

    @Test
    fun kakiIkutBadanSaatMelompat() {
        // Tepat setelah ketukan kaki baru mulai ikut naik (pegas, tertinggal), lalu terus naik bersama badan.
        val m = LimbMotion(autoGestures = false)
        run(m, 0f, 2f)
        val before = m.limbs[2].y
        m.hop(2f)
        run(m, 2f, 2.05f)
        val early = m.limbs[2].y - before
        run(m, 2.05f, 2.3f)
        val later = m.limbs[2].y - before
        assertTrue("kaki naik bertahap: awal $early, nanti $later", later < early - 1f)
    }

    @Test
    fun adaGesturAcakPadaTanganDanKaki() {
        val m = LimbMotion(seed = 5L)
        run(m, 0f, 60f)
        val arms = m.limbs[0].gestureCount + m.limbs[1].gestureCount
        val feet = m.limbs[2].gestureCount + m.limbs[3].gestureCount
        assertTrue("gestur tangan $arms", arms >= 6)
        assertTrue("gestur kaki $feet", feet >= 3)
    }

    @Test
    fun setiapBolaBergerakBerbeda() {
        val m = LimbMotion(autoGestures = false)
        var armDiff = 0f
        var footDiff = 0f
        run(m, 0f, 30f) {
            armDiff = maxOf(armDiff, abs(m.limbs[0].x - m.limbs[1].x))
            footDiff = maxOf(footDiff, abs(m.limbs[2].y - m.limbs[3].y))
        }
        assertTrue("tangan kiri dan kanan tidak kembar ($armDiff)", armDiff > 2f)
        assertTrue("kaki kiri dan kanan tidak kembar ($footDiff)", footDiff > 1f)
    }

    @Test
    fun suasanaHatiMenggeserTangan() {
        val base = armMeanY(null)
        assertTrue("senang mengangkat tangan", armMeanY(Mood.HAPPY) - base < -3f)
        assertTrue("sedih menurunkan tangan", armMeanY(Mood.SAD) - base > 3f)
    }

    @Test
    fun suasanaHatiBerakhirDanKembaliNormal() {
        val m = LimbMotion(autoGestures = false)
        m.setMood(Mood.SURPRISED, 0f, 1000L)
        run(m, 0f, 1f)
        val during = (m.limbs[0].y + m.limbs[1].y) / 2f
        run(m, 1f, 6f)
        val after = (m.limbs[0].y + m.limbs[1].y) / 2f
        assertTrue("kaget mengangkat tangan ($during)", during < -4f)
        assertTrue("sesudahnya kembali ($after)", abs(after) < 8f)
    }

    @Test
    fun hasilSamaUntukBenihSama() {
        val a = LimbMotion(seed = 3L)
        val b = LimbMotion(seed = 3L)
        run(a, 0f, 20f)
        run(b, 0f, 20f)
        for (i in a.limbs.indices) {
            assertEquals(a.limbs[i].x, b.limbs[i].x, 1e-4f)
            assertEquals(a.limbs[i].y, b.limbs[i].y, 1e-4f)
        }
    }

    @Test
    fun livelyHanyaSaatAdaGerakCepat() {
        val m = LimbMotion(autoGestures = false)
        run(m, 0f, 5f)
        assertTrue("diam saja bukan lively", !m.lively(5f))
        // Lompatan: lively selama lompat + tertinggal + mengendap, lalu tenang lagi.
        m.hop(5f)
        run(m, 5f, 5.5f)
        assertTrue(m.lively(5.5f))
        run(m, 5.5f, 8f)
        assertTrue("setelah mengendap tenang lagi", !m.lively(8f))
        // Suasana hati: lively selama suasana hati dan sesaat sesudahnya.
        m.setMood(Mood.HAPPY, 8f, 1500)
        run(m, 8f, 9f)
        assertTrue(m.lively(9f))
        run(m, 9f, 14f)
        assertTrue("suasana hati sudah lewat", !m.lively(14f))
    }

    @Test
    fun livelyMenyalaSaatGesturAcak() {
        val m = LimbMotion(seed = 3L)
        var livelyFrames = 0
        var calmFrames = 0
        run(m, 0f, 120f) { t -> if (m.lively(t)) livelyFrames++ else calmFrames++ }
        // Gestur acak terjadi tapi sebagian besar waktu gerak hanya ayunan pelan (hemat gambar).
        assertTrue(livelyFrames > 0)
        assertTrue("terlalu sering lively: $livelyFrames vs $calmFrames", calmFrames > livelyFrames / 2)
    }
}

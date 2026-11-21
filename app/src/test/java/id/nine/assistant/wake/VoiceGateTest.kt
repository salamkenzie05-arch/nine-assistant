package id.nine.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Tes gerbang suara: hening tidak diteruskan, ucapan utuh (dengan pre-roll), dan tidak macet terbuka di tempat bising. */
class VoiceGateTest {

    private val frame = 320

    /** Bingkai sinus dengan RMS ≈ [rms]; [tag] mengisi fase supaya tiap bingkai bisa dibedakan. */
    private fun tone(rms: Float, tag: Int = 0): ShortArray {
        val amp = rms * 1.4142f
        return ShortArray(frame) { i -> (amp * sin(2.0 * PI * 300.0 * (i + tag * frame) / 16000.0)).toInt().toShort() }
    }

    private class Rec : VoiceGate.Sink {
        val frames = ArrayList<ShortArray>()
        var ends = 0
        override fun onAudio(buf: ShortArray, len: Int) {
            frames += buf.copyOf(len)
        }

        override fun onUtteranceEnd() {
            ends++
        }
    }

    @Test
    fun heningTidakPernahSampaiKePengenal() {
        val g = VoiceGate()
        val r = Rec()
        repeat(2000) { g.feed(tone(30f, it), frame, r) } // 40 detik hening
        assertEquals(0, r.frames.size)
        assertFalse(g.isOpen)
    }

    @Test
    fun ucapanDiteruskanLengkapDenganPreRollDanHangover() {
        val g = VoiceGate(preRollFrames = 20, hangoverFrames = 40)
        val r = Rec()
        repeat(100) { g.feed(tone(30f, it), frame, r) }
        assertEquals(0, r.frames.size)
        repeat(50) { g.feed(tone(1500f, it), frame, r) } // bicara 1 detik
        // 20 bingkai pre-roll + 50 bingkai ucapan sudah masuk, belum ada akhir ucapan.
        assertEquals(70, r.frames.size)
        assertEquals(0, r.ends)
        assertTrue(g.isOpen)
        // Hening: tetap diteruskan selama hangover (40 bingkai = 800 ms), lalu berakhir tepat sekali.
        repeat(39) { g.feed(tone(30f, it), frame, r) }
        assertEquals(0, r.ends)
        g.feed(tone(30f), frame, r)
        assertEquals(1, r.ends)
        assertFalse(g.isOpen)
        assertEquals(70 + 40, r.frames.size)
        // Sesudah itu hening lagi: tidak ada lagi yang diteruskan.
        repeat(200) { g.feed(tone(30f, it), frame, r) }
        assertEquals(110, r.frames.size)
    }

    @Test
    fun preRollBerurutanDariYangTertua() {
        val g = VoiceGate(preRollFrames = 5, hangoverFrames = 10)
        val r = Rec()
        // Bingkai hening bernomor lewat amplitudo kecil yang unik (nilai sampel pertama berbeda-beda).
        for (k in 1..12) {
            val f = ShortArray(frame) { k.toShort() } // RMS = k, jauh di bawah ambang
            g.feed(f, frame, r)
        }
        g.feed(tone(1500f), frame, r)
        // Pre-roll = 5 bingkai terakhir (8..12), berurutan, lalu bingkai yang membuka gerbang.
        assertEquals(6, r.frames.size)
        assertEquals(listOf(8, 9, 10, 11, 12), r.frames.take(5).map { it[0].toInt() })
    }

    @Test
    fun jedaAntarKataDalamHangoverTidakMemotongUcapan() {
        val g = VoiceGate(hangoverFrames = 40)
        val r = Rec()
        repeat(10) { g.feed(tone(1500f, it), frame, r) }
        repeat(25) { g.feed(tone(30f, it), frame, r) } // jeda 500 ms
        repeat(10) { g.feed(tone(1500f, it), frame, r) }
        assertEquals(0, r.ends)
        assertTrue(g.isOpen)
    }

    @Test
    fun ambangMengikutiDerauRuangan() {
        val g = VoiceGate()
        val r = Rec()
        repeat(1500) { g.feed(tone(100f, it), frame, r) } // ruangan agak bising tapi rata
        // Derau 100 → ambang ≈ 350: derau itu sendiri tidak membuka gerbang.
        assertEquals(0, r.frames.size)
        assertTrue(g.openThreshold() >= 300f)
        // Ucapan di atas derau tetap membuka gerbang.
        g.feed(tone(1500f), frame, r)
        assertTrue(g.isOpen)
    }

    @Test
    fun ambangDibatasiAgarUcapanKerasSelaluBisaMembuka() {
        val g = VoiceGate()
        val r = Rec()
        repeat(3000) { g.feed(tone(1700f, it), frame, r) } // sangat bising
        assertTrue(g.openThreshold() <= 1800f)
    }

    @Test
    fun deraurataYangMembukaGerbangAkhirnyaDipelajariDanGerbangMenutup() {
        val g = VoiceGate()
        val r = Rec()
        // Bising rata (RMS 400) sejak awal: gerbang terbuka karena 400 > ambang awal, tidak boleh macet selamanya.
        var closedAt = -1
        for (i in 0 until 6000) { // 2 menit
            g.feed(tone(400f, i), frame, r)
            if (!g.isOpen) {
                closedAt = i
                break
            }
        }
        assertTrue("gerbang harus menutup sendiri di derau rata", closedAt in 1..5999)
        assertEquals(1, r.ends)
    }

    @Test
    fun bingkaiPendekDanKosongAman() {
        val g = VoiceGate()
        val r = Rec()
        g.feed(ShortArray(0), 0, r)
        g.feed(tone(1500f), 100, r) // bingkai pendek
        assertEquals(1, r.frames.size)
        assertEquals(100, r.frames[0].size)
    }

    @Test
    fun resetMengembalikanKeadaanAwal() {
        val g = VoiceGate()
        val r = Rec()
        repeat(5) { g.feed(tone(1500f, it), frame, r) }
        assertTrue(g.isOpen)
        g.reset()
        assertFalse(g.isOpen)
        assertEquals(40f, g.noiseFloor, 0.001f)
    }

    @Test
    fun rmsBenar() {
        assertEquals(0f, VoiceGate.rmsOf(ShortArray(10), 10), 0.0001f)
        assertEquals(100f, VoiceGate.rmsOf(ShortArray(8) { 100 }, 8), 0.01f)
        assertEquals(100f, VoiceGate.rmsOf(ShortArray(8) { if (it % 2 == 0) 100 else -100 }, 8), 0.01f)
    }

    @Test
    fun frasaDicocokkanSepertiSebelumnya() {
        val p = listOf("hey nine", "okay nine")
        assertTrue(WakePhrase.matches("hey nine", p))
        assertTrue(WakePhrase.matches("well hey nine", p))
        assertTrue(WakePhrase.matches("hey nine please", p))
        assertTrue(WakePhrase.matches(" okay nine ", p))
        assertFalse(WakePhrase.matches("hey", p))
        assertFalse(WakePhrase.matches("hey nines", p))
        assertFalse(WakePhrase.matches("[unk]", p))
        assertFalse(WakePhrase.matches("", p))
    }
}

package id.nine.assistant.widget

import id.nine.assistant.widget.WidgetUpdater.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tes urutan bingkai widget: mode diam hanya satu bingkai, dan semua nama bingkai ada di tabel gambar. */
class WidgetFramesTest {

    @Test
    fun modeDiamSatuBingkaiUntukSemuaWajah() {
        for (f in Face.values()) assertEquals(f.name, 1, WidgetUpdater.frameNames(f, true).size)
    }

    @Test
    fun animasiPenuhPanjangDanBerganti() {
        for (f in Face.values()) {
            val n = WidgetUpdater.frameNames(f, false)
            assertTrue("${f.name} terlalu pendek", n.size > 20)
            assertTrue("${f.name} tidak berganti", n.toSet().size > 1)
        }
    }

    @Test
    fun bingkaiDiamSamaDenganAwalAnimasi() {
        for (f in Face.values()) {
            assertEquals(f.name, WidgetUpdater.frameNames(f, false).first(), WidgetUpdater.frameNames(f, true).first())
        }
    }

    @Test
    fun semuaNamaBingkaiAdaDiSemuaTabel() {
        for (table in listOf(FaceFrames.LIGHT, FaceFrames.DARK, FaceFrames.AUTO)) {
            for (f in Face.values()) for (still in listOf(false, true)) {
                for (name in WidgetUpdater.frameNames(f, still)) {
                    assertTrue("bingkai '$name' tidak ada", table.containsKey(name))
                }
            }
        }
    }

    @Test
    fun wajahNetralMemakaiEkspresiTambahanDanTidakTerlaluPanjang() {
        val n = WidgetUpdater.frameNames(Face.NEUTRAL, false)
        for (name in listOf("wink", "surprised", "curious", "happy")) assertTrue("tanpa $name", name in n)
        // RemoteViews memuat satu anak per bingkai: jaga agar tetap ringan bagi launcher
        for (f in Face.values()) assertTrue("${f.name} terlalu panjang: ${WidgetUpdater.frameNames(f, false).size}", WidgetUpdater.frameNames(f, false).size <= 400)
    }

    @Test
    fun wajahSenangMemakaiMataHatiDanTertawa() {
        val n = WidgetUpdater.frameNames(Face.HAPPY, false)
        for (name in listOf("love1", "love2", "wink", "laugh1", "laugh2")) assertTrue("tanpa $name", name in n)
    }
}

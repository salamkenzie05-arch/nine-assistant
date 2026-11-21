package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportEntryTest {
    private fun yes(s: String) = assertTrue("harus masuk: $s", SupportEntry.shouldOpen(s))
    private fun no(s: String) = assertFalse("tidak boleh masuk: $s", SupportEntry.shouldOpen(s))

    @Test fun storiesOpen() {
        yes("aku sedih banget hari ini")
        yes("Aku lagi cemas banget soal besok")
        yes("hari ini aku dimarahi bos terus aku merasa gagal")
        yes("lagi sedih banget")
        yes("aku merasa kesepian akhir-akhir ini")
        yes("aku merasa tidak berguna")
        yes("I feel so anxious about tomorrow")
        yes("I've been really lonely lately")
        yes("aku kesal banget sama bosku, kerjaan numpuk terus")
        yes("kenapa aku selalu merasa sedih tanpa alasan")
    }

    @Test fun commandsAndQuestionsStay() {
        no("jam berapa sekarang")
        no("tolong ingatkan aku jangan sedih")
        no("apa itu depresi")
        no("cara mengatasi cemas")
        no("how to deal with stress")
        no("cari lagu sedih")
        no("putar lagu galau")
        no("film itu sedih banget")
        no("dia sedih banget hari ini")
        no("aku mau curhat ke ibuku")
    }

    @Test fun lightOrAmbiguousDoNotOpen() {
        no("aku capek")
        no("aku capek habis gym")
        no("aku senang banget hari ini")
        no("aku lapar")
        no("aku bingung mau makan apa")
        no("aku kesal kamu lama banget")
        no("aku takut ketinggalan kereta, jam berapa keretanya")
        no("sedih")
        no("ok")
    }

    @Test fun tiredNeedsMoreSignal() {
        yes("aku capek banget kerja terus setiap hari tidak ada habisnya")
        no("aku capek banget hari ini")
    }

    @Test fun noteIsBilingual() {
        assertTrue(SupportEntry.note(AppLang.ID).contains("selesai"))
        assertTrue(SupportEntry.note(AppLang.EN).contains("done"))
    }

    @Test fun sessionOpensQuietly() {
        val s = SupportSession()
        s.openNatural()
        assertTrue(s.active)
        val t = s.handle("aku sedih banget hari ini", AppLang.ID)
        assertTrue(t != null && t.reply.isNotBlank())
        assertEquals(false, t!!.ended)
    }
}

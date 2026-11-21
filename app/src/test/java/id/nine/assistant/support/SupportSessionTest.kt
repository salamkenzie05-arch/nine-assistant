package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import id.nine.assistant.support.CrisisCheck.Level
import id.nine.assistant.support.SupportIntent.Exercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportSessionTest {

    private val id = AppLang.ID
    private val en = AppLang.EN

    private fun started(lang: AppLang = id) = SupportSession().also { it.start(lang) }

    /** Meniru urutan di aplikasi: cek krisis dulu, lalu giliran biasa. */
    private fun say(s: SupportSession, text: String, lang: AppLang = id): SupportSession.Turn? {
        val lv = CrisisCheck.assess(text)
        if (lv != Level.NONE) s.crisis(lv, lang)?.let { return it }
        return s.handle(text, lang)
    }

    private fun reply(s: SupportSession, text: String, lang: AppLang = id): String = say(s, text, lang)!!.reply

    // ---------------------------------------------------------------- masuk

    @Test
    fun pembukaanJujurTentangBatasan() {
        val s = SupportSession()
        assertFalse(s.active)
        val intro = s.start(id)
        assertTrue(s.active)
        assertTrue(intro.contains("bukan psikolog"))
        assertTrue(intro.contains("tidak disimpan"))
        assertTrue(intro.contains("selesai"))
        assertTrue(intro.trimEnd().endsWith("?"))
        val e = SupportSession().start(en)
        assertTrue(e.contains("not a psychologist"))
        assertTrue(e.contains("isn't saved"))
        assertTrue(e.contains("done"))
    }

    @Test
    fun mulaiLangsungDenganLatihan() {
        val s = SupportSession()
        val txt = s.start(id, Exercise.BREATHING)
        assertTrue(s.active)
        assertTrue(txt.contains("4 hitungan"))
        assertTrue(txt.contains("6 hitungan"))
        // jawaban sesudah latihan ditanggapi sebagai "bagaimana rasanya"
        val t = say(s, "lumayan lebih tenang")!!
        assertEquals(Mood.HAPPY, t.mood)
        assertTrue(t.reply.contains("Senang"))
    }

    // ---------------------------------------------------------------- mendengarkan

    @Test
    fun merefleksikanPerasaanLaluBertanya() {
        val s = started()
        val t = say(s, "aku sedih banget, kerjaanku berantakan")!!
        assertEquals(Mood.SAD, t.mood)
        assertFalse(t.ended)
        assertTrue(t.reply.contains("?"))
        assertNotNull("giliran emosional boleh dibantu AI", t.aiPrompt)
    }

    @Test
    fun tidakMengulangJawabanYangSamaBerturut() {
        val s = started()
        val replies = (1..8).map { reply(s, "aku sedih") }
        for (i in 1 until replies.size) assertNotEquals("balasan ke-$i sama dengan sebelumnya", replies[i - 1], replies[i])
    }

    @Test
    fun setiapGiliranKetigaCukupMenemaniTanpaPertanyaan() {
        val s = started()
        reply(s, "aku sedih"); reply(s, "aku sedih lagi")
        val third = reply(s, "masih sedih")
        // giliran ke-3: menemani, tanpa tawaran/pertanyaan baru kecuali tawaran latihan
        assertTrue(third.contains("Aku di sini") || third.contains("Ambil waktumu") || third.contains("mendengarkan") || third.contains("Mau coba"))
    }

    @Test
    fun ceritaPanjangDiringkasDanDiverifikasi() {
        val s = started()
        val long = "aku capek banget kerja lembur terus dan bos tidak pernah menghargai usahaku, aku juga cemas soal masa depan " +
            "dan merasa sendirian karena teman teman sibuk semua, di rumah pun aku tidak bisa cerita ke siapa siapa, rasanya semuanya menumpuk dan aku kewalahan sekali"
        val r = reply(s, long)
        assertTrue(r.contains("Kalau aku tidak salah tangkap"))
        assertTrue(r.contains("Apakah itu sesuai?"))
        assertEquals("hanya satu pertanyaan", 1, r.count { it == '?' })
    }

    @Test
    fun ceritaTanpaKataPerasaanDitanyaPerasaannya() {
        val s = started()
        val r = reply(s, "tadi pagi aku naik bus dan ketinggalan dompet di kantor")
        assertTrue(r.contains("?"))
        assertTrue(r.contains("perasaan") || r.contains("rasakan") || r.contains("menghadapinya"))
    }

    @Test
    fun menyebutMerasaTapiTidakDikenaliDitanyaPenyebab() {
        val s = started()
        val r = reply(s, "aku merasa aneh akhir akhir ini")
        assertTrue(r.contains("Apa yang membuatmu merasa begitu?"))
    }

    @Test
    fun kabarBaikDisambutHangat() {
        val s = started()
        val t = say(s, "hari ini aku senang banget, lega akhirnya")!!
        assertEquals(Mood.HAPPY, t.mood)
        assertTrue(t.reply.contains("?"))
        assertNull("kabar baik tidak perlu AI", t.aiPrompt)
    }

    @Test
    fun jawabanSangatSingkatDitanggapiLembut() {
        val s = started()
        val a = say(s, "hmm")!!
        assertEquals(Mood.NEUTRAL, a.mood)
        assertNull(a.aiPrompt)
        assertTrue(a.reply.isNotBlank())
        assertEquals("Baik. Aku tetap di sini kalau kamu mau cerita.", reply(started(), "nggak"))
        assertEquals("Aku mendengarkan, silakan lanjutkan.", reply(started(), "iya"))
    }

    @Test
    fun hargaDiriRendahDivalidasiLaluDitantangLembut() {
        val s = started()
        val r = reply(s, "aku merasa tidak berguna")
        assertTrue(r.contains("nyata"))
        assertTrue(r.contains("sahabatmu") || r.contains("Adakah hal"))
        assertEquals(1, r.count { it == '?' })
    }

    @Test
    fun dukaDijawabDenganBelasungkawa() {
        val r = reply(started(), "nenekku baru meninggal minggu ini")
        assertTrue(r.contains("berduka") || r.contains("kehilangan"))
    }

    // ---------------------------------------------------------------- batas peran

    @Test
    fun pertanyaanDiagnosisTidakDijawabDenganDiagnosis() {
        val s = started()
        val r = reply(s, "apakah aku depresi?")
        assertTrue(r.contains("tidak bisa mendiagnosis"))
        assertTrue(r.contains("psikolog atau psikiater"))
        assertFalse(r.contains("kamu depresi", ignoreCase = true))
        val e = reply(started(en), "am I depressed?", en)
        assertTrue(e.contains("can't diagnose"))
    }

    @Test
    fun pertanyaanIdentitasDijawabJujur() {
        val r = reply(started(), "kamu psikolog beneran?")
        assertTrue(r.contains("bukan psikolog"))
        assertTrue(r.contains("asisten digital"))
        val e = reply(started(en), "are you a real therapist?", en)
        assertTrue(e.contains("not a psychologist"))
    }

    @Test
    fun mintaSaranDiberiPilihanKecilTanpaObat() {
        val s = started()
        val r = reply(s, "aku cemas banget, aku harus gimana ya?")
        assertTrue(r.contains("1.") && r.contains("2.") && r.contains("3."))
        assertTrue(r.contains("bukan ahli"))
        assertFalse(r.contains("obat", ignoreCase = true))
        assertFalse(r.contains("mg"))
        val heavy = reply(started(), "aku merasa hampa, harus bagaimana?")
        assertTrue(heavy.contains("psikolog"))
        assertTrue(heavy.contains("119"))
    }

    // ---------------------------------------------------------------- latihan

    @Test
    fun tawaranLatihanMunculSekaliSetelahBeberapaGiliranBerat() {
        val s = started()
        val first = listOf("aku cemas banget", "masih cemas", "makin panik rasanya").map { reply(s, it) }
        assertEquals("tawaran muncul di giliran ketiga", 1, first.count { it.contains("Mau coba? (iya / tidak)") })
        assertTrue(first[2].contains("Mau coba? (iya / tidak)"))
        val after = reply(s, "iya")
        assertTrue(after.contains("hitungan") || after.contains("5 hal"))
        // setelah latihan, ditanya rasanya
        val feel = reply(s, "masih agak cemas")
        assertTrue(feel.contains("Tidak apa-apa kalau belum terasa berbeda"))
        // tidak ditawari lagi
        val more = listOf("tetap gelisah", "aku takut besok", "cemas lagi").map { reply(s, it) }
        assertEquals(0, more.count { it.contains("Mau coba? (iya / tidak)") })
    }

    @Test
    fun menolakTawaranLatihanDihormati() {
        val s = started()
        listOf("aku cemas banget", "masih cemas", "makin panik rasanya").forEach { reply(s, it) }
        val r = reply(s, "tidak")
        assertTrue(r.contains("Tidak apa-apa"))
        assertFalse(r.contains("hitungan"))
    }

    @Test
    fun latihanBisaDimintaKapanSaja() {
        val s = started()
        val r = reply(s, "latihan napas dong")
        assertTrue(r.contains("4 hitungan"))
        val g = reply(started(), "coba grounding")
        assertTrue(g.contains("5 hal yang kamu lihat"))
    }

    @Test
    fun semuaLatihanPunyaTeksLengkapDuaBahasa() {
        val s = SupportSession()
        for (ex in Exercise.values()) for (l in AppLang.values()) {
            val t = s.exerciseText(ex, l)
            assertTrue("$ex $l", t.length > 80)
            assertFalse("$ex $l", t.contains("CMD:"))
        }
    }

    // ---------------------------------------------------------------- rujukan profesional

    @Test
    fun rujukanProfesionalSekaliBilaBerlangsungLama() {
        val s = started()
        val r1 = reply(s, "sudah berbulan-bulan aku murung terus")
        assertTrue(r1.contains("psikolog"))
        assertTrue(r1.contains("119"))
        val r2 = reply(s, "sudah berbulan-bulan juga aku susah tidur")
        assertFalse("rujukan tidak diulang", r2.contains("Healing119"))
    }

    @Test
    fun hampaDanPutusAsaLangsungDiarahkanLembut() {
        val r = reply(started(), "aku merasa putus asa")
        assertTrue(r.contains("psikolog"))
    }

    // ---------------------------------------------------------------- selesai

    @Test
    fun selesaiMenutupDenganHangat() {
        val s = started()
        reply(s, "aku sedih")
        val t = say(s, "selesai")!!
        assertTrue(t.ended)
        assertEquals(Mood.LOVE, t.mood)
        assertFalse(s.active)
        assertTrue(t.reply.contains("Terima kasih"))
        assertNull("setelah selesai sesi tidak menjawab lagi", s.handle("aku sedih", id))
    }

    @Test
    fun penutupSetelahBanyakGiliranMenyebutBantuanProfesional() {
        val s = started()
        repeat(5) { reply(s, "tadi aku ke pasar lalu pulang") }
        val t = say(s, "udahan dulu")!!
        assertTrue(t.reply.contains("psikolog"))
        assertTrue(t.reply.contains("119 lalu tekan 8"))
        val short = started()
        reply(short, "aku sedih")
        assertFalse(say(short, "selesai")!!.reply.contains("Healing119"))
    }

    @Test
    fun penutupTidakMengulangRujukanYangSudahDiberikan() {
        val s = started()
        reply(s, "sudah berbulan-bulan aku murung")   // rujukan diberikan di sini
        repeat(4) { reply(s, "aku sedih") }
        val closing = say(s, "selesai")!!.reply
        assertFalse(closing.contains("Healing119"))
    }

    // ---------------------------------------------------------------- krisis dalam sesi

    @Test
    fun krisisJelasMendapatBalasanSiaga() {
        val s = started()
        val t = say(s, "aku ingin bunuh diri")!!
        assertTrue(t.reply.contains("Apakah kamu aman saat ini?"))
        assertTrue(t.reply.contains("112"))
        assertTrue(t.reply.contains("119 lalu tekan 8"))
        assertTrue(t.reply.contains("healing119.id"))
        assertNull("krisis tidak boleh diserahkan ke AI", t.aiPrompt)
        assertEquals(Mood.SAD, t.mood)
    }

    @Test
    fun krisisBerulangDijawabLebihRingkasTapiTetapAda112() {
        val s = started()
        val first = say(s, "aku ingin bunuh diri")!!.reply
        val second = say(s, "aku masih ingin bunuh diri")!!.reply
        assertTrue(second.length < first.length)
        assertTrue(second.contains("112"))
        assertTrue(second.contains("119 lalu tekan 8"))
    }

    @Test
    fun mendesakMenyuruhJauhkanBendaBerbahaya() {
        val s = started()
        val t = say(s, "aku mau bunuh diri sekarang, aku sudah pegang pisau")!!
        assertTrue(t.reply.contains("jauhkan"))
        assertTrue(t.reply.contains("112"))
        assertNull(t.aiPrompt)
    }

    @Test
    fun jawabanAtasPertanyaanKeamananDitindaklanjuti() {
        val s = started()
        say(s, "aku ingin bunuh diri")
        val unsafe = say(s, "tidak, aku tidak aman")!!
        assertTrue(unsafe.reply.contains("jauhkan"))
        val s2 = started()
        say(s2, "aku ingin bunuh diri")
        val safe = say(s2, "iya aku aman")!!
        assertTrue(safe.reply.contains("Syukurlah kamu aman"))
    }

    @Test
    fun krisisDiLuarSesiMembukaSesiSenyap() {
        val s = SupportSession()
        val t = s.crisis(Level.SELF, id)!!
        assertTrue(s.active)
        assertTrue(t.reply.contains("112"))
        // pesan berikutnya ditangani sebagai curhat
        assertNotNull(s.handle("aku sendirian di kamar", id))
    }

    @Test
    fun tentangOrangLainTidakMembukaSesi() {
        val s = SupportSession()
        val t = s.crisis(Level.OTHER, id)!!
        assertFalse(s.active)
        assertTrue(t.reply.contains("119 lalu tekan 8"))
        assertTrue(t.reply.contains("112"))
        assertTrue(t.reply.contains("tidak menanamkan ide"))
    }

    // ---------------------------------------------------------------- pertanyaan lembut (CHECK_IN)

    @Test
    fun kalimatSamarDiLuarSesiDitanyaLembut() {
        val s = SupportSession()
        val t = s.crisis(Level.CHECK_IN, id)!!
        assertTrue(s.active)
        assertTrue(t.reply.contains("apakah kamu sedang punya pikiran untuk menyakiti diri atau mengakhiri hidup"))
        assertTrue(t.reply.contains("119 lalu tekan 8"))
    }

    @Test
    fun jawabanTidakMenutupSesiSamarDanTidakMemerangkap() {
        val s = SupportSession()
        s.crisis(Level.CHECK_IN, id)
        val t = s.handle("tidak, cuma lagi capek aja", id)!!
        assertTrue(t.ended)
        assertFalse(s.active)
        assertTrue(t.reply.contains("Syukurlah"))
        assertTrue(t.reply.contains("aku mau curhat"))
    }

    @Test
    fun jawabanIyaMenjadiBalasanSiaga() {
        val s = SupportSession()
        s.crisis(Level.CHECK_IN, id)
        val t = s.handle("iya", id)!!
        assertTrue(t.reply.contains("Apakah kamu aman saat ini?"))
        assertTrue(s.active)
    }

    @Test
    fun jawabanTentangHalLainMelepasSesiSamar() {
        val s = SupportSession()
        s.crisis(Level.CHECK_IN, id)
        assertNull("harus dikembalikan ke alur biasa", s.handle("jam berapa sekarang", id))
        assertFalse(s.active)
    }

    @Test
    fun jawabanPanjangBerperasaanMenjadiCurhat() {
        val s = SupportSession()
        s.crisis(Level.CHECK_IN, id)
        val t = s.handle("aku merasa kewalahan dengan semuanya akhir akhir ini, kerjaan dan keluarga", id)!!
        assertTrue(s.active)
        assertFalse(t.ended)
    }

    @Test
    fun duaKalimatSamarBerturutTanpaJawabanNaikKeSiaga() {
        val s = SupportSession()
        s.crisis(Level.CHECK_IN, id)
        val t = s.crisis(Level.CHECK_IN, id)!!
        assertTrue(t.reply.contains("Apakah kamu aman saat ini?"))
    }

    @Test
    fun sesudahMenjawabTidakKalimatSamarTidakDitanyaTerus() {
        val s = started()
        s.crisis(Level.CHECK_IN, id)
        s.handle("tidak", id)
        assertNull("sudah dijawab tidak: lanjut mendengarkan", s.crisis(Level.CHECK_IN, id))
        // tetapi niat jelas tetap dijawab siaga
        assertNotNull(s.crisis(Level.SELF, id))
    }

    // ---------------------------------------------------------------- dua bahasa

    @Test
    fun inggrisBerjalan() {
        val s = started(en)
        val r = reply(s, "I feel so lonely and tired", en)
        assertTrue(r.isNotBlank())
        assertFalse(r.contains("Aku"))
        val crisis = reply(s, "I want to kill myself", en)
        assertTrue(crisis.contains("Are you safe right now?"))
        assertTrue(crisis.contains("119, then press 8"))
        assertTrue(crisis.contains("112"))
    }

    // ---------------------------------------------------------------- kualitas umum

    @Test
    fun tidakAdaBalasanKosongPanjangBerlebihanAtauPerintahLiar() {
        val inputs = listOf(
            "aku sedih", "aku cemas", "aku marah banget", "aku capek", "aku kesepian", "aku bersalah", "aku kewalahan",
            "aku bingung", "aku senang", "tadi aku ke pasar", "hmm", "ya", "tidak", "terima kasih", "kamu siapa",
            "aku harus gimana", "apakah aku depresi", "latihan napas", "aku merasa hampa", "aku putus asa", "aku merasa tidak berguna",
            "ayahku meninggal", "?", "...", "🙂", "a".repeat(3000), "sedih ".repeat(200), "aku sedih. ".repeat(40)
        )
        for (l in AppLang.values()) {
            val s = started(l)
            for (round in 1..3) for (x in inputs) {
                val t = say(s, x, l) ?: continue
                assertTrue("kosong untuk: $x", t.reply.isNotBlank())
                assertTrue("terlalu panjang (${t.reply.length}) untuk: $x", t.reply.length < 900)
                assertFalse(t.reply.contains("CMD:"))
                assertFalse(t.reply.contains("\$"))
                if (t.ended) { s.start(l) }
            }
        }
    }

    @Test
    fun balasanBiasaTidakMenyebutObatDanTidakMendiagnosis() {
        val s = started()
        val inputs = listOf("aku sedih", "aku cemas", "aku kesepian", "aku capek", "aku bersalah", "aku marah", "aku kewalahan",
            "aku bingung", "aku hampa", "aku putus asa", "aku tidak berguna", "kehilangan ibuku")
        for (x in inputs) {
            val r = reply(s, x)
            assertFalse("menyebut obat: $r", Regex("""\b(?:obat|dosis|antidepresan)\b""", RegexOption.IGNORE_CASE).containsMatchIn(r))
            assertFalse("mendiagnosis: $r", Regex("""\bkamu (?:mengalami|menderita|terkena) """).containsMatchIn(r))
        }
    }

    @Test
    fun nomorBantuanSatuSumber() {
        assertEquals("112", CrisisResources.EMERGENCY)
        assertEquals("119", CrisisResources.HEALING_CALL)
        assertEquals("8", CrisisResources.HEALING_EXT)
        assertEquals("healing119.id", CrisisResources.HEALING_WEB)
        assertEquals("119 lalu tekan 8", CrisisResources.healingId())
        assertEquals("119, then press 8", CrisisResources.healingEn())
        for (lv in listOf(Level.SELF, Level.IMMEDIATE, Level.CHECK_IN, Level.OTHER)) for (l in AppLang.values()) {
            val r = CrisisReplies.reply(lv, l)
            assertTrue("$lv $l tanpa 119/8", r.contains("119") && (r.contains("press 8") || r.contains("tekan 8")))
            assertTrue("$lv $l terlalu panjang", r.length < 900)
        }
        assertTrue(CrisisReplies.reply(Level.SELF, id).contains("healing119.id"))
        assertTrue(CrisisReplies.reply(Level.IMMEDIATE, id).contains("112"))
        assertEquals("", CrisisReplies.reply(Level.NONE, id))
    }
}

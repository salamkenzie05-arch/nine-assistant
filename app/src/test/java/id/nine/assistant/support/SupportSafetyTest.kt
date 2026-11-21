package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import id.nine.assistant.support.Feelings.Emotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportSafetyTest {

    private fun vet(s: String?, l: AppLang = AppLang.ID) = SupportSafety.vet(s, l)

    @Test
    fun balasanBaikLolosApaAdanya() {
        val ok = "Kedengarannya kamu sedang sangat lelah dengan semua tuntutan itu. Apa yang paling terasa berat bagimu saat ini?"
        assertEquals(ok, vet(ok))
        val en = "That sounds really exhausting. What feels heaviest for you right now?"
        assertEquals(en, vet(en, AppLang.EN))
    }

    @Test
    fun perintahDanTautanDitolak() {
        assertNull(vet("Aku mengerti perasaanmu. CMD:ALARM|07:00 Apa yang kamu rasakan?"))
        assertNull(vet("cmd : open youtube. Aku di sini untukmu, ceritakan lebih banyak ya."))
        assertNull(vet("Kunjungi https://contoh.com untuk bantuan, aku di sini untukmu."))
        assertNull(vet("Coba lihat www.contoh.com ya, aku mendengarkan kamu."))
    }

    @Test
    fun diagnosisDitolak() {
        listOf(
            "Sepertinya kamu mengalami depresi. Apa yang kamu rasakan?",
            "Kamu menderita gangguan kecemasan, sebaiknya segera berobat.",
            "Kamu depresi, itu jelas dari ceritamu. Mau cerita lebih lanjut?",
            "Ini adalah tanda depresi, sebaiknya kamu periksa.",
            "Kamu memiliki bipolar, dan itu perlu ditangani.",
            "You have depression, and that explains how you feel. Tell me more?",
            "It sounds like you have anxiety disorder. What do you think?",
            "You are depressed, so please see someone about it."
        ).forEach { assertNull("harus ditolak: $it", vet(it, if (it.startsWith("You") || it.startsWith("It")) AppLang.EN else AppLang.ID)) }
    }

    @Test
    fun obatDanDosisDitolak() {
        listOf(
            "Kamu bisa minum obat penenang agar lebih tenang, bagaimana menurutmu?",
            "Dosis 50 mg biasanya membantu orang yang cemas seperti kamu.",
            "Antidepresan mungkin cocok untukmu, apa kamu pernah mencobanya?",
            "Coba minum vitamin dan suplemen supaya mood membaik ya sayang.",
        ).forEach { assertNull("harus ditolak: $it", vet(it)) }
        assertNull(vet("You could take some medication to calm down, what do you think?", AppLang.EN))
        assertNull(vet("A prescription might help you sleep better, would you try it?", AppLang.EN))
    }

    @Test
    fun membahasBunuhDiriDiluarKrisisAturanDitolak() {
        assertNull(vet("Aku khawatir kamu berpikir untuk bunuh diri. Apakah itu benar?"))
        assertNull(vet("Are you thinking about suicide? I am worried about you, tell me.", AppLang.EN))
        assertNull(vet("Jangan melukai diri sendiri ya, aku peduli padamu. Apa yang terjadi?"))
    }

    @Test
    fun kalimatMeremehkanDitolak() {
        listOf(
            "Jangan sedih, semuanya akan baik-baik saja. Apa yang terjadi?",
            "Kamu lebay deh, coba pikirkan yang positif saja. Mau cerita?",
            "Sudahlah, move on saja, hidup terus berjalan. Apa rencanamu?",
            "Setidaknya kamu masih punya pekerjaan, bersyukurlah. Mau cerita?",
            "Bersyukurlah dengan apa yang kamu punya, itu lebih baik. Kenapa begitu?"
        ).forEach { assertNull("harus ditolak: $it", vet(it)) }
        assertNull(vet("Don't be sad, everything will be fine. What happened?", AppLang.EN))
        assertNull(vet("Just think positive and get over it, okay? What happened?", AppLang.EN))
        assertNull(vet("At least you still have a job. What else is going on?", AppLang.EN))
    }

    @Test
    fun mengaku_psikologDitolak() {
        assertNull(vet("Sebagai psikolog, aku menyarankan kamu istirahat. Apa yang terjadi?".replace("Sebagai psikolog, aku", "Aku adalah psikolog dan aku")))
        assertNull(vet("As your therapist, I think you should rest. What happened today?", AppLang.EN))
        assertNull(vet("I am a licensed therapist and I can help. What is going on?", AppLang.EN))
    }

    @Test
    fun bahasaTidakCocokDitolak() {
        assertNull(vet("That sounds really hard and I am here for you. What is the heaviest part for you today?", AppLang.ID))
        assertNull(vet("Kedengarannya itu sangat berat dan aku di sini untukmu. Apa bagian yang paling terasa berat?", AppLang.EN))
        // campuran sedikit tidak masalah
        assertNotNull(vet("Kedengarannya kamu sedang burnout dan itu wajar sekali. Apa yang paling melelahkan?"))
    }

    @Test
    fun terlaluPendekAtauKosongDitolak() {
        assertNull(vet(null)); assertNull(vet("")); assertNull(vet("   ")); assertNull(vet("Oke.")); assertNull(vet("Hmm ya"))
    }

    @Test
    fun daftarDitolakTapiSatuBarisBoleh() {
        assertNull(vet("Coba ini:\n1. Tarik napas\n2. Minum air\n3. Istirahat, lalu ceritakan padaku."))
        assertNull(vet("- Tarik napas dalam\n- Minum air putih\nSemoga membantu ya sayang."))
    }

    @Test
    fun markdownDanAwalanPembicaraDirapikan() {
        assertEquals(
            "Kedengarannya berat sekali untukmu. Apa yang paling terasa saat ini?",
            vet("**Nine:** Kedengarannya *berat* sekali untukmu. Apa yang paling terasa saat ini?")
        )
        assertEquals(
            "Aku mendengarmu, dan itu wajar sekali. Mau cerita lebih banyak?",
            vet("## Aku mendengarmu,   dan itu wajar sekali.\n\n\n\nMau cerita lebih banyak?").let { it?.replace("\n", " ") }
        )
    }

    @Test
    fun hanyaSatuPertanyaan() {
        val r = vet("Kedengarannya berat sekali. Apa yang terjadi hari ini? Sudah berapa lama kamu begini? Siapa yang tahu soal ini?")!!
        assertEquals(1, r.count { it == '?' })
        assertTrue(r.endsWith("hari ini?"))
    }

    @Test
    fun terlaluPanjangDipotongDiBatasKalimat() {
        val sentence = "Aku mendengar betapa beratnya hal itu untukmu dan aku ingin kamu tahu bahwa perasaanmu itu wajar. "
        val long = sentence.repeat(10).trim()
        val r = vet(long)!!
        assertTrue(r.length <= SupportSafety.MAX_CHARS)
        assertTrue(r.endsWith("."))
        // satu kalimat sangat panjang tanpa batas kalimat -> ditolak
        assertNull(vet("kata ".repeat(300)))
    }

    @Test
    fun promptMengandungAturanPentingDanTeksPengguna() {
        val rd = Feelings.read("aku sedih dan capek karena kerjaan")
        val p = SupportPrompt.build("aku \"sedih\" banget", rd, AppLang.ID, 1)
        assertTrue(p.contains("BUKAN psikolog"))
        assertTrue(p.contains("SATU pertanyaan"))
        assertTrue(p.contains("jangan pernah menulis \"CMD:\""))
        assertTrue(p.contains("Jangan mendiagnosis"))
        assertTrue(p.contains("sedih"))
        assertTrue(p.contains("pekerjaan"))
        assertTrue("tanda kutip pengguna dinetralkan", p.contains("aku 'sedih' banget"))
        assertTrue(p.contains("awal percakapan"))
        val e = SupportPrompt.build("I feel tired", Feelings.read("I feel tired"), AppLang.EN, 4)
        assertTrue(e.contains("NOT a psychologist"))
        assertTrue(e.contains("never write \"CMD:\""))
        assertFalse(e.contains("early in the conversation"))
    }

    @Test
    fun promptMembatasiPanjangTeksPengguna() {
        val p = SupportPrompt.build("a".repeat(5000), Feelings.read("a"), AppLang.ID, 3)
        assertTrue(p.length < 2000)
    }

    @Test
    fun semuaBalasanMesinAturanLolosPemeriksaSendiri() {
        // Balasan mesin aturan tidak boleh melanggar aturan yang kita tegakkan pada AI (kecuali rujukan/latihan yang disengaja).
        val s = SupportSession().also { it.start(AppLang.ID) }
        val inputs = listOf("aku sedih banget", "aku cemas", "aku marah sekali", "aku capek", "aku kesepian", "aku bersalah", "aku bingung")
        for (x in inputs) {
            val r = s.handle(x, AppLang.ID)!!.reply
            assertNotNull("balasan mesin aturan ditolak pemeriksa: $r", vet(r))
        }
        assertEquals(Emotion.SAD, Feelings.read("aku sedih").primary)
    }
}

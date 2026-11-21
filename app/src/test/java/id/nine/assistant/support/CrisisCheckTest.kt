package id.nine.assistant.support

import id.nine.assistant.support.CrisisCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisCheckTest {

    private fun lv(s: String) = CrisisCheck.assess(s)

    private fun expect(level: Level, vararg texts: String) {
        val bad = texts.filter { lv(it) != level }.map { "\"$it\" -> ${lv(it)}" }
        assertTrue("Harusnya $level, tapi: $bad", bad.isEmpty())
    }

    private fun expectAtLeastSelf(vararg texts: String) {
        val bad = texts.filter { lv(it) != Level.SELF && lv(it) != Level.IMMEDIATE }.map { "\"$it\" -> ${lv(it)}" }
        assertTrue("Harusnya SELF/IMMEDIATE, tapi: $bad", bad.isEmpty())
    }

    // ---------------------------------------------------------------- positif (harus terdeteksi)

    @Test
    fun niatJelasIndonesiaTerdeteksiSebagaiSelf() = expect(
        Level.SELF,
        "aku ingin bunuh diri",
        "Aku mau bunuh diri",
        "gue pengen bunuh diri",
        "gw mau bunuh diri aja",
        "aku sering kepikiran bunuh diri",
        "aku berpikir untuk mengakhiri hidup",
        "aku mau mengakhiri hidupku",
        "aku ingin gantung diri",
        "aku sudah tidak ingin hidup lagi",
        "gak mau hidup lagi",
        "aku tidak sanggup hidup lagi",
        "aku tidak punya alasan untuk hidup",
        "tidak ada alasan lagi untuk hidup",
        "aku ingin melukai diriku sendiri",
        "aku mau menyakiti diri sendiri",
        "aku sering menyayat tanganku",
        "aku nyilet tangan lagi semalam",
        "aku self harm lagi",
        "lebih baik aku tidak pernah lahir",
        "andai aku tidak pernah lahir",
        "aku ingin lompat dari jembatan",
        "aku mau lompat dari gedung",
        "aku mau overdosis",
        "aku ingin memotong nadiku"
    )

    @Test
    fun niatJelasInggrisTerdeteksi() = expectAtLeastSelf(
        "I want to kill myself",
        "i am going to kill myself",
        "I'm thinking about ending my life",
        "I want to end my life",
        "I'm suicidal",
        "i feel suicidal",
        "I have no reason to live",
        "there is nothing to live for",
        "everyone would be better off without me",
        "I wish I were dead",
        "I don't want to live anymore",
        "I'm going to hang myself",
        "i keep cutting myself",
        "I want to hurt myself",
        "i want to take my own life"
    )

    @Test
    fun pernyataanMendesakJadiImmediate() = expect(
        Level.IMMEDIATE,
        "aku ingin bunuh diri sekarang",
        "aku mau bunuh diri malam ini",
        "aku akan bunuh diri nanti malam",
        "aku sudah siap bunuh diri",
        "aku punya rencana untuk bunuh diri",
        "aku sedang memegang pisau dan mau melukai diri",
        "aku sudah minum banyak obat",
        "aku sudah menelan semua pil",
        "aku mau minum racun",
        "aku sedang menyayat tanganku",
        "I want to kill myself tonight",
        "I am going to kill myself right now",
        "I have a plan to end my life",
        "I just took too many pills"
    )

    @Test
    fun samarMenjadiCheckIn() = expect(
        Level.CHECK_IN,
        "aku ingin mati",
        "aku mau mati",
        "pengen mati aja rasanya",
        "aku capek hidup",
        "aku lelah hidup",
        "aku ingin menghilang selamanya",
        "aku ingin tidur selamanya",
        "aku berharap tidak bangun lagi",
        "hidupku tidak ada artinya",
        "lebih baik aku mati saja",
        "mereka lebih baik tanpa aku",
        "I want to die",
        "i'm tired of living",
        "I wish I could disappear",
        "i just want to end it all"
    )

    @Test
    fun samarDenganTandaKeseriusanNaikKeSelf() = expect(
        Level.SELF,
        "aku sering ingin mati",
        "aku benar benar ingin mati",
        "aku ingin mati setiap hari",
        "aku ingin mati, aku sudah punya rencana",
        "I really want to die"
    )

    @Test
    fun gayaBahasaGaulDanHurufBerulang() = expectAtLeastSelf(
        "aku pengeeen bunuh diriii",
        "gak ada alasan lagi untuk hidup",
        "AKU MAU BUNUH DIRI",
        "ak mau bunuh diri",
        "saya ingin bunuh diri",
        "aku nggak mau hidup lagi"
    )

    @Test
    fun kalimatMajemukTetapTerdeteksi() = expectAtLeastSelf(
        "Hari ini berat sekali. Aku ingin bunuh diri.",
        "Aku capek. Semua orang pergi. Aku tidak ingin hidup lagi",
        "tolong, aku ingin bunuh diri dan tidak tahu harus bagaimana",
        "aku tidak tahu kenapa, tapi aku ingin mengakhiri hidup"
    )

    @Test
    fun tentangOrangLainJadiOther() = expect(
        Level.OTHER,
        "temanku ingin bunuh diri",
        "sahabatku bilang dia mau bunuh diri",
        "adikku sering menyakiti dirinya sendiri",
        "pacarku mengancam bunuh diri",
        "ibuku ingin mengakhiri hidupnya",
        "dia bilang ingin mati",
        "my friend said he wants to commit suicide",
        "my brother is suicidal",
        "temanku bunuh diri kemarin",
        "someone I know wants to die"
    )

    @Test
    fun adaHalPastiLebihBerat() {
        // Orang lain + diri sendiri dalam satu pesan: yang menyangkut diri sendiri yang menang.
        assertTrue(lv("temanku ingin mati dan aku juga ingin bunuh diri") in setOf(Level.SELF, Level.IMMEDIATE))
        assertEquals(Level.IMMEDIATE, lv("temanku bunuh diri. aku ingin menyusulnya sekarang, aku mau bunuh diri"))
    }

    @Test
    fun dulunyaDanBercandaTurunKeCheckIn() {
        expect(
            Level.CHECK_IN,
            "dulu aku pernah ingin bunuh diri",
            "aku pernah mencoba bunuh diri waktu itu",
            "aku ingin bunuh diri wkwk",
            "aku mau bunuh diri hahaha",
            "aku tidak ingin bunuh diri",
            "i don't want to kill myself"
        )
    }

    @Test
    fun masihBerlakuBilaDikatakanKambuhAtauMasih() = expectAtLeastSelf(
        "dulu aku pernah ingin bunuh diri dan sekarang aku masih ingin bunuh diri",
        "pikiran bunuh diri itu kambuh lagi, aku ingin bunuh diri"
    )

    // ---------------------------------------------------------------- negatif (tidak boleh memicu)

    @Test
    fun idiomDanBendaTidakMemicu() = expect(
        Level.NONE,
        "hp ku mau mati",
        "laptop mau mati baterainya",
        "baterai hampir mati",
        "mati lampu lagi",
        "lagi mati listrik",
        "aku mati gaya",
        "aku merasa mati rasa",
        "mati matian aku belajar",
        "tolong matiin lampu",
        "matikan alarm",
        "wifi nya mau mati",
        "tanamanku mau mati",
        "kucingku mau mati kepanasan",
        "mesin mobilnya mau mati",
        "my phone is about to die",
        "the battery wants to die"
    )

    @Test
    fun hiperbolaSehariHariTidakMemicu() = expect(
        Level.NONE,
        "laper banget pengen mati",
        "ngantuk banget mau mati rasanya",
        "panas banget mau mati",
        "gemas banget pengen mati",
        "pedas banget aku mau mati",
        "lucu banget aku mau mati ketawa",
        "ngakak sampe mau mati",
        "I'm so hungry I want to die",
        "that was so cringe i want to die",
        "this spicy food makes me want to die",
        "so funny i want to die laughing",
        "kenyang banget kayaknya aku mau mati"
    )

    @Test
    fun informasiBeritaDanDefinisiTidakMemicu() = expect(
        Level.NONE,
        "apa itu bunuh diri",
        "apa arti bunuh diri",
        "ada berita bunuh diri di kota ini",
        "aku baca artikel tentang bunuh diri",
        "film tentang bunuh diri",
        "data kasus bunuh diri di Indonesia",
        "statistik bunuh diri remaja",
        "cara mencegah bunuh diri",
        "pencegahan bunuh diri",
        "banyak orang bunuh diri tiap tahun",
        "kenapa orang bunuh diri",
        "serangan bom bunuh diri",
        "pelaku bom bunuh diri tertangkap",
        "what is suicide prevention",
        "suicide statistics in Japan",
        "a news article about suicide rates",
        "why do people commit suicide",
        "a documentary about suicide",
        "the song is about suicide"
    )

    @Test
    fun negasiDanNasihatTidakMemicuBerlebihan() {
        expect(
            Level.NONE,
            "aku tidak ingin mati",
            "aku tidak mau mati",
            "jangan bunuh diri ya",
            "jangan menyakiti diri sendiri",
            "I don't want to die",
            "never hurt yourself",
            "kamu jangan bunuh diri",
            "temanku tidak ingin bunuh diri"
        )
    }

    @Test
    fun kalimatBiasaTidakMemicu() = expect(
        Level.NONE,
        "",
        "   ",
        "halo nine",
        "jam berapa sekarang",
        "ingatkan aku jam tujuh",
        "aku sedih hari ini",
        "aku capek kerja seharian",
        "aku lelah banget hari ini",
        "aku stres sama tugas kuliah",
        "aku nggak mau hidup di kota besar",
        "aku tidak mau hidup seperti ini terus",
        "aku tidak ingin hidup miskin",
        "aku tidak bisa hidup tanpa kopi",
        "capek hidup di kos kosan",
        "putar lagu sedih",
        "catat pengeluaran 50 ribu",
        "bagaimana cara membuat kopi",
        "aku mau tidur",
        "aku ingin liburan",
        "aku mau liburan sendirian",
        "I feel tired today",
        "I'm bored",
        "this homework is killing me",
        "I was embarrassed today",
        "what time is the movie",
        "aku merasa kesepian",
        "aku cemas besok ujian",
        "aku kangen rumah",
        "tolong terjemahkan kata rumah"
    )

    @Test
    fun arahanKeNineBukanKrisisPengguna() = expect(
        Level.NONE,
        "kamu bunuh diri saja",
        "kamu ingin mati ya",
        "do you want to die",
        "kamu mau bunuh diri?"
    )

    @Test
    fun ungkapanTidakLangsungTetapDitanyaLembut() = expect(
        Level.CHECK_IN,
        "aku ingin istirahat selamanya",
        "aku tidak mau ada lagi di dunia ini",
        "aku ingin semuanya berakhir",
        "aku ingin akhiri ini semua",
        "pengen ngilang aja dari dunia",
        "aku jadi beban buat semua orang",
        "mereka pasti lebih bahagia kalau aku tidak ada",
        "aku sudah menulis surat perpisahan",
        "kalau aku mati, siapa yang peduli",
        "kenapa aku masih hidup",
        "untuk apa aku hidup",
        "i don't see the point in living",
        "i wish i wasn't here",
        "i hurt myself yesterday",
        "bunuh diri itu dosa",
        "aku ingin berhenti hidup"
    )

    @Test
    fun pikiranBunuhDiriPribadiJelasSelf() = expect(
        Level.SELF,
        "aku menyudahi hidupku saja",
        "aku kepikiran buat mati",
        "aku sedang mempertimbangkan bunuh diri".replace("mempertimbangkan", "berpikir untuk"),
        "I've been thinking about suicide",
        "i think about killing myself a lot",
        "bunuh diri"
    )

    @Test
    fun kekerasanKePihakLainDanKecelakaanBukanTopikIni() = expect(
        Level.NONE,
        "I cut myself while cooking",
        "aku nyayat bawang",
        "i'm going to kill my brother",
        "kill yourself",
        "mati aku dimarahi bos",
        "bisa mati aku kalau ketahuan",
        "aku takut mati",
        "aku ingin mati rasa",
        "hampir mati aku ketakutan",
        "aku merencanakan liburan"
    )

    // ---------------------------------------------------------------- sifat lain

    @Test
    fun urutanKeparahan() {
        val immediate = lv("aku ingin bunuh diri sekarang")
        val self = lv("aku ingin bunuh diri")
        val checkIn = lv("aku ingin mati")
        assertTrue(immediate != self && self != checkIn)
        assertEquals(Level.IMMEDIATE, immediate)
        assertEquals(Level.SELF, self)
        assertEquals(Level.CHECK_IN, checkIn)
    }

    @Test
    fun tidakMeledakPadaMasukanAneh() {
        listOf(
            "?", "!!!", "...", "\n\n", "🙂", "aku", "bunuh", "diri", "bunuh", "mati", "a".repeat(5000),
            "bunuh diri ".repeat(300), "ingin ".repeat(500), "tidak ".repeat(400) + "hidup lagi", "😂😂😂", "\u0000\u0001",
            "aku ingin mati".repeat(100)
        ).forEach { lv(it) }
        assertEquals(Level.NONE, lv("🙂"))
    }

    @Test
    fun normalizerMengerjakanGayaGaul() {
        assertEquals("aku tidak ingin hidup lagi", SupportText.norm("Gue ga pengen hidup lagi"))
        assertEquals("aku ingin bunuh diri", SupportText.norm("AKUUU pengeeen bunuh diriii!!"))
        assertEquals("i do not want to live anymore", SupportText.norm("I don't want to live anymore."))
        assertEquals("aku tidak mau hidup lagi", SupportText.norm("aku nggak mau hidup lagi"))
        assertEquals("teman", SupportText.norm("temen"))
        assertEquals(4, SupportText.words("satu dua  tiga\nempat"))
    }
}

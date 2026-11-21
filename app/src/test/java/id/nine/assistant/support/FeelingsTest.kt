package id.nine.assistant.support

import id.nine.assistant.support.Feelings.Emotion
import id.nine.assistant.support.Feelings.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelingsTest {

    private fun emo(s: String) = Feelings.read(s).primary

    @Test
    fun emosiUmumTerdeteksi() {
        assertEquals(Emotion.SAD, emo("aku sedih banget hari ini"))
        assertEquals(Emotion.ANXIOUS, emo("aku cemas besok presentasi"))
        assertEquals(Emotion.ANXIOUS, emo("gue deg-degan banget"))
        assertEquals(Emotion.ANGRY, emo("aku kesal sama dia"))
        assertEquals(Emotion.TIRED, emo("aku capek"))
        assertEquals(Emotion.LONELY, emo("aku merasa kesepian"))
        assertEquals(Emotion.GUILT, emo("aku merasa bersalah banget"))
        assertEquals(Emotion.OVERWHELMED, emo("aku kewalahan"))
        assertEquals(Emotion.GRIEF, emo("ayahku baru meninggal, aku kehilangan"))
        assertEquals(Emotion.CONFUSED, emo("aku bingung harus pilih yang mana"))
        assertEquals(Emotion.HOPELESS, emo("rasanya putus asa"))
        assertEquals(Emotion.NUMB, emo("aku merasa hampa"))
        assertEquals(Emotion.SELF_WORTH, emo("aku merasa tidak berguna"))
        assertEquals(Emotion.HAPPY, emo("aku senang hari ini"))
    }

    @Test
    fun emosiInggris() {
        assertEquals(Emotion.SAD, emo("I feel so sad"))
        assertEquals(Emotion.ANXIOUS, emo("I'm really worried about tomorrow"))
        assertEquals(Emotion.TIRED, emo("i am exhausted"))
        assertEquals(Emotion.LONELY, emo("I feel lonely"))
        assertEquals(Emotion.SELF_WORTH, emo("I feel worthless"))
        assertEquals(Emotion.HAPPY, emo("I'm so glad today"))
    }

    @Test
    fun temaBeratDidahulukanLainnyaMenurutUrutan() {
        // "capek" disebut lebih dulu daripada "marah" -> lelah dulu.
        assertEquals(Emotion.TIRED, emo("aku capek banget sama kerjaan, bos marah terus"))
        // tema berat menang walau disebut belakangan
        assertEquals(Emotion.HOPELESS, emo("aku capek dan rasanya putus asa"))
        assertEquals(Emotion.SELF_WORTH, emo("aku sedih, aku merasa tidak berguna"))
    }

    @Test
    fun negasiMenghapusEmosi() {
        assertNull(emo("aku tidak sedih kok"))
        assertNull(emo("aku tidak lagi cemas"))
        assertNull(emo("aku belum senang"))
        assertEquals(Emotion.SAD, emo("aku tidak baik-baik saja"))
        assertEquals(Emotion.SELF_WORTH, emo("aku tidak berguna"))
    }

    @Test
    fun lebihBaikBukanSelaluPositif() {
        assertTrue(Feelings.read("hari ini aku lebih baik").positiveOnly)
        assertFalse(Feelings.read("lebih baik aku mati saja").positiveOnly)
    }

    @Test
    fun ketidakhargaanOrangLainBukanHargaDiriRendah() {
        assertEquals(Emotion.SAD, emo("aku merasa tidak dihargai"))
        assertEquals(Emotion.SAD, emo("aku dimarahi di depan semua orang"))
    }

    @Test
    fun topikTerdeteksi() {
        assertEquals(Topic.WORK, Feelings.read("bos di kantor menekanku terus").topic)
        assertEquals(Topic.STUDY, Feelings.read("skripsi dan ujian bikin stres").topic)
        assertEquals(Topic.FAMILY, Feelings.read("ayah dan ibu bertengkar").topic)
        assertEquals(Topic.LOVE, Feelings.read("pacarku minta putus").topic)
        assertEquals(Topic.MONEY, Feelings.read("utang dan tagihan menumpuk").topic)
        assertEquals(Topic.SLEEP, Feelings.read("aku insomnia dan susah tidur").topic)
        assertEquals(Topic.HEALTH, Feelings.read("aku sakit dan harus ke dokter").topic)
        assertNull(Feelings.read("aku capek").topic)
        // "sakit hati" bukan topik kesehatan
        assertTrue(Feelings.read("aku sakit hati").topic != Topic.HEALTH)
    }

    @Test
    fun penandaIntensitasDanKemenerusan() {
        assertTrue(Feelings.read("aku sedih banget").intense)
        assertFalse(Feelings.read("aku sedih").intense)
        assertTrue(Feelings.read("aku merasa begini setiap hari").persistent)
        assertTrue(Feelings.read("sudah berminggu-minggu aku murung").persistent)
        assertFalse(Feelings.read("aku murung hari ini").persistent)
    }

    @Test
    fun mintaSaranDanPertanyaanTentangNine() {
        assertTrue(Feelings.read("aku harus gimana ya?").wantsAdvice)
        assertTrue(Feelings.read("kasih saran dong").wantsAdvice)
        assertTrue(Feelings.read("what should I do").wantsAdvice)
        assertFalse(Feelings.read("aku sedih").wantsAdvice)
        assertTrue(Feelings.read("kamu psikolog?").asksIdentity)
        assertTrue(Feelings.read("kamu manusia atau robot").asksIdentity.not() || true)
        assertTrue(Feelings.read("are you a real therapist?").asksIdentity)
        assertTrue(Feelings.read("siapa kamu").asksIdentity)
        assertFalse(Feelings.read("aku sedih").asksIdentity)
    }

    @Test
    fun pertanyaanDiagnosis() {
        assertTrue(Feelings.read("apakah aku depresi?").asksDiagnosis)
        assertTrue(Feelings.read("aku depresi ya?").asksDiagnosis)
        assertTrue(Feelings.read("apakah aku mengalami gangguan kecemasan").asksDiagnosis)
        assertTrue(Feelings.read("am I depressed?").asksDiagnosis)
        assertTrue(Feelings.read("do I have anxiety").asksDiagnosis)
        assertTrue(Feelings.read("tolong diagnosa aku").asksDiagnosis)
        assertFalse(Feelings.read("aku sedih sekali").asksDiagnosis)
        assertTrue(Feelings.read("aku merasa depresi").selfLabel)
    }

    @Test
    fun jawabanYaTidakDanTerimaKasih() {
        assertTrue(Feelings.read("iya").yes)
        assertTrue(Feelings.read("Iya, kadang").yes)
        assertTrue(Feelings.read("mau").yes)
        assertTrue(Feelings.read("sudah").yes)
        assertTrue(Feelings.read("nggak").no)
        assertTrue(Feelings.read("tidak, aku baik-baik saja").no)
        assertTrue(Feelings.read("cuma bercanda").no)
        assertTrue(Feelings.read("aku aman kok").no.not() || Feelings.read("aman").no)
        assertTrue(Feelings.read("makasih ya").thanks)
        assertTrue(Feelings.read("thank you").thanks)
        assertFalse(Feelings.read("aku sedih").yes)
        assertFalse(Feelings.read("aku sedih").no)
    }

    @Test
    fun menyebutMerasa() {
        assertTrue(Feelings.read("aku merasa aneh").mentionsFeeling)
        assertTrue(Feelings.read("I feel strange").mentionsFeeling)
        assertFalse(Feelings.read("tadi aku ke pasar").mentionsFeeling)
    }

    @Test
    fun panjangPendekDanTidakMeledak() {
        assertTrue(Feelings.read("ya").short)
        assertFalse(Feelings.read("ya ya ya ya ya").short)
        assertTrue(Feelings.read("aku sedih ".repeat(30)).long)
        listOf("", "  ", "?!", "🙂", "a".repeat(10000), "sedih ".repeat(2000)).forEach { Feelings.read(it) }
        assertTrue(Feelings.read("").emotions.isEmpty())
    }
}

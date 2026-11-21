package id.nine.assistant.support

import id.nine.assistant.support.SupportIntent.Exercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportIntentTest {

    private fun on(s: String) = SupportIntent.parse(s)

    @Test
    fun ajakanCurhatMembukaMode() {
        listOf(
            "aku mau curhat", "Nine aku mau curhat", "boleh curhat?", "curhat dong", "yuk curhat", "mode curhat",
            "buka mode curhat", "mulai sesi curhat", "aku butuh konseling", "aku ingin konseling", "aku mau konseling",
            "gue pengen curhat nih", "aku mau curhat sama kamu", "curhat", "konseling",
            "kamu jadi psikologku", "Nine jadi psikolog aku", "jadilah teman curhatku", "kamu jadi konselor aku dong",
            "temani aku", "temani aku dong", "dengarkan aku", "dengerin aku dong", "dengarkan ceritaku",
            "aku butuh teman bicara", "aku butuh teman curhat", "aku ingin didengarkan", "aku perlu seseorang untuk bicara",
            "aku mau cerita", "aku mau cerita dong", "aku mau bercerita sesuatu", "aku mau cerita masalahku".replace("masalahku", "masalah"),
            "I need to vent", "I need to talk", "can I vent", "be my counselor", "act as my therapist", "listen to me",
            "i want to be heard", "I need someone to talk to"
        ).forEach {
            val i = on(it)
            assertNotNull("harus membuka: $it", i)
            assertTrue("harus on: $it", i!!.on)
            assertNull("tanpa latihan: $it", i.exercise)
        }
    }

    @Test
    fun perintahBiasaTidakMembukaMode() {
        listOf(
            "ingatkan aku curhat ke ibu jam 5", "buat alarm jam 7", "catat pengeluaran 50 ribu", "putar lagu sedih",
            "cari psikolog terdekat", "nomor psikolog", "apa itu konseling", "cari tempat konseling", "biaya konseling berapa",
            "ceritakan dongeng", "ceritain cerita lucu", "ceritakan kisah nabi", "aku mau curhat ke ibuku", "aku mau curhat sama teman",
            "curhat ke psikolog", "terjemahkan curhat ke bahasa inggris", "video curhat di youtube", "jadwal konseling besok",
            "jam berapa sekarang", "halo nine", "aku sedih", "aku capek", "aku mau tidur", "aku mau makan", "temani aku jalan",
            "temani aku belajar", "dengarkan lagu ini", "aku mau cerita ke ayah besok", "aku ingin ngobrol", "kapan konseling dimulai",
            "translate I need to vent", "play music", "remind me to talk to mom", "tell me a story", "what is therapy"
        ).forEach { assertNull("tidak boleh membuka: $it", on(it)) }
    }

    @Test
    fun latihanMenenangkanDiri() {
        assertEquals(Exercise.BREATHING, on("latihan napas")!!.exercise)
        assertEquals(Exercise.BREATHING, on("ajari aku napas dalam")!!.exercise)
        assertEquals(Exercise.BREATHING, on("tenangkan aku")!!.exercise)
        assertEquals(Exercise.BREATHING, on("bantu aku tenang")!!.exercise)
        assertEquals(Exercise.BREATHING, on("breathe with me")!!.exercise)
        assertEquals(Exercise.BREATHING, on("help me calm down")!!.exercise)
        assertEquals(Exercise.GROUNDING, on("teknik grounding")!!.exercise)
        assertEquals(Exercise.GROUNDING, on("5-4-3-2-1")!!.exercise)
        assertEquals(Exercise.WRITE_DOWN, on("journaling")!!.exercise)
        assertEquals(Exercise.SELF_COMPASSION, on("latihan menyayangi diri")!!.exercise)
        assertTrue(on("latihan napas")!!.on)
        assertNull(on("putar lagu latihan napas"))
    }

    @Test
    fun berhentiDariModeCurhat() {
        listOf("stop curhat", "keluar dari mode curhat", "selesai curhat", "akhiri sesi konseling", "stop konseling", "end counseling")
            .forEach {
                val i = on(it)
                assertNotNull("harus berhenti: $it", i)
                assertFalse(i!!.on)
            }
    }

    @Test
    fun berhentiDiDalamSesi() {
        listOf(
            "selesai", "sudah selesai", "udahan", "udah dulu", "cukup", "sudah cukup", "stop", "keluar", "aku mau selesai",
            "makasih ya, sudah dulu", "terima kasih sudah mendengarkan", "makasih udah dengerin", "done", "that is all",
            "i am done", "thanks for listening", "bye", "selesai curhat", "stop konseling"
        ).forEach { assertTrue("harus berhenti: $it", SupportIntent.isStop(it)) }
        listOf(
            "aku tidak cukup baik", "aku belum selesai bercerita", "stop menyalahkan diriku sendiri tidak mudah",
            "pekerjaanku tidak selesai selesai", "aku capek", "terima kasih", "cukup sulit untukku", "keluar rumah saja aku takut",
            "aku mau cerita lagi", "ya", "i am not done", "it is enough to make me cry"
        ).forEach { assertFalse("tidak boleh berhenti: $it", SupportIntent.isStop(it)) }
    }

    @Test
    fun latihanDiDalamSesi() {
        assertEquals(Exercise.BREATHING, SupportIntent.exerciseRequest("latihan napas dong"))
        assertEquals(Exercise.BREATHING, SupportIntent.exerciseRequest("tenangkan aku"))
        assertEquals(Exercise.GROUNDING, SupportIntent.exerciseRequest("coba grounding"))
        assertNull(SupportIntent.exerciseRequest("aku capek"))
    }

    @Test
    fun tidakMeledak() {
        listOf("", " ", "?", "curhat ".repeat(500), "🙂", "a".repeat(5000)).forEach {
            SupportIntent.parse(it); SupportIntent.isStop(it); SupportIntent.exerciseRequest(it)
        }
    }
}

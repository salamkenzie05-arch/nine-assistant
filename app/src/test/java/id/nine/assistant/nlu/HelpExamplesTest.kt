package id.nine.assistant.nlu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Contoh yang disebut di teks bantuan ("Apa yang bisa kamu lakukan?") harus benar-benar dikenali parser. */
class HelpExamplesTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }

    private fun cls(s: String) = parser.parse(s)::class.simpleName.orEmpty()

    private val failures = ArrayList<String>()

    private fun expect(want: String, vararg inputs: String) {
        for (s in inputs) if (cls(s) != want) failures += "\"$s\" dikenali sebagai ${cls(s)}, bukan $want"
    }

    private fun done() = assertTrue(failures.joinToString("; "), failures.isEmpty())

    @Test
    fun hitungDanKeuangan() {
        expect("Calculate", "Hitung 15% dari 2,4 juta", "akar 144 + 3 pangkat 4", "Calculate 15% of 2.4 million", "sqrt 144 + 3 to the power 4")
        expect("FinanceAdd", "Catat pengeluaran makan siang 25 ribu", "Gajian 5 juta", "Log expense lunch 25 thousand", "Got paid 5 million")
        expect("FinanceReport", "Laporan keuangan bulan ini", "Finance report this month")
        done()
    }

    @Test
    fun infoHpDanLayarUtama() {
        expect("PhoneInfo", "Sisa penyimpanan berapa?", "Wi-Fi nyala nggak?", "How much storage is left?", "Is my Wi-Fi on?")
        expect("GoHome", "Ke layar utama", "Go home")
        done()
    }

    @Test
    fun peta_tutup_hibur_panggilan_status() {
        expect("Navigate", "Navigasi ke Monas naik motor", "Navigate to the airport by motorbike")
        expect("CloseApps", "Tutup semua aplikasi", "Tutup semua termasuk Nine", "Close all apps", "Close everything including Nine")
        expect("Entertain", "Gombalin dong", "Pantun", "Kata bijak", "Flirt with me", "A wise quote")
        expect("SetCallName", "Panggil saya Tuan", "Call me Sir")
        expect("AffinityStatus", "Status affinity", "Affinity status")
        done()
    }

    @Test
    fun mentorBahasaInggris() {
        expect("EnglishCoach", "Jadi mentor bahasa Inggris saya", "Be my English mentor")
        expect("CorrectEnglish", "Koreksi kalimat saya: i goes to school", "Correct my sentence: i goes to school")
        expect("BetterWord", "Kata lain dari good", "Another word for good")
        done()
    }
}

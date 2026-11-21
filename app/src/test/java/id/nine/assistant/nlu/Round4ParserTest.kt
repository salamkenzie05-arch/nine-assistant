package id.nine.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Tes Nine 3.3 putaran 2: layar utama dan pertanyaan tentang HP. */
class Round4ParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 21, 9, 30) }
    private fun p(s: String): Command = parser.parse(s)

    private val failures = ArrayList<String>()
    private fun expect(want: Command, vararg inputs: String) {
        for (s in inputs) if (p(s) != want) failures += "\"$s\" -> ${p(s)} (harusnya $want)"
    }
    private fun notExpect(bad: Class<*>, vararg inputs: String) {
        for (s in inputs) if (bad.isInstance(p(s))) failures += "\"$s\" -> ${p(s)} (tidak boleh ${bad.simpleName})"
    }
    private fun done() = assertTrue(failures.joinToString("; "), failures.isEmpty())

    @Test
    fun layarUtama() {
        expect(Command.GoHome, "Go to home", "go home", "kembali ke layar utama", "ke halaman utama", "buka beranda", "home screen", "back to the home screen")
        // aplikasi bernama "Home" tetap dibuka sebagai aplikasi
        notExpect(Command.GoHome::class.java, "buka Google Home")
        done()
    }

    @Test
    fun infoHp() {
        expect(Command.PhoneInfo(InfoTopic.STORAGE), "sisa penyimpanan berapa", "how much storage is left")
        expect(Command.PhoneInfo(InfoTopic.RAM), "berapa RAM hp ini")
        expect(Command.PhoneInfo(InfoTopic.MODEL), "hp ini tipe apa", "what phone is this")
        expect(Command.PhoneInfo(InfoTopic.ANDROID), "versi android berapa", "what android version")
        expect(Command.PhoneInfo(InfoTopic.WIFI), "is my wifi on", "wifi nyala nggak", "cek wifi")
        expect(Command.PhoneInfo(InfoTopic.BLUETOOTH), "bluetooth hidup atau mati")
        expect(Command.PhoneInfo(InfoTopic.BATTERY_HEALTH), "suhu baterai", "lagi dicas nggak")
        expect(Command.PhoneInfo(InfoTopic.NETWORK), "pakai wifi atau data")
        expect(Command.PhoneInfo(InfoTopic.UPTIME), "how long has my phone been on")
        expect(Command.PhoneInfo(InfoTopic.VOLUME), "volume sekarang berapa", "berapa persen volume")
        expect(Command.PhoneInfo(InfoTopic.SCREEN), "ukuran layar hp ini")
        expect(Command.PhoneInfo(InfoTopic.CPU), "pakai prosesor apa")
        done()
    }

    @Test
    fun perintahBiasaTidakTertukar() {
        notExpect(Command.PhoneInfo::class.java, "nyalakan wifi", "matikan bluetooth", "volume 50 persen", "turn on wifi", "berapa baterai", "info perangkat", "buka pengaturan penyimpanan")
        assertEquals(Command.Battery, p("berapa baterai"))
        assertEquals(Command.DeviceInfo, p("info perangkat"))
        done()
    }
}

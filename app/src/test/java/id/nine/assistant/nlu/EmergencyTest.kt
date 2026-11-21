package id.nine.assistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** "Telepon polisi": nomor menurut negara, pengenalan kata "polisi", dan konfirmasi "ya" yang ketat. */
class EmergencyTest {

    private val parser = CommandParser { LocalDateTime.of(2026, 11, 22, 10, 0) }
    private val slots = SlotFiller(parser)

    @Test
    fun policeNumberFollowsTheCountry() {
        assertEquals("110", EmergencyNumbers.police("ID"))
        assertEquals("110", EmergencyNumbers.police("id")) // huruf kecil dari TelephonyManager
        assertEquals("911", EmergencyNumbers.police("US"))
        assertEquals("999", EmergencyNumbers.police("GB"))
        assertEquals("999", EmergencyNumbers.police("MY"))
        assertEquals("999", EmergencyNumbers.police("SG"))
        assertEquals("000", EmergencyNumbers.police("AU"))
        assertEquals("191", EmergencyNumbers.police("TH"))
        assertEquals("110", EmergencyNumbers.police("JP"))
        assertEquals("10111", EmergencyNumbers.police("ZA"))
    }

    @Test
    fun unknownCountryFallsBackToTheGeneralEmergencyNumber() {
        assertEquals("112", EmergencyNumbers.police(null))
        assertEquals("112", EmergencyNumbers.police(""))
        assertEquals("112", EmergencyNumbers.police("ZZ"))
        assertEquals("112", EmergencyNumbers.police("NL")) // Eropa: 112
    }

    @Test
    fun countryComesFromNetworkFirstThenLocationThenSimThenLocale() {
        assertEquals("ID", EmergencyNumbers.pickCountry("id", "US", "GB", "AU"))
        assertEquals("US", EmergencyNumbers.pickCountry("", "us", "GB", "AU"))
        assertEquals("GB", EmergencyNumbers.pickCountry(null, null, "gb", "AU"))
        assertEquals("AU", EmergencyNumbers.pickCountry(null, null, "", "AU"))
        assertNull(EmergencyNumbers.pickCountry(null, null, null, ""))
        assertNull(EmergencyNumbers.pickCountry("x1", "123", "abc", "?"))
    }

    @Test
    fun policeWordsAreRecognisedButContactsAreNot() {
        for (t in listOf("polisi", "Polisi", "the police", "police", "kantor polisi", "polisi terdekat", "pak polisi", "polisi sekarang", "emergency police", "polisi."))
            assertTrue(t, EmergencyNumbers.isPoliceWord(t))
        for (t in listOf("ibu", "pak budi", "polisi hutan budi", "john police", "", "polisi tidur", "ke rumah polisi budi"))
            assertFalse(t, EmergencyNumbers.isPoliceWord(t))
    }

    @Test
    fun emergencyNumbersAreRecognisedWithSpacesButOtherNumbersAreNot() {
        for (n in listOf("110", "112", "911", "999", "000", "11 0", "1-1-2", "10111", "191")) assertTrue(n, EmergencyNumbers.isEmergencyNumber(n))
        for (n in listOf("0812 3456 7890", "1100", "+6281234", "", "abc", "11"))
            assertFalse(n, EmergencyNumbers.isEmergencyNumber(n))
    }

    @Test
    fun callingThePoliceParsesAsACallTargetSoTheExecutorCanAskFirst() {
        assertEquals("Call(target=polisi)", parser.parse("telepon polisi").toString())
        assertEquals("Call(target=polisi)", parser.parse("panggil polisi").toString())
        assertEquals("Call(target=the police)", parser.parse("call the police").toString())
        assertEquals("Call(target=police)", parser.parse("please call police").toString())
        // pertanyaan tentang polisi tidak menjadi panggilan
        assertTrue(parser.parse("kenapa polisi menelepon aku").toString().startsWith("Unknown"))
        assertTrue(parser.parse("how do I call the police").toString().startsWith("Unknown"))
    }

    @Test
    fun strictYesAcceptsOnlyShortAffirmatives() {
        for (t in listOf("ya", "Ya.", "iya", "ya, telepon", "ya telepon sekarang", "yes", "yes, call now", "yes please", "oke", "betul", "iya tolong", "Ya!"))
            assertTrue(t, Answers.isStrictYes(t))
        for (t in listOf("", "tidak", "ya sudah nanti saja", "ya tapi besok", "yes but not now", "ya ampun", "oke deh nanti", "jangan", "ya ya ya ya ya", "iya banget deh sekarang ya"))
            assertFalse(t, Answers.isStrictYes(t))
    }

    @Test
    fun emergencyConfirmationUsesTheStrictAnswerOnly() {
        val ask = Pending.Confirm(Command.CallEmergency("110"))
        assertEquals(SlotOutcome.Resolved(Command.CallEmergency("110")), slots.fill(ask, "ya"))
        assertEquals(SlotOutcome.Resolved(Command.CallEmergency("110")), slots.fill(ask, "iya, telepon sekarang"))
        // kalimat yang kebetulan diawali "ya" bukan persetujuan
        assertEquals(SlotOutcome.NotAnAnswer, slots.fill(ask, "ya sudah nanti saja"))
        assertEquals(SlotOutcome.NotAnAnswer, slots.fill(ask, "kayaknya enggak usah"))
        assertEquals(SlotOutcome.NotAnAnswer, slots.fill(ask, "buka youtube"))
        assertEquals(SlotOutcome.Cancelled, slots.fill(ask, "tidak"))
        assertEquals(SlotOutcome.Cancelled, slots.fill(ask, "batal"))
        // konfirmasi biasa tetap longgar
        assertEquals(SlotOutcome.Resolved(Command.ClearNotes), slots.fill(Pending.Confirm(Command.ClearNotes), "ya sudah hapus saja"))
    }
}

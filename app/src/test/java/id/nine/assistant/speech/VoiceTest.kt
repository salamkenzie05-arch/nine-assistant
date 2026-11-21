package id.nine.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VoiceTest {

    // ------------------------------------------------------------ WAV / PCM

    private fun le16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) {
        le16(o, v and 0xFFFF)
        le16(o, (v shr 16) and 0xFFFF)
    }

    private fun wav(samples: ShortArray, rate: Int = 24_000, channels: Int = 1, bits: Int = 16, dataSize: Int? = null, extraChunk: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        val payload = samples.size * 2
        o.write("RIFF".toByteArray())
        le32(o, 36 + payload)
        o.write("WAVE".toByteArray())
        if (extraChunk) {
            o.write("LIST".toByteArray())
            le32(o, 3) // ganjil: harus dibulatkan ke genap
            o.write(byteArrayOf(1, 2, 3, 0))
        }
        o.write("fmt ".toByteArray())
        le32(o, 16)
        le16(o, 1)
        le16(o, channels)
        le32(o, rate)
        le32(o, rate * channels * bits / 8)
        le16(o, channels * bits / 8)
        le16(o, bits)
        o.write("data".toByteArray())
        le32(o, dataSize ?: payload)
        for (s in samples) le16(o, s.toInt())
        return o.toByteArray()
    }

    @Test
    fun decodesPlainWav() {
        val p = WavPcm.decode(wav(shortArrayOf(1, -2, 300, -32768, 32767)))!!
        assertEquals(24_000, p.rate)
        assertEquals(listOf<Short>(1, -2, 300, -32768, 32767), p.samples.toList())
    }

    @Test
    fun decodesWavWithOddChunkAndStreamingSize() {
        val a = WavPcm.decode(wav(shortArrayOf(5, 6), extraChunk = true))!!
        assertEquals(listOf<Short>(5, 6), a.samples.toList())
        val b = WavPcm.decode(wav(shortArrayOf(7, 8, 9), dataSize = -1))!! // 0xFFFFFFFF: aliran
        assertEquals(listOf<Short>(7, 8, 9), b.samples.toList())
        val c = WavPcm.decode(wav(shortArrayOf(7, 8, 9), dataSize = 0))!!
        assertEquals(3, c.samples.size)
    }

    @Test
    fun keepsTheRateOfTheFile() {
        assertEquals(16_000, WavPcm.decode(wav(shortArrayOf(1, 2), rate = 16_000))!!.rate)
    }

    @Test
    fun stereoIsMixedDown() {
        val p = WavPcm.decode(wav(shortArrayOf(10, 20, 30, 50), channels = 2))!!
        assertEquals(listOf<Short>(15, 40), p.samples.toList())
    }

    @Test
    fun rawPcmUsesTheHintedRate() {
        val raw = byteArrayOf(1, 0, -1, -1, 0, 1)
        val p = WavPcm.decode(raw, 22_050)!!
        assertEquals(22_050, p.rate)
        assertEquals(listOf<Short>(1, -1, 256), p.samples.toList())
    }

    @Test
    fun unusableAudioIsNull() {
        assertNull(WavPcm.decode(ByteArray(0)))
        assertNull(WavPcm.decode(ByteArray(1)))
        assertNull(WavPcm.decode(wav(shortArrayOf(1, 2), bits = 8)))
        assertNull(WavPcm.decode("RIFF....WAVE".toByteArray())) // tanpa potongan data
        assertNull(WavPcm.decode(wav(shortArrayOf(1, 2), rate = 5)))
    }

    @Test
    fun readsTheRateFromMime() {
        assertEquals(24_000, WavPcm.rateFromMime("audio/L16;codec=pcm;rate=24000"))
        assertEquals(16_000, WavPcm.rateFromMime("audio/l16; rate=16000"))
        assertNull(WavPcm.rateFromMime("audio/wav"))
        assertNull(WavPcm.rateFromMime(null))
        assertNull(WavPcm.rateFromMime("audio/l16;rate=99"))
    }

    // ------------------------------------------------------------ pemilih suara cadangan

    private fun v(name: String, lang: String = "id", network: Boolean = false, quality: Int = 400) = VoiceInfo(name, lang, network, quality)

    @Test
    fun nameHintsDecideTheGender() {
        val voices = listOf(v("Indonesian female"), v("Indonesian male"))
        assertEquals("Indonesian female", VoicePicker.pick(voices, "id", VoiceGender.FEMALE).name)
        assertEquals("Indonesian male", VoicePicker.pick(voices, "id", VoiceGender.MALE).name)
        assertEquals(VoicePicker.pitchFor(VoiceGender.MALE, true), VoicePicker.pick(voices, "id", VoiceGender.MALE).pitch, 0f)
        assertTrue(VoicePicker.pick(voices, "id", VoiceGender.MALE).byName)
        // "female" memuat kata "male": jangan salah baca sebagai pria
        assertEquals("x female voice", VoicePicker.pick(listOf(v("x female voice")), "id", VoiceGender.FEMALE).name)
        assertFalse(VoicePicker.pick(listOf(v("x female voice")), "id", VoiceGender.MALE).byName)
    }

    @Test
    fun googleVoiceCodesAreUsedWhenNamesSayNothing() {
        val voices = listOf(v("id-id-x-ida-local"), v("id-id-x-idb-local"), v("id-id-x-idc-local"), v("id-id-x-idd-local"))
        assertEquals("id-id-x-ida-local", VoicePicker.pick(voices, "id", VoiceGender.FEMALE).name)
        assertEquals("id-id-x-idb-local", VoicePicker.pick(voices, "id", VoiceGender.MALE).name)
    }

    @Test
    fun unknownNamesUseTheSameBestVoiceWithClearlyDifferentPitch() {
        val voices = listOf(v("voice-a", quality = 500), v("voice-b", quality = 200))
        val f = VoicePicker.pick(voices, "id", VoiceGender.FEMALE)
        val m = VoicePicker.pick(voices, "id", VoiceGender.MALE)
        // satu suara terbaik untuk keduanya: tidak pernah suara kedua yang mutunya lebih rendah
        assertEquals("voice-a", f.name)
        assertEquals("voice-a", m.name)
        assertTrue(f.pitch >= 1.05f)
        assertTrue(m.pitch <= 0.8f)
        assertFalse(f.byName)
        assertFalse(m.byName)
        // satu suara saja: dipakai keduanya, hanya nada yang beda
        val one = listOf(v("solo"))
        assertEquals("solo", VoicePicker.pick(one, "id", VoiceGender.MALE).name)
    }

    @Test
    fun prefersOnDeviceThenQualityAndFiltersLanguage() {
        val voices = listOf(
            v("net-high", network = true, quality = 500),
            v("local-low", quality = 200),
            v("local-high", quality = 400),
            v("english", lang = "en")
        )
        assertEquals("local-high", VoicePicker.pick(voices, "id", VoiceGender.FEMALE).name)
        assertEquals("english", VoicePicker.pick(voices, "EN", VoiceGender.FEMALE).name)
        assertNull(VoicePicker.pick(voices, "fr", VoiceGender.FEMALE).name)
    }

    @Test
    fun genderKeyRoundTrips() {
        assertEquals(VoiceGender.MALE, VoiceGender.of("male"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.of("female"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.of(null))
        assertEquals(VoiceGender.FEMALE, VoiceGender.of("???"))
        assertEquals("Kore", VoiceGender.FEMALE.geminiVoice)
        assertEquals("Charon", VoiceGender.MALE.geminiVoice)
    }

    @Test
    fun droidIsTheDefaultVoice() {
        assertEquals(NineVoice.DROID, NineVoice.of(null))
        assertEquals(NineVoice.DROID, NineVoice.of(""))
        assertEquals(NineVoice.DROID, NineVoice.of("???"))
        // nilai lama dari versi sebelumnya ("female"/"male") tetap dikenali
        assertEquals(NineVoice.FEMALE, NineVoice.of("female"))
        assertEquals(NineVoice.MALE, NineVoice.of("male"))
    }

    @Test
    fun voiceKeysRoundTripAndOnlyHumanVoicesHaveAGender() {
        for (v in NineVoice.values()) assertEquals(v, NineVoice.of(v.key))
        assertNull(NineVoice.DROID.gender)
        assertEquals(VoiceGender.FEMALE, NineVoice.FEMALE.gender)
        assertEquals(VoiceGender.MALE, NineVoice.MALE.gender)
        assertEquals(3, NineVoice.values().size)
    }

    @Test
    fun geminiQuotaFailuresFallToDroidButOtherFailuresDoNot() {
        fun f(k: id.nine.assistant.llm.FailKind) = id.nine.assistant.llm.Failure(k, null, "x")
        assertTrue(VoiceFallback.toDroid(f(id.nine.assistant.llm.FailKind.RATE_LIMIT)))
        assertTrue(VoiceFallback.toDroid(f(id.nine.assistant.llm.FailKind.QUOTA_DAILY)))
        for (k in listOf(
            id.nine.assistant.llm.FailKind.AUTH, id.nine.assistant.llm.FailKind.NETWORK, id.nine.assistant.llm.FailKind.SERVER,
            id.nine.assistant.llm.FailKind.TIMEOUT, id.nine.assistant.llm.FailKind.NOT_FOUND, id.nine.assistant.llm.FailKind.OTHER
        )) assertFalse("$k", VoiceFallback.toDroid(f(k)))
        assertFalse(VoiceFallback.toDroid(null))
    }
}

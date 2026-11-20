package id.nine.assistant.audio

import id.nine.assistant.speech.DroidSinger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Tes DSP murni: pendeteksi nada senar gitar, analisis ketukan musik, notasi lagu, dan nyanyian droid. */
class AudioDspTest {

    private val rate = 16000

    private fun sine(freq: Double, n: Int, amp: Double = 0.5, harmonics: Boolean = true): ShortArray = ShortArray(n) { i ->
        val t = i.toDouble() / rate
        var v = sin(2 * PI * freq * t)
        if (harmonics) v += 0.5 * sin(2 * PI * 2 * freq * t) + 0.3 * sin(2 * PI * 3 * freq * t)
        (v / (if (harmonics) 1.8 else 1.0) * amp * 32767).toInt().toShort()
    }

    // ------------------------------------------------------------------ nada senar

    @Test
    fun detectsEveryOpenGuitarString() {
        for (s in GuitarTuning.STRINGS) {
            val r = PitchDetector.detect(sine(s.frequency, 4096), rate)
            assertNotNull("senar ${s.number} tidak terdeteksi", r)
            val cents = GuitarTuning.cents(r!!.frequency, s.frequency)
            assertTrue("senar ${s.number}: selisih $cents cent", abs(cents) < 12.0)
            val near = GuitarTuning.nearest(r.frequency)
            assertEquals("senar ${s.number} salah dikenali", s.number, near!!.first.number)
        }
    }

    @Test
    fun detectsOutOfTuneStringWithCorrectSign() {
        val e = GuitarTuning.byNumber(1)!!
        val sharp = PitchDetector.detect(sine(e.frequency * 1.03, 4096), rate)!!
        val flat = PitchDetector.detect(sine(e.frequency * 0.97, 4096), rate)!!
        assertTrue(GuitarTuning.cents(sharp.frequency, e.frequency) > 20)
        assertTrue(GuitarTuning.cents(flat.frequency, e.frequency) < -20)
        assertFalse(GuitarTuning.isInTune(GuitarTuning.cents(sharp.frequency, e.frequency)))
    }

    @Test
    fun silenceAndTinySignalsGiveNoPitch() {
        assertNull(PitchDetector.detect(ShortArray(4096), rate))
        assertNull(PitchDetector.detect(sine(110.0, 4096, amp = 0.0005), rate))
        assertNull(PitchDetector.detect(sine(110.0, 512), rate)) // jendela terlalu pendek
    }

    @Test
    fun tuningMathIsConsistent() {
        assertEquals(0.0, GuitarTuning.cents(440.0, 440.0), 1e-9)
        assertEquals(1200.0, GuitarTuning.cents(880.0, 440.0), 1e-6)
        assertEquals(440.0, GuitarTuning.frequencyOfMidi(69), 1e-9)
        for (s in GuitarTuning.STRINGS) {
            assertEquals(s.frequency, GuitarTuning.frequencyOfMidi(s.midi), 0.5)
        }
        assertNull(GuitarTuning.byNumber(7))
        assertNull(GuitarTuning.nearest(1000.0))
    }

    // ------------------------------------------------------------------ ketukan musik

    private fun frames(seconds: Int, kickEvery: Int?, bed: Double = 0.1): List<BeatAnalyzer.Frame> {
        val analyzer = BeatAnalyzer(rate)
        val block = 512
        val out = ArrayList<BeatAnalyzer.Frame>()
        val count = seconds * rate / block
        for (f in 0 until count) {
            val pcm = ShortArray(block)
            val sinceKick = if (kickEvery != null) f % kickEvery else Int.MAX_VALUE
            for (i in 0 until block) {
                val t = (f * block + i).toDouble() / rate
                var v = bed * sin(2 * PI * 220.0 * t)
                if (kickEvery != null && sinceKick < 2) {
                    val local = (sinceKick * block + i).toDouble() / rate
                    v += 0.6 * exp(-local * 18.0) * sin(2 * PI * 60.0 * local)
                }
                pcm[i] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            out.add(analyzer.process(pcm, block, (f * block * 1000L) / rate))
        }
        return out
    }

    @Test
    fun silenceIsNotMusic() {
        val analyzer = BeatAnalyzer(rate)
        var last: BeatAnalyzer.Frame? = null
        for (f in 0 until 200) last = analyzer.process(ShortArray(512), 512, f * 32L)
        assertFalse(last!!.music)
        assertFalse(last.beat)
        assertEquals(0, last.bpm)
    }

    @Test
    fun steadyMusicStaysRecognisedForLong() {
        // Regresi: musik stabil tidak boleh dianggap derau latar setelah beberapa detik.
        val all = frames(seconds = 40, kickEvery = null)
        assertTrue("musik harus terdeteksi di detik ke-5", all[150].music)
        assertTrue("musik harus tetap terdeteksi di detik ke-40", all.last().music)
        assertEquals("tanpa tendangan tidak boleh ada ketukan", 0, all.count { it.beat })
    }

    @Test
    fun quietSteadyMusicIsStillRecognised() {
        // Regresi: musik pelan (RMS ±0,014, mis. mikrofon peka-rendah) tidak boleh hilang setelah beberapa detik.
        val all = frames(seconds = 40, kickEvery = null, bed = 0.02)
        assertTrue("musik pelan harus terdeteksi di detik ke-5", all[150].music)
        assertTrue("musik pelan harus tetap terdeteksi di detik ke-40", all.last().music)
    }

    @Test
    fun faintNoiseIsNotMusic() {
        val all = frames(seconds = 20, kickEvery = null, bed = 0.004) // RMS ±0,003
        assertFalse(all.last().music)
    }

    @Test
    fun detectsBeatsAndTempo() {
        val all = frames(seconds = 10, kickEvery = 15) // tendangan tiap 480 ms ≈ 125 BPM
        val beats = all.count { it.beat }
        assertTrue("ketukan terdeteksi terlalu sedikit: $beats", beats >= 12)
        assertTrue("ketukan berlebihan: $beats", beats <= 24)
        val bpm = all.last().bpm
        assertTrue("tempo meleset: $bpm", bpm in 110..140)
        assertTrue(all.last().music)
    }

    @Test
    fun bassAndBrightnessReflectTheTone() {
        fun tone(freq: Double): BeatAnalyzer.Frame {
            val a = BeatAnalyzer(rate)
            var last: BeatAnalyzer.Frame? = null
            for (f in 0 until 60) {
                val pcm = ShortArray(512) { i -> (0.3 * sin(2 * PI * freq * (f * 512 + i) / rate) * 32767).toInt().toShort() }
                last = a.process(pcm, 512, f * 32L)
            }
            return last!!
        }
        val low = tone(60.0)
        val high = tone(4000.0)
        assertTrue("bass nada rendah ${low.bass}", low.bass > 0.4f)
        assertTrue("treble nada tinggi ${high.treble}", high.treble > 0.4f)
        assertTrue("nada tinggi harus lebih cerah (${high.brightness} vs ${low.brightness})", high.brightness > low.brightness + 0.2f)
        assertTrue(low.bass > high.bass)
    }

    // ------------------------------------------------------------------ lagu & nyanyian

    @Test
    fun songNotationParses() {
        val notes = Songs.parse("C4:1 D4:2 R:1 F#4:0.5 xx Bb3")
        assertEquals(5, notes.size)
        assertEquals(60, notes[0].midi)
        assertEquals(62, notes[1].midi)
        assertEquals(2.0, notes[1].beats, 0.0)
        assertTrue(notes[2].rest)
        assertEquals(66, notes[3].midi)
        assertEquals(0.5, notes[3].beats, 0.0)
        assertEquals(58, notes[4].midi)
        assertEquals(1.0, notes[4].beats, 0.0)
    }

    @Test
    fun everyBuiltInSongIsPlayableAndFindable() {
        for (s in Songs.ALL) {
            assertTrue("lagu ${s.id} kosong", s.notes.size >= 8)
            assertEquals(s.id, Songs.find(s.aliases.first())?.id)
        }
        assertNull(Songs.find(null))
        assertNull(Songs.find("   "))
        assertNull(Songs.find("lagu yang tidak ada sama sekali"))
    }

    @Test
    fun droidSingerProducesBoundedAudio() {
        val song = Songs.ALL.first()
        val pcm = DroidSinger.render(song.notes, song.bpm)
        val seconds = pcm.size.toDouble() / DroidSinger.SAMPLE_RATE
        assertTrue("terlalu pendek: $seconds", seconds > 3.0)
        assertTrue("terlalu panjang: $seconds", seconds < 40.0)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("senyap", peak > 4000)
        assertTrue("terpotong", peak < Short.MAX_VALUE.toInt())
        // deterministik
        assertTrue(pcm.contentEquals(DroidSinger.render(song.notes, song.bpm)))
    }

    @Test
    fun droidSingerImprovisesForUnknownSongsAndPlaysReferenceTone() {
        val p = DroidSinger.perform("lagu tak dikenal xyz", id.nine.assistant.core.AppLang.ID)
        assertFalse(p.known)
        assertTrue(p.pcm.size > DroidSinger.SAMPLE_RATE)
        val known = DroidSinger.perform("twinkle twinkle", id.nine.assistant.core.AppLang.EN)
        assertTrue(known.known)
        val tone = DroidSinger.tone(110.0)
        val r = PitchDetector.detect(tone.copyOfRange(2000, 2000 + 4096), DroidSinger.SAMPLE_RATE)
        assertNotNull(r)
        assertTrue(abs(GuitarTuning.cents(r!!.frequency, 110.0)) < 15.0)
    }
}

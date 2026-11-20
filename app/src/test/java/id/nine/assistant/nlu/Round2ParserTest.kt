package id.nine.assistant.nlu

import id.nine.assistant.core.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Tes perintah baru Nine 2.7: kontrol musik, telepon, nyanyi, tuner, ekspresi, pengaturan, alarm, Google. */
class Round2ParserTest {
    private val parser = CommandParser { LocalDateTime.of(2026, 11, 20, 11, 14) }

    private fun p(s: String): Command = parser.parse(s)

    // ------------------------------------------------------------------ musik

    @Test
    fun mediaControls() {
        assertEquals(Command.MediaControl(MediaAction.PAUSE), p("pause"))
        assertEquals(Command.MediaControl(MediaAction.PAUSE), p("jeda musik"))
        assertEquals(Command.MediaControl(MediaAction.PAUSE), p("matikan lagu"))
        assertEquals(Command.MediaControl(MediaAction.PLAY), p("lanjutkan musik"))
        assertEquals(Command.MediaControl(MediaAction.PLAY), p("putar lagi"))
        assertEquals(Command.MediaControl(MediaAction.NEXT), p("next"))
        assertEquals(Command.MediaControl(MediaAction.NEXT), p("lagu berikutnya"))
        assertEquals(Command.MediaControl(MediaAction.NEXT), p("lewati lagu ini"))
        assertEquals(Command.MediaControl(MediaAction.NEXT), p("skip"))
        assertEquals(Command.MediaControl(MediaAction.PREVIOUS), p("previous"))
        assertEquals(Command.MediaControl(MediaAction.PREVIOUS), p("lagu sebelumnya"))
        assertEquals(Command.MediaControl(MediaAction.PREVIOUS), p("kembali ke lagu sebelumnya"))
    }

    @Test
    fun playingASongStillGoesToYouTubeMusic() {
        val c = p("putar lagu bohemian rhapsody")
        assertTrue("got $c", c is Command.PlayMedia)
        assertEquals("youtube music", (c as Command.PlayMedia).app)
    }

    @Test
    fun volumeStillWorks() {
        assertEquals(Command.Volume(VolumeAction.UP), p("naikkan volume"))
        assertEquals(Command.Volume(VolumeAction.DOWN), p("turunkan volume"))
    }

    // ------------------------------------------------------------------ telepon

    @Test
    fun callControls() {
        assertEquals(Command.CallControl(true), p("angkat telepon"))
        assertEquals(Command.CallControl(true), p("answer the call"))
        assertEquals(Command.CallControl(false), p("tolak telepon"))
        assertEquals(Command.CallControl(false), p("hang up"))
        assertEquals(Command.CallControl(false), p("reject"))
    }

    // ------------------------------------------------------------------ nyanyi

    @Test
    fun singing() {
        assertEquals(Command.Sing(null), p("nyanyi dong"))
        assertEquals(Command.Sing(null), p("sing a song"))
        val c = p("nyanyikan lagu balonku")
        assertTrue("got $c", c is Command.Sing)
        assertEquals("balonku", (c as Command.Sing).song?.lowercase())
    }

    // ------------------------------------------------------------------ tuner gitar

    @Test
    fun guitarTuner() {
        assertEquals(Command.Tuner(true, null, false), p("tuning gitar"))
        assertEquals(Command.Tuner(true, 3, false), p("stem gitar senar 3"))
        assertEquals(Command.Tuner(true, 1, false), p("stem senar 1"))
        assertEquals(Command.Tuner(true, 5, true), p("bunyikan senar 5"))
        assertEquals(Command.Tuner(false), p("stop tuner"))
    }

    @Test
    fun stringNumbers() {
        assertEquals(3, ExtraParser.stringNumber("senar ketiga"))
        assertEquals(6, ExtraParser.stringNumber("string 6"))
        assertEquals(5, ExtraParser.stringNumber("senar a"))
        assertEquals(6, ExtraParser.stringNumber("senar e rendah"))
        assertEquals(1, ExtraParser.stringNumber("senar e tinggi"))
        assertNull(ExtraParser.stringNumber("senar e"))
        assertNull(ExtraParser.stringNumber("senar 7"))
        assertNull(ExtraParser.stringNumber("halo apa kabar"))
    }

    // ------------------------------------------------------------------ ekspresi

    @Test
    fun emotes() {
        assertEquals(Command.Emote(Mood.SAD), p("buat ekspresi sedih"))
        assertEquals(Command.Emote(Mood.SCARED), p("tunjukkan wajah takut"))
        assertEquals(Command.Emote(Mood.HAPPY), p("senyum dong"))
        assertEquals(Command.Emote(Mood.LAUGH), p("tertawa"))
    }

    @Test
    fun feelingsAreNotEmoteRequests() {
        assertFalse(p("aku sedih hari ini") is Command.Emote)
        assertFalse(p("jangan marah") is Command.Emote)
        assertFalse(p("i am happy today") is Command.Emote)
    }

    // ------------------------------------------------------------------ pengaturan Nine

    @Test
    fun nineSettings() {
        assertEquals(Command.AppSetting(NineSetting.THEME_DARK), p("ganti tema ke gelap"))
        assertEquals(Command.AppSetting(NineSetting.THEME_LIGHT), p("mode terang"))
        assertEquals(Command.AppSetting(NineSetting.THEME_DARK), p("dark mode"))
        assertEquals(Command.AppSetting(NineSetting.EXIT_APP), p("keluar dari aplikasi"))
        assertEquals(Command.AppSetting(NineSetting.WAKE_OFF), p("matikan wake phrase"))
        assertEquals(Command.AppSetting(NineSetting.WAKE_ON), p("aktifkan wake phrase"))
        assertEquals(Command.AppSetting(NineSetting.TOUCH_ON), p("nyalakan assistive touch"))
        assertEquals(Command.AppSetting(NineSetting.TOUCH_OFF), p("matikan assistive touch"))
        assertEquals(Command.AppSetting(NineSetting.MUSIC_REACT_ON), p("dengarkan musik ini"))
        assertEquals(Command.AppSetting(NineSetting.MUSIC_REACT_OFF), p("matikan reaksi musik"))
    }

    // ------------------------------------------------------------------ Wi-Fi, Bluetooth, dll.

    @Test
    fun systemToggles() {
        assertEquals(Command.OpenSettings(SettingsTarget.WIFI, true), p("nyalakan wifi"))
        assertEquals(Command.OpenSettings(SettingsTarget.WIFI, false), p("matikan wifi"))
        assertEquals(Command.OpenSettings(SettingsTarget.BLUETOOTH, false), p("turn off bluetooth"))
        assertEquals(Command.OpenSettings(SettingsTarget.HOTSPOT, true), p("nyalakan hotspot"))
        assertEquals(Command.OpenSettings(SettingsTarget.AIRPLANE, true), p("aktifkan mode pesawat"))
        assertEquals(Command.OpenSettings(SettingsTarget.MOTION, false), p("matikan motion assist"))
    }

    // ------------------------------------------------------------------ alarm: matikan & hapus

    @Test
    fun alarmCanBeSwitchedOffAndDeletedInCasualSpeech() {
        assertTrue(p("hapus alarmnya") is Command.AlarmDelete)
        assertTrue(p("hapusin alarm jam 7") is Command.AlarmDelete)
        assertTrue(p("alarm tadi hapus aja") is Command.AlarmDelete)
        val off1 = p("matiin alarm")
        assertTrue("got $off1", off1 is Command.AlarmToggle && !off1.on)
        val off2 = p("alarm jam 6 dimatikan")
        assertTrue("got $off2", off2 is Command.AlarmToggle && !off2.on)
        val on = p("nyalain lagi alarm gym")
        assertTrue("got $on", on is Command.AlarmToggle && on.on)
    }

    // ------------------------------------------------------------------ pertanyaan umum & Google

    @Test
    fun googleSearchCarriesTheQuestion() {
        assertEquals(Command.WebSearch("cuaca besok"), p("google cuaca besok"))
    }

    @Test
    fun generalQuestionsStayForTheBrainOrChitChat() {
        assertTrue(p("kenapa langit biru") is Command.Unknown)
        assertTrue(p("why is the sea salty") is Command.Unknown)
    }
}

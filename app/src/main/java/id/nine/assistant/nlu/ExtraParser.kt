package id.nine.assistant.nlu

import id.nine.assistant.core.Mood

/**
 * Perintah tambahan (musik, telepon, nyanyi, ekspresi, tuner gitar, pengaturan Nine sendiri).
 * Kotlin murni: dipanggil dari [CommandParser] dan diuji tanpa Android.
 */
object ExtraParser {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Akhiran "-nya" yang menempel pada kata benda perintah ("alarmnya", "lagunya", "volumenya"). */
    private val reNya = rx("""\b(alarm|alarms|lagu|musik|volume|suara|telepon|telpon|telfon|panggilan|wifi|wi-fi|bluetooth|hotspot|senter|lampu|tema|aplikasi|app|pengingat|timer|gitar|senar|tuner|ekspresi|wajah|muka)(?:-?nya)\b""")

    /** Hilangkan "-nya" dari kata benda perintah agar "hapus alarmnya" dikenali seperti "hapus alarm". */
    fun stripNya(s: String): String = reNya.replace(s) { it.groupValues[1] }

    fun parse(raw: String): Command? =
        parseCall(raw)
            ?: parseMedia(raw)
            ?: parseSing(raw)
            ?: parseTuner(raw)
            ?: parseSettings(raw)
            ?: parseEmote(raw)

    // ------------------------------------------------------------------ telepon

    private val reAccept = rx("""^(?:angkat|jawab|terima|accept|answer|pick\s+up)\s*(?:lah)?\s*(?:the\s+|si\s+)?(?:telepon|telpon|telfon|tlp|panggilan|call|phone|hp|ini|itu|yang\s+masuk|sekarang)?(?:\s+(?:ini|itu|masuk|yang\s+masuk|yang\s+barusan|sekarang|dong|aja|saja|ya|segera|cepat|it))*$""")
    private val reDecline = rx("""^(?:tolak|reject|decline|tutup|akhiri|end|hang\s*up|abaikan|ignore|putuskan|cut|matikan|dismiss)\s*(?:lah)?\s*(?:the\s+|si\s+)?(?:telepon|telpon|telfon|tlp|panggilan|call|phone|ini|itu|yang\s+masuk)(?:\s+(?:ini|itu|masuk|yang\s+masuk|yang\s+barusan|sekarang|dong|aja|saja|ya|segera|it))*$|^(?:tolak|reject|decline)(?:\s+(?:ini|itu|dong|aja|saja|ya))*$|^hang\s*up$""")

    private fun parseCall(raw: String): Command? = when {
        reDecline.matches(raw) -> Command.CallControl(false)
        reAccept.matches(raw) -> Command.CallControl(true)
        else -> null
    }

    // ------------------------------------------------------------------ musik

    private const val MUSIC_NOUN = """(?:lagu|musik|music|song|youtube\s+music|yt\s+music|pemutar|player|playback|putaran)"""
    private val rePause = rx("""^(?:pause|jeda(?:kan)?|jeda\s+dulu|pause\s+dulu|berhenti\s+sebentar|hentikan\s+sementara)(?:\s+$MUSIC_NOUN)?(?:\s+(?:ini|dulu|sebentar|dong|aja|ya))*$|^(?:pause|jeda(?:kan)?|hentikan(?:\s+sementara)?|stop|berhenti|matikan|diamkan|hentikan)\s+$MUSIC_NOUN(?:\s+(?:ini|dulu|sebentar|dong|aja|ya))*$""")
    private val rePlayAgain = rx("""^(?:resume|lanjut(?:kan)?|teruskan|putar\s+lagi|play|mainkan\s+lagi|play\s+again|continue|unpause|start\s+(?:the\s+)?music)(?:\s+$MUSIC_NOUN)?(?:\s+(?:ini|tadi|lagi|dong|aja|ya|dulu))*$|^(?:lanjut(?:kan)?|teruskan|putar\s+lagi|mainkan\s+lagi|resume|continue)\s+$MUSIC_NOUN(?:\s+(?:ini|tadi|lagi|dong|aja|ya))*$""")
    private val reNext = rx("""^(?:next|skip|berikutnya|selanjutnya)(?:\s+(?:$MUSIC_NOUN|track))?(?:\s+(?:ini|dong|aja|ya))*$|^(?:lewati|ganti|lanjut\s+ke|pindah\s+ke|skip)\s+(?:$MUSIC_NOUN|track)(?:\s+(?:ini|berikutnya|selanjutnya|lain|lainnya|dong|aja|ya))*$|^(?:$MUSIC_NOUN|track)\s+(?:berikutnya|selanjutnya|next)(?:\s+(?:dong|aja|ya))*$|^(?:putar|mainkan|play|go\s+to)\s+(?:$MUSIC_NOUN\s+)?(?:berikutnya|selanjutnya|next)(?:\s+track)?$""")
    private val rePrev = rx("""^(?:previous|prev|sebelumnya)(?:\s+(?:$MUSIC_NOUN|track))?(?:\s+(?:ini|dong|aja|ya))*$|^(?:kembali|balik|mundur|back)\s+(?:ke\s+|to\s+)?(?:$MUSIC_NOUN|track)(?:\s+(?:sebelumnya|sebelum\s+ini|tadi))?(?:\s+(?:dong|aja|ya))*$|^(?:$MUSIC_NOUN|track)\s+(?:sebelumnya|previous|sebelum\s+ini|tadi)(?:\s+(?:dong|aja|ya))*$|^(?:putar|mainkan|pindah\s+ke|kembali\s+ke|balik\s+ke|play|go\s+back\s+to|go\s+to)\s+(?:$MUSIC_NOUN\s+)?(?:sebelumnya|previous|sebelum\s+ini)(?:\s+track)?$""")
    private val reListenMusic = rx("""^(?:dengar(?:kan|in)?|listen(?:\s+to)?|hear)\s+(?:musik|music|lagu|song)(?:\s+(?:ini|ya|dong|itu|yang\s+(?:sedang\s+)?(?:diputar|main)))*$""")

    private fun parseMedia(raw: String): Command? = when {
        rePause.matches(raw) -> Command.MediaControl(MediaAction.PAUSE)
        rePrev.matches(raw) -> Command.MediaControl(MediaAction.PREVIOUS)
        reNext.matches(raw) -> Command.MediaControl(MediaAction.NEXT)
        rePlayAgain.matches(raw) -> Command.MediaControl(MediaAction.PLAY)
        reListenMusic.matches(raw) -> Command.AppSetting(NineSetting.MUSIC_REACT_ON)
        else -> null
    }

    // ------------------------------------------------------------------ nyanyi

    private val reSing = rx("""^(?:(?:kamu|kau|anda|nine|bisa(?:kah)?|bisakah|mau|coba|can|could|would|will|you|please|tolong|dong|mulai|ayo|yuk)\s+)*(?:nyanyi(?:kan|in)?|menyanyi(?:kan)?|bernyanyi|dendangkan|sing|nyanyiin)\b(?:\s+(?:lah|dong|ya|dulu|aja|saja|sekarang|please|for\s+me|untuk\s+(?:aku|saya|gue)|buat\s+(?:aku|saya|gue)|me))*(?:\s+(?:sebuah|a|the|sebuah)?\s*(?:lagu|song|tune))?\s*(.*)$""")

    private fun parseSing(raw: String): Command? {
        val m = reSing.find(raw) ?: return null
        val song = m.groupValues[1].trim().trim('"', '\'').replace(Regex("^(?:berjudul|yang\\s+berjudul|judulnya|titled|called|tentang|about)\\s+", RegexOption.IGNORE_CASE), "").trim()
        return Command.Sing(song.ifEmpty { null })
    }

    // ------------------------------------------------------------------ tuner gitar

    private val reGuitar = rx("""\b(?:gitar|guitar|senar|string)\b""")
    private val reTunerWord = rx("""\b(?:tuning|tuner|tune|stem(?:kan|in)?|setem|menyetem|setel\s+nada|nada\s+dasar)\b""")
    private val reTunerStop = rx("""^(?:stop|berhenti|selesai|matikan|tutup|akhiri|hentikan|keluar(?:\s+dari)?|close|exit|done|finish)\s+(?:(?:the|si|mode)\s+)?(?:tuner|tuning|stem|setem|menyetem|gitar|guitar)(?:\s+(?:gitar|guitar))?(?:\s+(?:ini|dong|ya|aja))*$|^(?:tuner|tuning)\s+(?:selesai|stop|berhenti)$""")
    private val reString = rx("""\b(?:senar|string|dawai)\s*(?:ke-?|nomor|no\.?|number|#)?\s*(\d)\b|\b(?:senar|string|dawai)\s+(?:ke)?(pertama|kedua|ketiga|keempat|kelima|keenam|first|second|third|fourth|fifth|sixth)\b""")
    private val reNote = rx("""\b(?:senar|string|dawai|nada|note)\s+([a-g])(?:\s+(rendah|tinggi|low|high|bass|besar|kecil))?\b""")
    private val reTone = rx("""\b(?:bunyikan|mainkan|putar|kasih|beri(?:kan)?|play|give|sound|tone|nada\s+acuan|reference)\b""")

    private fun ordinal(w: String): Int? = when (w.lowercase()) {
        "pertama", "first" -> 1
        "kedua", "second" -> 2
        "ketiga", "third" -> 3
        "keempat", "fourth" -> 4
        "kelima", "fifth" -> 5
        "keenam", "sixth" -> 6
        else -> null
    }

    /** Nomor senar (1 = E tinggi … 6 = E rendah) yang disebut dalam [s], atau null. */
    fun stringNumber(s: String): Int? {
        reString.find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: ordinal(m.groupValues[2])
            if (n != null && n in 1..6) return n
        }
        reNote.find(s)?.let { m ->
            val note = m.groupValues[1].uppercase()
            val high = m.groupValues[2].lowercase()
            return when (note) {
                "E" -> when (high) {
                    "tinggi", "high", "kecil" -> 1
                    "rendah", "low", "bass", "besar" -> 6
                    else -> null
                }
                "B" -> 2
                "G" -> 3
                "D" -> 4
                "A" -> 5
                else -> null
            }
        }
        return null
    }

    private fun parseTuner(raw: String): Command? {
        if (reTunerStop.matches(raw)) return Command.Tuner(false)
        val hasTuner = reTunerWord.containsMatchIn(raw)
        val hasGuitar = reGuitar.containsMatchIn(raw)
        val str = stringNumber(raw)
        // "tuning gitar", "stem gitar", "tuner", "tune my guitar"
        if (hasTuner && (hasGuitar || raw.length < 24)) return Command.Tuner(true, str, tone = reTone.containsMatchIn(raw))
        // "bunyikan senar 3", "nada senar A", "play the low E string"
        if (hasGuitar && str != null && reTone.containsMatchIn(raw)) return Command.Tuner(true, str, tone = true)
        if (hasGuitar && str != null) return Command.Tuner(true, str, tone = false)
        return null
    }

    // ------------------------------------------------------------------ ekspresi

    private val moodWords: List<Pair<Regex, Mood>> = listOf(
        rx("""\b(?:takut|ketakutan|ngeri|scared|afraid|fear(?:ful)?|frightened|terrified|gemetar|panik)\b""") to Mood.SCARED,
        rx("""\b(?:sedih|menangis|nangis|nangis|bersedih|galau|murung|sad|cry(?:ing)?|upset|kecewa)\b""") to Mood.SAD,
        rx("""\b(?:marah|kesal|kesel|ngambek|jengkel|angry|mad|annoyed|grumpy|cemberut|bete)\b""") to Mood.ANNOYED,
        rx("""\b(?:kaget|terkejut|kejut|surprise[d]?|shock(?:ed)?|wow|terkesima)\b""") to Mood.SURPRISED,
        rx("""\b(?:bingung|binggung|confused|puzzled|heran)\b""") to Mood.CONFUSED,
        rx("""\b(?:jatuh\s+cinta|cinta|sayang|love|in\s+love|baper|tersipu|malu|blush(?:ing)?|shy)\b""") to Mood.LOVE,
        rx("""\b(?:ngantuk|mengantuk|sleepy|tired|lelah|capek|tidur|sleep|drowsy)\b""") to Mood.SLEEPY,
        rx("""\b(?:penasaran|curious|ingin\s+tahu)\b""") to Mood.CURIOUS,
        rx("""\b(?:kedip|berkedip|kedipkan|wink(?:ing)?|kedipin)\b""") to Mood.WINK,
        rx("""\b(?:tertawa|ketawa|ngakak|tawa|laugh(?:ing)?|lol|haha|terbahak)\b""") to Mood.LAUGH,
        rx("""\b(?:senang|bahagia|gembira|riang|happy|glad|joy(?:ful)?|tersenyum|senyum|smile|smiling|ceria|girang)\b""") to Mood.HAPPY
    )

    private val reEmoteLead = rx("""^(?:(?:coba|tolong|please|dong|ayo|yuk|nine|kamu|bisa(?:kah)?|can\s+you|could\s+you)\s+)*(?:buat(?:kan)?|bikin(?:kan)?|tunjukkan|tunjukin|tampilkan|perlihatkan|show(?:\s+me)?|make|do|give\s+me|put\s+on|pasang(?:kan)?|tampil(?:kan)?|berikan|kasih|act|be|look|jadi|berubah\s+jadi|pura-?pura|ekspresikan|express)\b""")
    private val reEmoteNoun = rx("""\b(?:ekspresi|expression|emote|emoticon|muka|wajah|face|raut|look|mimik|reaksi)\b""")
    private val reNotEmote = rx("""\b(?:alarm|timer|pengingat|reminder|tugas|catatan|acara|jadwal|event|note|todo|telepon|sms|pesan|wa|whatsapp|foto|video|lagu|musik|senter|volume|wifi|bluetooth|hotspot|aplikasi|app)\b""")

    /** Ekspresi yang disebut dalam [s] (kata perasaan pertama yang cocok), atau null. */
    fun moodIn(s: String): Mood? {
        for ((re, m) in moodWords) if (re.containsMatchIn(s)) return m
        return null
    }

    private val reSelf = rx("""\b(?:aku|saya|gue|gw|ku|i|i'm|im|my|me|we|kita)\b""")
    private val reNegation = rx("""\b(?:jangan|tidak|nggak|gak|ga|bukan|don't|dont|not|no|never)\b""")

    private fun parseEmote(raw: String): Command? {
        if (reNotEmote.containsMatchIn(raw)) return null
        val mood = moodIn(raw) ?: return null
        val lead = reEmoteLead.containsMatchIn(raw)
        val noun = reEmoteNoun.containsMatchIn(raw)
        // Curhat ("aku sedih", "jangan marah") bukan permintaan ekspresi.
        if (!lead && !noun && (reSelf.containsMatchIn(raw) || reNegation.containsMatchIn(raw))) return null
        val words = raw.trim().split(Regex("\\s+"))
        // "buat ekspresi sedih", "tunjukkan wajah takut", "jadi marah dong", "senyum dong", "nangis"
        if (lead || noun || words.size <= 3) {
            // kalimat curhat panjang tanpa kata perintah bukan permintaan ekspresi
            return Command.Emote(mood)
        }
        return null
    }

    // ------------------------------------------------------------------ pengaturan Nine

    private val reOnVerb = rx("""\b(?:nyalakan|nyalain|hidupkan|hidupin|aktifkan|aktifin|enable|turn\s+on|switch\s+on|aktif|on|start|mulai|munculkan|tampilkan|show|pasang)\b""")
    private val reOffVerb = rx("""\b(?:matikan|matiin|nonaktifkan|nonaktifin|disable|turn\s+off|switch\s+off|off|mati|stop|hentikan|berhenti|sembunyikan|hide|hilangkan|jangan|remove|copot)\b""")

    private val reExit = rx("""^(?:keluar(?:\s+(?:dari\s+)?(?:aplikasi|app|nine))?(?:\s+(?:ini|dong|ya|aja|sekarang))*|tutup\s+(?:aplikasi|app|nine)(?:\s+(?:ini|dong|ya|aja|sekarang))*|exit(?:\s+(?:the\s+)?(?:app|nine))?|quit(?:\s+(?:the\s+)?(?:app|nine))?|close\s+(?:the\s+)?(?:app|nine)|tutup\s+diri|selesai(?:\s+(?:dulu|dong))?\s+nine)$""")
    private val reWake = rx("""\b(?:wake\s*(?:phrase|word)|frasa\s+(?:panggil(?:an)?|pemanggil|bangun)|kata\s+(?:panggil(?:an)?|pemanggil|bangun)|panggilan\s+suara|aktivasi\s+suara|hey\s+nine|hei\s+nine|deteksi\s+suara|mendengarkan\s+terus|always\s+listening)\b""")
    private val reTouch = rx("""\b(?:assistive\s*touch|assistivetouch|tombol\s+(?:melayang|mengambang|bantu|virtual|asisten|nine|floating)|floating\s+(?:button|bubble)|bubble\s+nine|asisten\s+sentuh|tombol\s+sentuh)\b""")
    private val reMusicReact = rx("""\b(?:bereaksi|reaksi|react(?:ing)?|respon|menari|dance|dancing|joget|goyang)\b.*\b(?:musik|music|lagu|beat|ketukan|irama)\b|\b(?:musik|music|lagu|beat|irama)\b.*\b(?:bereaksi|reaksi|react|menari|dance|joget|goyang)\b|\bmode\s+(?:musik|music|disko|party|pesta)\b""")
    private val reTheme = rx("""\b(?:mode\s+(gelap|terang|dark|light|malam|siang)|(dark|light|night|day)\s+(?:mode|theme)|tema\s+(gelap|terang|dark|light|sistem|system|otomatis|auto|malam|siang)|theme\s+(dark|light|system|auto))\b|\b(?:ganti|ubah|pindah|set|switch|change|jadikan)\s+(?:the\s+)?(?:tema|theme|mode)\s*(?:aplikasi\s+|nine\s+|app\s+)?(?:ke|jadi|menjadi|to)?\s*(gelap|terang|dark|light|sistem|system|otomatis|auto|malam|siang|putih|hitam)\b""")

    private fun themeFrom(word: String, off: Boolean): NineSetting {
        val w = word.lowercase()
        val dark = w in setOf("gelap", "dark", "malam", "night", "hitam")
        val light = w in setOf("terang", "light", "siang", "day", "putih")
        return when {
            dark -> if (off) NineSetting.THEME_LIGHT else NineSetting.THEME_DARK
            light -> if (off) NineSetting.THEME_DARK else NineSetting.THEME_LIGHT
            else -> NineSetting.THEME_SYSTEM
        }
    }

    private fun parseSettings(raw: String): Command? {
        if (reExit.matches(raw)) return Command.AppSetting(NineSetting.EXIT_APP)
        val off = reOffVerb.containsMatchIn(raw) && !reOnVerb.containsMatchIn(raw)
        val onlyOn = reOnVerb.containsMatchIn(raw) && !reOffVerb.containsMatchIn(raw)
        if (reTouch.containsMatchIn(raw)) {
            return Command.AppSetting(if (off) NineSetting.TOUCH_OFF else NineSetting.TOUCH_ON)
        }
        if (reWake.containsMatchIn(raw) && (off || onlyOn)) {
            return Command.AppSetting(if (off) NineSetting.WAKE_OFF else NineSetting.WAKE_ON)
        }
        if (reMusicReact.containsMatchIn(raw)) {
            return Command.AppSetting(if (off) NineSetting.MUSIC_REACT_OFF else NineSetting.MUSIC_REACT_ON)
        }
        reTheme.find(raw)?.let { m ->
            val word = m.groups.drop(1).firstOrNull { it != null && it.value.isNotEmpty() }?.value
            if (word != null) return Command.AppSetting(themeFrom(word, off))
        }
        return null
    }
}

package id.nine.assistant.nlu

/** Jawaban pengguna terhadap pertanyaan lanjutan. */
sealed class SlotOutcome {
    data class Resolved(val command: Command) : SlotOutcome()
    object Cancelled : SlotOutcome()
    /** Ucapan itu bukan jawaban; perlakukan sebagai perintah baru. */
    object NotAnAnswer : SlotOutcome()
}

object Answers {
    private val yes = Regex(
        """^(?:ya|iya|yap|yup|yoi|betul|benar|oke|ok|okay|baik|boleh|lanjut|lanjutkan|kirim|kirimkan|silakan|silahkan|tentu|""" +
            """yes|yeah|yep|sure|go\s+ahead|do\s+it|send\s+it|please\s+do|correct|confirm)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val no = Regex(
        """^(?:tidak|nggak|enggak|engga|gak|ga|nope|no|jangan|batal|batalkan|cancel|stop|never\s*mind|forget\s+it|lupakan|sudahlah|don't)\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private fun tidy(s: String) = s.trim().trimEnd('.', ',', '!', '?', ' ')

    fun isYes(s: String): Boolean = yes.matches(tidy(s))

    private val strictYes = Regex(
        """^(?:ya|iya|yap|yup|yes|yeah|yep|betul|benar|oke|ok|okay)(?:\s+(?:tolong|telepon|telpon|hubungi|panggil|sekarang|segera|please|call|them|now|dong|banget|saya|iya|ya))*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Jawaban "ya" yang ketat, untuk tindakan yang tak boleh terjadi tanpa sengaja (menelepon polisi): hanya "ya" ditambah kata
     * penguat singkat ("ya, telepon sekarang"). Kalimat lain yang kebetulan diawali "ya" ("ya sudah nanti saja") tidak dihitung.
     */
    fun isStrictYes(s: String): Boolean {
        val t = s.lowercase().replace(Regex("""[,.!?;:]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        return t.isNotEmpty() && t.split(' ').size <= 4 && strictYes.matches(t)
    }
    fun isNo(s: String): Boolean = no.matches(tidy(s))
}

/** Mengisi informasi yang kurang berdasarkan jawaban pengguna. */
class SlotFiller(private val parser: CommandParser) {

    fun fill(p: Pending, text: String): SlotOutcome {
        // Untuk isian bebas (catatan, isi pesan) hanya anggap batal bila ucapannya pendek.
        val freeText = p is Pending.NoteText || p is Pending.SmsBody || p is Pending.ReminderText || p is Pending.TodoText
        val short = text.trim().split(Regex("\\s+")).size <= 2
        if (Answers.isNo(text) && (!freeText || short)) return SlotOutcome.Cancelled
        val now = parser.parseWithNow()
        val norm = NumberWords.normalize(text.trim())

        return when (p) {
            is Pending.Confirm -> {
                // Menelepon polisi: jawaban "ya" harus ketat supaya tidak terpicu oleh ucapan lain.
                val yes = if (p.command is Command.CallEmergency) Answers.isStrictYes(text) else Answers.isYes(text)
                if (yes) SlotOutcome.Resolved(p.command) else SlotOutcome.NotAnAnswer
            }

            is Pending.AlarmTime -> {
                val tp = TimeParser.parse(norm)
                val at = tp?.spec?.resolve(now)
                if (tp != null && tp.spec.hour != null && at != null) {
                    SlotOutcome.Resolved(Command.SetAlarm(at, p.label))
                } else SlotOutcome.NotAnAnswer
            }

            is Pending.TimerDuration -> {
                val secs = Durations.parseSeconds(norm)
                if (secs != null) SlotOutcome.Resolved(Command.SetTimer(secs, p.label)) else SlotOutcome.NotAnAnswer
            }

            is Pending.ReminderTime -> {
                val tp = TimeParser.parse(norm)
                if (tp == null || !tp.spec.isKnown) {
                    SlotOutcome.NotAnAnswer
                } else {
                    val rel = tp.spec.relativeSeconds
                    val at = tp.spec.resolve(now)
                    when {
                        rel != null -> SlotOutcome.Resolved(Command.SetTimer(rel, p.text.ifBlank { null }))
                        at != null -> SlotOutcome.Resolved(Command.SetReminder(p.text, at))
                        else -> SlotOutcome.NotAnAnswer
                    }
                }
            }

            is Pending.ReminderText ->
                SlotOutcome.Resolved(parser.parse("ingatkan saya " + text.trim()))

            is Pending.NoteText ->
                SlotOutcome.Resolved(Command.AddNote(text.trim()))

            is Pending.SmsBody ->
                SlotOutcome.Resolved(Command.SendSms(p.target, text.trim()))

            is Pending.CallTarget ->
                SlotOutcome.Resolved(Command.Call(text.trim()))

            is Pending.AppName ->
                SlotOutcome.Resolved(Command.OpenApp(text.trim()))

            is Pending.EventTime -> {
                val ev = parser.buildEvent(p.title, text, now)
                if (ev != null) SlotOutcome.Resolved(ev) else SlotOutcome.NotAnAnswer
            }

            is Pending.TodoText ->
                SlotOutcome.Resolved(Command.TodoAdd(text.trim()))

            is Pending.Choose -> choose(p, text)
        }
    }

    private val reAll = Regex("""^(?:semua(?:nya)?|semuanya\s+saja|all(?:\s+of\s+them)?|both|keduanya|dua-?duanya)\b.*$""", RegexOption.IGNORE_CASE)

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s:.]"), " ").split(Regex("\\s+"))
            .map { it.trim('.', ':') }.filter { it.length >= 2 || (it.length == 1 && it[0].isDigit()) }

    /** Kata [w] ada di [label]; angka jam ("9") cocok dengan "09.00" / "9:30". */
    private fun hit(label: String, w: String): Boolean {
        if (w.all { it.isDigit() } && w.length <= 2) {
            return Regex("""(?<!\d)0?${w.toInt()}(?:[.:]\d{2})""").containsMatchIn(label) || Regex("""(?<![\d.:])$w(?![\d.:])""").containsMatchIn(label)
        }
        return label.contains(w) || label.contains(w.replace('.', ':')) || label.contains(w.replace(':', '.'))
    }

    private fun choose(p: Pending.Choose, text: String): SlotOutcome {
        val t = text.trim()
        if (p.all != null && reAll.matches(t)) return SlotOutcome.Resolved(p.all)
        (Ordinals.parse(t) ?: Ordinals.parse(NumberWords.normalize(t)))?.let { n ->
            val i = if (n == -1) p.options.size - 1 else n - 1
            p.options.getOrNull(i)?.let { return SlotOutcome.Resolved(it.command) }
        }
        // "yang jam 10" / "yang rapat RNPS": cocokkan dengan teks pilihan
        val ignore = setOf("yang", "itu", "the", "one", "jam", "pukul", "at", "acara", "alarm", "saja", "aja")
        val ws = words(NumberWords.normalize(t)).filter { it !in ignore }
        if (ws.isEmpty()) return SlotOutcome.NotAnAnswer
        val hits = p.options.filter { c ->
            val label = c.label.lowercase()
            ws.all { w -> hit(label, w) }
        }
        return if (hits.size == 1) SlotOutcome.Resolved(hits[0].command) else SlotOutcome.NotAnAnswer
    }
}

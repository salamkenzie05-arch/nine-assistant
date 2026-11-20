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
    fun isNo(s: String): Boolean = no.matches(tidy(s))
}

/** Mengisi informasi yang kurang berdasarkan jawaban pengguna. */
class SlotFiller(private val parser: CommandParser) {

    fun fill(p: Pending, text: String): SlotOutcome {
        // Untuk isian bebas (catatan, isi pesan) hanya anggap batal bila ucapannya pendek.
        val freeText = p is Pending.NoteText || p is Pending.SmsBody || p is Pending.ReminderText
        val short = text.trim().split(Regex("\\s+")).size <= 2
        if (Answers.isNo(text) && (!freeText || short)) return SlotOutcome.Cancelled
        val now = parser.parseWithNow()
        val norm = NumberWords.normalize(text.trim())

        return when (p) {
            is Pending.Confirm ->
                if (Answers.isYes(text)) SlotOutcome.Resolved(p.command) else SlotOutcome.NotAnAnswer

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
        }
    }
}

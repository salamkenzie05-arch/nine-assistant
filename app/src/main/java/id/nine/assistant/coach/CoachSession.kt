package id.nine.assistant.coach

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood

/**
 * Sesi latihan bahasa Inggris: Nine bertanya, pengguna menjawab dalam bahasa Inggris, Nine mengoreksi kesalahan dan
 * menyarankan kata yang lebih bagus lalu bertanya lagi. Semua aturan lokal (tanpa AI, tanpa internet) dan Kotlin
 * murni; [AssistantController] hanya menyalurkan ucapan ke sini selama [active].
 */
class CoachSession {

    /** Hasil satu giliran. [ended] = sesi selesai. */
    class Turn(val reply: String, val ended: Boolean = false, val mood: Mood = Mood.NEUTRAL)

    var active: Boolean = false
        private set

    private var sentences = 0
    private var perfect = 0
    private val topics = LinkedHashMap<Topic, Int>()
    private val asked = HashSet<Prompt>()
    private var lastPrompt: Prompt? = null
    private var rotate = 0

    /** Kalimat yang sudah diperiksa (dipakai tes dan layar ringkasan). */
    val sentenceCount: Int get() = sentences
    val perfectCount: Int get() = perfect

    // ------------------------------------------------------------------ mulai & selesai

    fun start(lang: AppLang): String {
        active = true
        sentences = 0
        perfect = 0
        topics.clear()
        asked.clear()
        rotate = 0
        val q = pick(null)
        val intro = CoachFeedback.both(
            lang,
            "Let's practise English! Talk to me and I'll fix your mistakes and suggest better words. " +
                "Say \"next question\" for a new topic, \"my progress\" for your score, or \"stop English practice\" when you're done.",
            "Ayo latihan English! Bicara saja, nanti saya koreksi kesalahan dan sarankan kata yang lebih bagus. " +
                "Ucapkan \"next question\" untuk topik baru, \"my progress\" untuk skor, atau \"stop English practice\" untuk selesai. " +
                "Selama latihan, mikrofon mendengarkan bahasa Inggris."
        )
        return intro + "\n\n" + format(q, lang)
    }

    fun stop(lang: AppLang): String {
        active = false
        return summary(lang)
    }

    private fun summary(lang: AppLang): String {
        if (sentences == 0) {
            return CoachFeedback.both(lang, "Practice finished. Come back any time!", "Latihan selesai. Kembali kapan saja ya!")
        }
        val top = topics.entries.sortedByDescending { it.value }.take(2)
        val enTop = if (top.isEmpty()) "" else " Most common: " + top.joinToString(", ") { "${it.key.en} (${it.value}x)" } + "."
        val idTop = if (top.isEmpty()) "" else " Paling sering: " + top.joinToString(", ") { "${it.key.id} (${it.value}x)" } + "."
        return CoachFeedback.both(
            lang,
            "Practice finished! You said $sentences sentence${if (sentences == 1) "" else "s"} and $perfect had no mistakes.$enTop Keep going!",
            "Latihan selesai! Kamu mengucapkan $sentences kalimat dan $perfect di antaranya tanpa kesalahan.$idTop Terus berlatih!"
        )
    }

    private fun progress(lang: AppLang): String {
        if (sentences == 0) {
            return CoachFeedback.both(lang, "We just started. Say a sentence and I'll check it!", "Kita baru mulai. Ucapkan satu kalimat, nanti saya periksa!")
        }
        val pct = perfect * 100 / sentences
        return CoachFeedback.both(
            lang,
            "So far: $sentences sentence${if (sentences == 1) "" else "s"}, $perfect without mistakes ($pct%). Keep going!",
            "Sejauh ini: $sentences kalimat, $perfect tanpa kesalahan ($pct%). Terus semangat!"
        )
    }

    // ------------------------------------------------------------------ satu giliran

    fun handle(text: String, lang: AppLang): Turn {
        val sentence: String
        when (val intent = CoachIntents.detectInSession(text)) {
            is CoachIntent.Stop -> return Turn(stop(lang), ended = true, mood = Mood.HAPPY)
            is CoachIntent.NextTopic -> return Turn(format(pick(null), lang), mood = Mood.CURIOUS)
            is CoachIntent.Progress -> return Turn(progress(lang), mood = Mood.HAPPY)
            is CoachIntent.BetterWord -> {
                val again = lastPrompt?.let { "\n\n" + format(it, lang) }.orEmpty()
                return Turn(CoachFeedback.betterWordReply(intent.word, lang) + again, mood = Mood.HAPPY)
            }
            is CoachIntent.Start -> {
                val again = lastPrompt?.let { format(it, lang) }.orEmpty()
                return Turn(CoachFeedback.both(lang, "We're already practising! Keep talking.", "Kita sudah berlatih! Lanjutkan bicara.") + "\n\n" + again, mood = Mood.HAPPY)
            }
            is CoachIntent.Correct -> sentence = intent.sentence
            null -> sentence = text
        }
        val s = sentence.trim()
        if (CoachFeedback.looksIndonesian(s)) {
            val again = lastPrompt?.let { "\n\n" + format(it, lang) }.orEmpty()
            return Turn(
                CoachFeedback.both(
                    lang,
                    "That sounds like Indonesian. Try saying it in English. Even with mistakes, I'll help you fix it!",
                    "Itu terdengar seperti bahasa Indonesia. Coba ucapkan dalam bahasa Inggris. Walau salah, saya bantu perbaiki!"
                ) + again,
                mood = Mood.CURIOUS
            )
        }
        if (CoachFeedback.wordCount(s) < 2) {
            val again = lastPrompt?.let { "\n\n" + format(it, lang) }.orEmpty()
            return Turn(
                CoachFeedback.both(
                    lang,
                    "Good start! Try a full sentence, for example: \"I like to listen to music.\"",
                    "Awal yang bagus! Coba kalimat lengkap, misalnya: \"I like to listen to music.\""
                ) + again,
                mood = Mood.HAPPY
            )
        }
        val rv = CoachFeedback.review(s, lang, sentences)
        sentences++
        if (rv.perfect) perfect++
        for (i in rv.issues) topics[i.topic] = (topics[i.topic] ?: 0) + 1
        val q = pick(s)
        return Turn(rv.text + "\n\n" + format(q, lang), mood = if (rv.perfect) Mood.HAPPY else Mood.NEUTRAL)
    }

    // ------------------------------------------------------------------ pertanyaan

    private fun format(p: Prompt, lang: AppLang): String =
        if (lang == AppLang.ID) "${p.en}\n(${p.id})" else p.en

    /** Pilih pertanyaan baru: sesuai kata kunci dari [said] bila ada, kalau tidak berputar di daftar umum. */
    private fun pick(said: String?): Prompt {
        val low = said?.lowercase().orEmpty()
        var choice: Prompt? = null
        if (low.isNotEmpty()) {
            for ((re, list) in TOPIC_PROMPTS) {
                if (re.containsMatchIn(low)) {
                    choice = list.firstOrNull { it !in asked }
                    if (choice != null) break
                }
            }
        }
        if (choice == null) {
            if (GENERIC.all { it in asked }) asked.clear()
            // mulai dari posisi berputar agar sesi berbeda tidak selalu dibuka dengan pertanyaan yang sama
            val n = GENERIC.size
            for (k in 0 until n) {
                val c = GENERIC[(rotate + k) % n]
                if (c !in asked) {
                    choice = c
                    rotate = (rotate + k + 1) % n
                    break
                }
            }
        }
        val p = choice ?: GENERIC[0]
        asked.add(p)
        lastPrompt = p
        return p
    }

    data class Prompt(val en: String, val id: String)

    companion object {
        val GENERIC: List<Prompt> = listOf(
            Prompt("What did you do today?", "Apa yang kamu lakukan hari ini?"),
            Prompt("Tell me about your family.", "Ceritakan tentang keluargamu."),
            Prompt("What is your favourite food, and why?", "Apa makanan favoritmu, dan kenapa?"),
            Prompt("Describe your best friend.", "Ceritakan tentang sahabatmu."),
            Prompt("What are your plans for the weekend?", "Apa rencanamu untuk akhir pekan?"),
            Prompt("What do you do for work or study?", "Apa pekerjaan atau studimu?"),
            Prompt("Tell me about a place you want to visit.", "Ceritakan tempat yang ingin kamu kunjungi."),
            Prompt("What is your favourite movie or song?", "Apa film atau lagu favoritmu?"),
            Prompt("What was the best day of your life?", "Kapan hari terbaik dalam hidupmu?"),
            Prompt("How do you usually spend your evenings?", "Bagaimana biasanya kamu menghabiskan malam?"),
            Prompt("What is a skill you would like to learn?", "Keterampilan apa yang ingin kamu pelajari?"),
            Prompt("Describe your room.", "Ceritakan kamarmu."),
            Prompt("What makes you happy?", "Apa yang membuatmu bahagia?"),
            Prompt("What did you eat for lunch yesterday?", "Apa yang kamu makan saat makan siang kemarin?"),
            Prompt("Tell me about your hometown.", "Ceritakan kota asalmu."),
            Prompt("What is your morning routine?", "Bagaimana rutinitas pagimu?"),
            Prompt("What is the most interesting thing you learned recently?", "Apa hal paling menarik yang baru kamu pelajari?"),
            Prompt("If you could have any job, what would it be?", "Kalau bisa memilih pekerjaan apa saja, kamu pilih apa?")
        )

        private fun p(en: String, id: String) = Prompt(en, id)

        /** Kata kunci dari ucapan pengguna → pertanyaan lanjutan yang nyambung. */
        val TOPIC_PROMPTS: List<Pair<Regex, List<Prompt>>> = listOf(
            Regex("""\b(?:food|eat|ate|dinner|lunch|breakfast|cook|cooking|restaurant|hungry|dish|meal)\b""") to listOf(
                p("What is your favourite dish, and how is it made?", "Apa masakan favoritmu, dan bagaimana cara membuatnya?"),
                p("What did you eat today?", "Apa yang kamu makan hari ini?")
            ),
            Regex("""\b(?:work|job|office|boss|colleague|career|company|business)\b""") to listOf(
                p("What do you do at work every day?", "Apa yang kamu kerjakan di tempat kerja setiap hari?"),
                p("What is the best part of your job?", "Apa bagian terbaik dari pekerjaanmu?")
            ),
            Regex("""\b(?:school|study|studied|studying|university|college|class|teacher|exam|lesson|homework|student)\b""") to listOf(
                p("What is your favourite subject, and why?", "Apa pelajaran favoritmu, dan kenapa?"),
                p("Tell me about your teacher or a classmate.", "Ceritakan tentang gurumu atau teman sekelasmu.")
            ),
            Regex("""\b(?:travel|trip|holiday|vacation|weekend|beach|mountain|flight|hotel|visit|visited)\b""") to listOf(
                p("Where would you like to go next?", "Ke mana kamu ingin pergi berikutnya?"),
                p("Tell me about the best trip you ever had.", "Ceritakan perjalanan terbaik yang pernah kamu alami.")
            ),
            Regex("""\b(?:family|mother|father|mom|dad|brother|sister|wife|husband|son|daughter|parents|children|kids|grandma|grandmother|grandfather)\b""") to listOf(
                p("What do you like to do together as a family?", "Apa yang suka kalian lakukan bersama sebagai keluarga?"),
                p("Who in your family are you closest to?", "Siapa anggota keluarga yang paling dekat denganmu?")
            ),
            Regex("""\b(?:friend|friends|buddy|classmate)\b""") to listOf(
                p("How did you meet your best friend?", "Bagaimana kamu bertemu sahabatmu?"),
                p("What do you and your friends do together?", "Apa yang kamu dan teman-temanmu lakukan bersama?")
            ),
            Regex("""\b(?:movie|film|music|song|sing|game|games|book|read|series|drama)\b""") to listOf(
                p("What did you like most about it?", "Apa yang paling kamu suka dari itu?"),
                p("Who is your favourite singer or actor?", "Siapa penyanyi atau aktor favoritmu?")
            ),
            Regex("""\b(?:weather|rain|raining|rainy|sunny|hot|cold|storm|flood)\b""") to listOf(
                p("What is the weather like where you live?", "Bagaimana cuaca di tempatmu tinggal?"),
                p("What is your favourite season?", "Musim apa yang paling kamu suka?")
            ),
            Regex("""\b(?:football|soccer|badminton|basketball|sport|sports|gym|run|running|swim|swimming|exercise|workout)\b""") to listOf(
                p("How often do you exercise?", "Seberapa sering kamu berolahraga?"),
                p("Which sport do you enjoy most?", "Olahraga apa yang paling kamu nikmati?")
            ),
            Regex("""\b(?:tired|sleep|sleepy|bed|wake|woke)\b""") to listOf(
                p("What is your morning routine?", "Bagaimana rutinitas pagimu?"),
                p("How many hours do you usually sleep?", "Biasanya kamu tidur berapa jam?")
            ),
            Regex("""\b(?:buy|bought|shop|shopping|market|money|price|expensive|cheap)\b""") to listOf(
                p("What was the last thing you bought?", "Apa barang terakhir yang kamu beli?"),
                p("Do you prefer shopping online or in a store?", "Kamu lebih suka belanja online atau di toko?")
            ),
            Regex("""\b(?:phone|computer|laptop|internet|app|apps|social)\b""") to listOf(
                p("What apps do you use every day?", "Aplikasi apa yang kamu pakai setiap hari?"),
                p("How do you use your phone to learn English?", "Bagaimana kamu memakai HP untuk belajar bahasa Inggris?")
            ),
            Regex("""\b(?:house|home|room|apartment|garden|kitchen)\b""") to listOf(
                p("What is your favourite place in your home?", "Apa tempat favoritmu di rumah?"),
                p("Describe your room.", "Ceritakan kamarmu.")
            ),
            Regex("""\b(?:cat|dog|pet|pets|bird|fish)\b""") to listOf(
                p("Do you prefer cats or dogs, and why?", "Kamu lebih suka kucing atau anjing, dan kenapa?"),
                p("Tell me about your pet.", "Ceritakan tentang hewan peliharaanmu.")
            ),
            Regex("""\b(?:happy|sad|angry|excited|nervous|worried|afraid|scared)\b""") to listOf(
                p("Why do you feel that way?", "Kenapa kamu merasa begitu?"),
                p("What usually makes you feel better?", "Apa yang biasanya membuatmu merasa lebih baik?")
            )
        )
    }
}

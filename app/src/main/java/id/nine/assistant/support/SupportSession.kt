package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import id.nine.assistant.support.CrisisCheck.Level
import id.nine.assistant.support.Feelings.Emotion
import id.nine.assistant.support.Feelings.Reading
import id.nine.assistant.support.Feelings.Topic
import id.nine.assistant.support.SupportIntent.Exercise

/**
 * Sesi curhat: Nine sebagai pendengar yang hangat (BUKAN psikolog, tanpa diagnosis, tanpa saran obat).
 * Mesin aturan ini selalu bekerja tanpa internet. Bila model AI lokal terpasang, [Turn.aiPrompt] berisi instruksi
 * untuk membuat balasan yang lebih alami; [Turn.reply] tetap menjadi cadangan bila AI gagal atau ditolak SupportSafety.
 *
 * Kotlin murni. Pemeriksaan krisis dilakukan di luar sesi lebih dulu (CrisisCheck) dan hasilnya diberikan lewat [crisis].
 */
class SupportSession {

    class Turn(
        val reply: String,
        val ended: Boolean = false,
        val mood: Mood = Mood.SAD,
        /** Bila tidak null dan AI lokal tersedia, boleh dipakai untuk balasan yang lebih alami. */
        val aiPrompt: String? = null
    )

    var active = false
        private set

    /** false = baru sebatas menunggu jawaban "apakah kamu baik-baik saja?" setelah kalimat samar. */
    private var full = false
    private var awaitingCheckIn = false
    private var askedSafe = false
    private var checkedNo = false
    private var afterExercise = false
    private var offerPending: Exercise? = null
    private var offered = false
    private var referred = false
    private var crisisCount = 0
    private val counters = HashMap<String, Int>()

    var turns = 0
        private set
    private var negTurns = 0

    private fun pick(key: String, options: List<String>): String {
        val i = counters[key] ?: 0
        counters[key] = i + 1
        return options[i % options.size]
    }

    // ------------------------------------------------------------------ masuk / keluar

    fun start(lang: AppLang, exercise: Exercise? = null): String {
        active = true
        full = true
        awaitingCheckIn = false
        if (exercise != null) {
            afterExercise = true
            return lang.t("Baik, aku temani ya.\n\n", "Okay, I'll do this with you.\n\n") + exerciseText(exercise, lang)
        }
        return lang.t(
            "Aku di sini untuk mendengarkanmu. Perlu kamu tahu: aku bukan psikolog atau konselor profesional, " +
                "tapi aku bisa menjadi pendengar yang tenang. Obrolan curhat ini tidak disimpan ke riwayat atau ruang obrolan. " +
                "Kamu bisa bilang \"selesai\" kapan saja.\n\nBagaimana perasaanmu sekarang?",
            "I'm here to listen. Just so you know: I'm not a psychologist or a professional counselor, " +
                "but I can be a calm listener. This venting chat isn't saved to your history or chat spaces. " +
                "You can say \"done\" any time.\n\nHow are you feeling right now?"
        )
    }

    fun stop(lang: AppLang): Turn {
        val note = (negTurns >= 3 || turns >= 5) && !referred
        active = false; full = false
        val base = lang.t(
            "Terima kasih sudah bercerita. Mengungkapkan perasaan itu tidak mudah, dan kamu sudah melakukannya. Jaga dirimu baik-baik ya.",
            "Thank you for sharing. Opening up isn't easy, and you did it. Please take good care of yourself."
        )
        val pro = if (note) lang.t(
            " Kalau beban ini terus terasa, bicara dengan psikolog atau orang yang kamu percaya bisa sangat membantu; " +
                "Healing119 (${CrisisResources.healingId()}) juga siap mendengarkan.",
            " If this keeps weighing on you, talking to a psychologist or someone you trust can really help; " +
                "Healing119 in Indonesia (${CrisisResources.healingEn()}) is also there to listen."
        ) else ""
        val tail = lang.t(" Aku selalu di sini kalau kamu mau cerita lagi.", " I'm always here if you want to talk again.")
        return Turn(base + pro + tail, ended = true, mood = Mood.LOVE)
    }

    // ------------------------------------------------------------------ krisis

    /**
     * Hasil CrisisCheck untuk ucapan terbaru. Mengembalikan null bila tidak perlu balasan khusus
     * (mis. kalimat samar yang sudah dijawab "tidak" sebelumnya), sehingga ucapan diproses seperti curhat biasa.
     */
    fun crisis(level: Level, lang: AppLang): Turn? {
        when (level) {
            Level.NONE -> return null
            Level.OTHER -> return Turn(CrisisReplies.reply(Level.OTHER, lang), mood = Mood.SAD)
            Level.IMMEDIATE -> {
                activateFull()
                crisisCount++
                awaitingCheckIn = false; askedSafe = true
                return Turn(CrisisReplies.reply(Level.IMMEDIATE, lang), mood = Mood.SAD)
            }
            Level.SELF -> {
                activateFull()
                crisisCount++
                awaitingCheckIn = false; askedSafe = true
                val text = if (crisisCount >= 2) lang.t(
                    "Aku masih di sini bersamamu. Apakah kamu aman saat ini? Kalau belum, hubungi ${CrisisResources.EMERGENCY} " +
                        "atau Healing119 (${CrisisResources.healingId()}, gratis 24 jam), dan beri tahu orang yang kamu percaya sekarang.",
                    "I'm still here with you. Are you safe right now? If not, please call your local emergency number " +
                        "(${CrisisResources.EMERGENCY} in Indonesia) or Healing119 (${CrisisResources.healingEn()}, free, 24 hours), " +
                        "and tell someone you trust now."
                ) else CrisisReplies.reply(Level.SELF, lang)
                return Turn(text, mood = Mood.SAD)
            }
            Level.CHECK_IN -> {
                if (checkedNo && full) return null
                if (awaitingCheckIn) {
                    // Dua kalimat samar berturut-turut tanpa jawaban: perlakukan sebagai SELF.
                    activateFull()
                    crisisCount++
                    awaitingCheckIn = false; askedSafe = true
                    return Turn(CrisisReplies.reply(Level.SELF, lang), mood = Mood.SAD)
                }
                if (!active) { active = true; full = false }
                awaitingCheckIn = true
                return Turn(CrisisReplies.reply(Level.CHECK_IN, lang), mood = Mood.SAD)
            }
        }
    }

    private fun activateFull() { active = true; full = true }

    // ------------------------------------------------------------------ giliran biasa

    /**
     * Ucapan pengguna (yang sudah lolos pemeriksaan krisis). Null berarti "bukan untukku": sesi menunggu jawaban
     * pertanyaan lembut tetapi pengguna membicarakan hal lain, jadi sesi ditutup dan ucapan diproses seperti biasa.
     */
    fun handle(text: String, lang: AppLang): Turn? {
        if (!active) return null
        if (SupportIntent.isStop(text)) return stop(lang)
        val rd = Feelings.read(text)

        if (askedSafe) {
            askedSafe = false
            val t = SupportText.norm(text)
            when {
                UNSAFE.containsMatchIn(t) -> return Turn(CrisisReplies.reply(Level.IMMEDIATE, lang), mood = Mood.SAD)
                SAFE.containsMatchIn(t) -> return Turn(
                    lang.t(
                        "Syukurlah kamu aman. Aku di sini mendengarkan. Mau ceritakan apa yang sedang terjadi?",
                        "I'm relieved you're safe. I'm here listening. Do you want to tell me what's going on?"
                    ),
                    mood = Mood.SAD
                )
                else -> Unit
            }
        }

        if (awaitingCheckIn) {
            awaitingCheckIn = false
            when {
                rd.no -> {
                    checkedNo = true
                    if (!full) {
                        active = false
                        return Turn(afterNo(lang, endsSession = true), ended = true, mood = Mood.NEUTRAL)
                    }
                    return Turn(afterNo(lang, endsSession = false), mood = Mood.NEUTRAL)
                }
                rd.yes -> {
                    activateFull()
                    crisisCount++; askedSafe = true
                    return Turn(CrisisReplies.afterYes(lang), mood = Mood.SAD)
                }
                !full -> {
                    if (rd.words >= 6 || rd.negative) full = true
                    else { active = false; return null }
                }
                else -> Unit
            }
        }
        if (!full) { active = false; return null }

        turns++
        if (rd.negative) negTurns++
        return listen(text, rd, lang)
    }

    private fun afterNo(l: AppLang, endsSession: Boolean): String =
        if (endsSession) l.t(
            "Syukurlah, terima kasih sudah jujur padaku. Kalau pikiran seperti itu datang, kamu boleh bilang padaku atau " +
                "menghubungi Healing119 (${CrisisResources.healingId()}). Kalau mau bercerita, bilang saja \"aku mau curhat\".",
            "I'm glad, and thank you for being honest with me. If thoughts like that come, you can tell me or contact " +
                "Healing119 in Indonesia (${CrisisResources.healingEn()}). If you'd like to talk, just say \"I need to vent\"."
        ) else CrisisReplies.afterNo(l)

    private fun listen(text: String, rd: Reading, l: AppLang): Turn {
        val primary = rd.primary

        // Pertanyaan tentang Nine sendiri.
        if (rd.asksIdentity && !rd.negative) return Turn(identity(l), mood = Mood.NEUTRAL)
        if (rd.asksDiagnosis || (rd.selfLabel && !rd.wantsAdvice && rd.words <= 12 && rd.negative)) {
            referred = true
            return Turn(diagnosis(l), mood = Mood.SAD)
        }

        // Jawaban atas tawaran latihan.
        offerPending?.let { ex ->
            offerPending = null
            if (rd.yes && !rd.negative) { afterExercise = true; return Turn(exerciseText(ex, l), mood = Mood.NEUTRAL) }
            if (rd.no) return Turn(
                l.t("Tidak apa-apa. Kita lanjut mengobrol saja. Apa yang sedang kamu rasakan sekarang?",
                    "That's okay. We can just keep talking. What are you feeling right now?"),
                mood = Mood.NEUTRAL
            )
        }

        // Setelah latihan: tanya bagaimana rasanya.
        if (afterExercise) {
            afterExercise = false
            when {
                rd.positiveOnly -> return Turn(
                    l.t("Senang mendengarnya. Kamu sudah merawat dirimu dengan baik. Mau lanjut bercerita, atau sudah cukup untuk saat ini?",
                        "I'm glad to hear that. You took good care of yourself. Do you want to keep talking, or is this enough for now?"),
                    mood = Mood.HAPPY
                )
                rd.negative -> return Turn(
                    l.t("Tidak apa-apa kalau belum terasa berbeda; latihan ini butuh waktu. Mau kita lanjut membicarakan apa yang kamu rasakan?",
                        "It's okay if it doesn't feel different yet; this takes time. Do you want to talk about what you're feeling?"),
                    mood = Mood.SAD
                )
                rd.short -> return Turn(
                    l.t("Bagaimana rasanya sekarang? Lebih tenang, sama saja, atau lebih berat?",
                        "How does it feel now? Calmer, the same, or heavier?"),
                    mood = Mood.NEUTRAL
                )
                else -> Unit
            }
        }

        // Permintaan latihan di tengah sesi.
        SupportIntent.exerciseRequest(text)?.let { ex ->
            afterExercise = true
            return Turn(exerciseText(ex, l), mood = Mood.NEUTRAL)
        }

        // Terima kasih saja (tanpa perasaan lain).
        if (rd.thanks && !rd.negative && rd.words <= 8) return Turn(
            l.t("Sama-sama. Aku senang kamu mau berbagi. Kamu boleh lanjut bercerita, atau bilang \"selesai\" kapan saja.",
                "You're welcome. I'm glad you shared. You can keep talking, or say \"done\" any time."),
            mood = Mood.HAPPY
        )

        // Minta saran.
        if (rd.wantsAdvice) return Turn(advice(primary, l), mood = if (rd.negative) Mood.SAD else Mood.NEUTRAL)

        // Kabar baik.
        if (rd.positiveOnly) return Turn(
            pick("pos", l.list(
                listOf("Senang mendengarnya! Apa yang membuatmu merasa begitu?",
                    "Itu kabar yang baik. Apa yang paling membantumu sampai di titik ini?",
                    "Aku ikut senang. Mau ceritakan lebih banyak?"),
                listOf("I'm glad to hear that! What made you feel this way?",
                    "That's good to hear. What helped you get here?",
                    "I'm happy for you. Do you want to tell me more?")
            )),
            mood = Mood.HAPPY
        )

        // Jawaban sangat singkat tanpa perasaan: "ya/tidak/hmm".
        if (primary == null && rd.words <= 3) {
            val text2 = when {
                rd.no -> l.t("Baik. Aku tetap di sini kalau kamu mau cerita.", "Okay. I'm still here if you want to talk.")
                rd.yes -> l.t("Aku mendengarkan, silakan lanjutkan.", "I'm listening, please go on.")
                else -> pick("short", l.list(
                    listOf("Tidak apa-apa kalau belum tahu harus mulai dari mana. Kita bisa pelan-pelan. Apa yang terasa paling berat hari ini?",
                        "Aku di sini, tidak perlu buru-buru. Kalau mau, ceritakan satu hal kecil dari harimu."),
                    listOf("It's okay if you don't know where to start. We can go slowly. What feels heaviest today?",
                        "I'm here, no rush. If you like, tell me one small thing about your day.")
                ))
            }
            return Turn(text2, mood = Mood.NEUTRAL)
        }

        // Menyebut "merasa ..." tetapi perasaannya tidak dikenali: tanya apa penyebabnya.
        if (primary == null && rd.mentionsFeeling) {
            val body = pick("feelAck", l.list(
                listOf("Terima kasih sudah mengatakannya. Perasaan seperti itu nyata.", "Aku mendengarmu. Perasaan itu penting."),
                listOf("Thank you for saying it. A feeling like that is real.", "I hear you. That feeling matters.")
            ))
            val q = l.t("Apa yang membuatmu merasa begitu?", "What makes you feel that way?")
            return Turn("$body $q", mood = Mood.SAD, aiPrompt = aiPrompt(text, rd, l))
        }

        // Cerita tentang kejadian tanpa kata perasaan.
        if (primary == null) {
            val body = pick("event", l.list(
                listOf("Terima kasih sudah menceritakannya. Itu kedengarannya tidak mudah.",
                    "Aku mendengarkan, dan aku bisa membayangkan itu cukup menguras.",
                    "Terima kasih sudah mau berbagi. Pasti ada banyak hal di kepalamu."),
                listOf("Thank you for telling me. That doesn't sound easy.",
                    "I'm listening, and I can imagine that's draining.",
                    "Thanks for sharing. There must be a lot on your mind.")
            ))
            val q = pick("evq", l.list(
                listOf("Bagaimana perasaanmu saat itu?", "Apa yang kamu rasakan waktu itu?", "Lalu, bagaimana kamu menghadapinya?"),
                listOf("How did that make you feel?", "What did you feel at that moment?", "And how did you deal with it?")
            ))
            return Turn("$body $q", mood = Mood.NEUTRAL, aiPrompt = aiPrompt(text, rd, l))
        }

        // ---- Curhat berperasaan ----
        val parts = ArrayList<String>()
        if (rd.long) {
            parts.add(summary(rd, l))
        } else {
            parts.add(reflection(primary, rd, l))
            if (rd.intense && primary != Emotion.SELF_WORTH) {
                parts.add(l.t("Kedengarannya ini sangat berat untukmu.", "It sounds really heavy for you."))
            }
        }

        // Rujukan profesional (sekali), menggantikan pertanyaan biasa.
        val needReferral = !referred && (rd.persistent || primary == Emotion.HOPELESS || primary == Emotion.NUMB || negTurns >= 5)
        if (needReferral) {
            referred = true
            parts.add(referral(l))
            return Turn(parts.joinToString(" "), mood = Mood.SAD)
        }

        // Tawaran latihan (sekali), setelah beberapa giliran negatif.
        if (!offered && negTurns >= 3 && primary != Emotion.GRIEF && primary != Emotion.HOPELESS && primary != Emotion.NUMB) {
            val ex = exerciseFor(primary, rd)
            offered = true
            offerPending = ex
            parts.add(offerText(ex, l))
            return Turn(parts.joinToString(" "), mood = Mood.SAD)
        }

        // Setiap giliran ketiga: cukup menemani, tanpa pertanyaan.
        if (turns % 3 == 0) {
            parts.add(pick("sit", l.list(
                listOf("Aku di sini, tidak perlu terburu-buru. Lanjutkan kapan pun kamu siap.",
                    "Kamu sudah melakukan hal yang tidak mudah dengan menceritakannya. Aku mendengarkan.",
                    "Ambil waktumu. Aku tidak ke mana-mana."),
                listOf("I'm here, no need to rush. Continue whenever you're ready.",
                    "You've done something hard by telling me. I'm listening.",
                    "Take your time. I'm not going anywhere.")
            )))
            return Turn(parts.joinToString(" "), mood = Mood.SAD, aiPrompt = aiPrompt(text, rd, l))
        }

        // Refleksi tertentu (mis. menantang pikiran "tidak berguna", ringkasan) sudah berakhir dengan pertanyaan.
        if (!parts.last().trimEnd().endsWith("?")) parts.add(question(rd, l))
        return Turn(parts.joinToString(" "), mood = Mood.SAD, aiPrompt = aiPrompt(text, rd, l))
    }

    // ------------------------------------------------------------------ isi jawaban

    private fun reflection(e: Emotion?, rd: Reading, l: AppLang): String = when (e) {
        Emotion.SAD -> pick("sad", l.list(
            listOf("Aku bisa merasakan betapa sedihnya itu.", "Kedengarannya hatimu sedang berat sekali.", "Terima kasih sudah menceritakannya. Wajar kalau kamu merasa sedih."),
            listOf("I can feel how sad that is.", "It sounds like your heart is heavy.", "Thank you for telling me. It makes sense that you feel sad.")))
        Emotion.ANXIOUS -> pick("anx", l.list(
            listOf("Kedengarannya pikiranmu sedang penuh rasa cemas.", "Rasa khawatir seperti itu memang melelahkan.", "Wajar kalau kamu merasa gelisah dengan semua itu."),
            listOf("It sounds like your mind is full of worry.", "That kind of worry is exhausting.", "It makes sense that you feel unsettled about all that.")))
        Emotion.ANGRY -> pick("ang", l.list(
            listOf("Aku mendengar ada rasa kesal yang besar di sini.", "Kedengarannya itu sangat menjengkelkan.", "Wajar sekali kalau kamu marah dengan hal itu."),
            listOf("I hear a lot of frustration here.", "That sounds really infuriating.", "It makes complete sense that you're angry about that.")))
        Emotion.TIRED -> pick("tir", l.list(
            listOf("Kedengarannya kamu benar-benar lelah.", "Capek seperti itu menguras, ya. Kamu sudah berusaha keras.", "Tubuh dan pikiranmu sepertinya butuh istirahat."),
            listOf("It sounds like you're really worn out.", "That kind of tiredness drains you. You've been trying hard.", "Your body and mind seem to need rest.")))
        Emotion.LONELY -> pick("lon", l.list(
            listOf("Merasa sendirian itu berat. Terima kasih sudah mau membaginya padaku.", "Aku mendengarmu. Kesepian bisa terasa sangat berat.", "Di percakapan ini kamu tidak sendirian; aku di sini."),
            listOf("Feeling alone is hard. Thank you for sharing it with me.", "I hear you. Loneliness can feel so heavy.", "In this conversation you're not alone; I'm here.")))
        Emotion.GUILT -> pick("gui", l.list(
            listOf("Rasa bersalah itu terdengar membebanimu.", "Kedengarannya kamu cukup keras pada dirimu sendiri.", "Merasa menyesal menandakan kamu peduli."),
            listOf("That guilt sounds like it's weighing on you.", "It sounds like you're being quite hard on yourself.", "Feeling regret shows that you care.")))
        Emotion.HOPELESS -> pick("hop", l.list(
            listOf("Aku mendengar betapa buntunya perasaanmu sekarang. Itu berat.", "Saat semuanya terasa tanpa harapan, itu benar-benar melelahkan."),
            listOf("I hear how stuck you feel right now. That's hard.", "When everything feels hopeless, it's truly exhausting.")))
        Emotion.OVERWHELMED -> pick("ovr", l.list(
            listOf("Kedengarannya terlalu banyak yang kamu pikul sekaligus.", "Wajar kalau kamu merasa kewalahan dengan semua itu."),
            listOf("It sounds like you're carrying too much at once.", "It makes sense that you feel overwhelmed by all that.")))
        Emotion.GRIEF -> pick("gri", l.list(
            listOf("Aku turut berduka. Kehilangan itu berat, dan tidak ada cara yang \"benar\" untuk berduka.", "Terima kasih sudah berbagi tentang kehilanganmu. Pelan-pelan saja."),
            listOf("I'm so sorry for your loss. Grief is hard, and there's no \"right\" way to grieve.", "Thank you for sharing your loss with me. Take it slowly.")))
        Emotion.NUMB -> pick("num", l.list(
            listOf("Rasa hampa seperti itu bisa sangat melelahkan. Terima kasih sudah mengatakannya.", "Merasa kosong atau mati rasa juga bagian dari beban perasaan, dan itu nyata."),
            listOf("That kind of emptiness can be exhausting. Thank you for saying it.", "Feeling empty or numb is also a real part of emotional weight.")))
        Emotion.SELF_WORTH -> pick("sw", l.list(
            listOf("Aku mendengar kamu cukup keras pada dirimu. Perasaan \"tidak cukup baik\" itu terasa nyata, tapi bukan berarti itu kenyataannya. " +
                "Kalau sahabatmu mengalami hal yang sama, apa yang akan kamu katakan padanya?",
                "Terima kasih sudah jujur tentang itu. Pikiran seperti ini sering terasa sangat meyakinkan. " +
                    "Adakah hal yang pernah kamu lakukan dengan baik, sekecil apa pun, yang tidak cocok dengan pikiran itu?"),
            listOf("I hear that you're being very hard on yourself. The feeling of \"not good enough\" is real, but it isn't necessarily the truth. " +
                "If a close friend were in your place, what would you say to them?",
                "Thank you for being honest about that. Thoughts like this can feel very convincing. " +
                    "Is there something you've done well, however small, that doesn't fit that thought?")))
        Emotion.CONFUSED -> pick("con", l.list(
            listOf("Wajar kalau terasa bingung. Kita pelan-pelan saja.", "Kebingungan itu manusiawi. Tidak harus langsung ada jawabannya."),
            listOf("It's natural to feel confused. We can take it slowly.", "Confusion is human. There doesn't have to be an answer right away.")))
        Emotion.HAPPY, null -> ""
    }

    private fun question(rd: Reading, l: AppLang): String {
        // Pertanyaan khusus topik, selang-seling dengan pertanyaan umum.
        val tp = rd.topic
        if (tp != null && (counters["q"] ?: 0) % 2 == 1) {
            counters["q"] = (counters["q"] ?: 0) + 1
            return topicQuestion(tp, l)
        }
        val generic = if (negTurns >= 2) l.list(
            listOf("Mau cerita lebih banyak tentang itu?", "Apa yang paling terasa berat dari semua itu?", "Apa yang kamu butuhkan paling banyak saat ini?",
                "Ada orang yang bisa kamu ajak bicara tentang ini?", "Apa yang biasanya sedikit membantumu merasa lebih baik?", "Bagian mana yang paling mengganggumu sekarang?"),
            listOf("Do you want to tell me more about it?", "What feels heaviest about all of this?", "What do you need most right now?",
                "Is there someone you can talk to about this?", "What usually helps you feel a little better?", "Which part bothers you the most right now?")
        ) else l.list(
            listOf("Mau cerita lebih banyak tentang itu?", "Apa yang paling terasa berat dari semua itu?", "Sejak kapan kamu merasa begini?",
                "Apa yang kamu butuhkan paling banyak saat ini?", "Bagian mana yang paling mengganggumu sekarang?"),
            listOf("Do you want to tell me more about it?", "What feels heaviest about all of this?", "How long have you been feeling this way?",
                "What do you need most right now?", "Which part bothers you the most right now?")
        )
        return pick("q", generic)
    }

    private fun topicQuestion(t: Topic, l: AppLang): String = when (t) {
        Topic.WORK -> l.t("Apa yang paling berat dari pekerjaanmu sekarang?", "What's the hardest part of your work right now?")
        Topic.STUDY -> l.t("Bagian mana dari kuliah atau sekolahmu yang paling menekanmu?", "Which part of your studies is pressuring you the most?")
        Topic.FAMILY -> l.t("Apa yang terjadi dengan keluargamu yang paling mengganggumu?", "What's happening with your family that bothers you most?")
        Topic.LOVE -> l.t("Apa yang paling kamu rasakan soal hubungan itu?", "What do you feel most about that relationship?")
        Topic.FRIENDS -> l.t("Apa yang terjadi dengan temanmu?", "What's going on with your friend?")
        Topic.MONEY -> l.t("Apa yang paling membuatmu khawatir soal keuangan itu?", "What worries you most about the money situation?")
        Topic.HEALTH -> l.t("Bagaimana kondisi itu memengaruhi hari-harimu?", "How is that affecting your days?")
        Topic.SLEEP -> l.t("Seperti apa tidurmu belakangan ini?", "What has your sleep been like lately?")
        Topic.FUTURE -> l.t("Apa yang paling kamu takutkan atau harapkan soal masa depan?", "What do you fear or hope for the most about the future?")
    }

    private fun summary(rd: Reading, l: AppLang): String {
        val names = rd.emotions.filter { it != Emotion.HAPPY }.take(3).map { emotionName(it, l) }
        val feel = when (names.size) {
            0 -> return l.t("Terima kasih sudah bercerita panjang. Aku mendengarkan semuanya.", "Thank you for sharing all of that. I heard it all.")
            1 -> names[0]
            2 -> names.joinToString(l.t(" dan ", " and "))
            else -> names.dropLast(1).joinToString(", ") + l.t(", dan ", ", and ") + names.last()
        }
        val about = rd.topic?.let { l.t(" yang berkaitan dengan ${topicName(it, l)}", " related to ${topicName(it, l)}") } ?: ""
        return l.t(
            "Terima kasih sudah bercerita panjang. Kalau aku tidak salah tangkap, kamu merasa $feel$about. Apakah itu sesuai?",
            "Thank you for sharing so much. If I understood correctly, you're feeling $feel$about. Does that sound right?"
        )
    }

    private fun emotionName(e: Emotion, l: AppLang): String = when (e) {
        Emotion.SAD -> l.t("sedih", "sad")
        Emotion.ANXIOUS -> l.t("cemas", "anxious")
        Emotion.ANGRY -> l.t("kesal", "frustrated")
        Emotion.TIRED -> l.t("lelah", "tired")
        Emotion.LONELY -> l.t("kesepian", "lonely")
        Emotion.GUILT -> l.t("bersalah", "guilty")
        Emotion.HOPELESS -> l.t("putus asa", "hopeless")
        Emotion.OVERWHELMED -> l.t("kewalahan", "overwhelmed")
        Emotion.GRIEF -> l.t("kehilangan", "grief")
        Emotion.SELF_WORTH -> l.t("tidak cukup baik", "not good enough")
        Emotion.NUMB -> l.t("hampa", "numb")
        Emotion.CONFUSED -> l.t("bingung", "confused")
        Emotion.HAPPY -> l.t("lega", "relieved")
    }

    private fun topicName(t: Topic, l: AppLang): String = when (t) {
        Topic.WORK -> l.t("pekerjaan", "work")
        Topic.STUDY -> l.t("kuliah atau sekolah", "your studies")
        Topic.FAMILY -> l.t("keluarga", "family")
        Topic.LOVE -> l.t("hubungan", "a relationship")
        Topic.FRIENDS -> l.t("pertemanan", "friends")
        Topic.MONEY -> l.t("keuangan", "money")
        Topic.HEALTH -> l.t("kesehatan", "health")
        Topic.SLEEP -> l.t("tidur", "sleep")
        Topic.FUTURE -> l.t("masa depan", "the future")
    }

    private fun referral(l: AppLang): String = l.t(
        "Kalau perasaan seperti ini sudah berlangsung lama atau mengganggu aktivitasmu, berbicara dengan psikolog atau psikiater " +
            "bisa sangat membantu, dan itu langkah yang kuat, bukan tanda kelemahan. Healing119 (${CrisisResources.healingId()}, gratis) " +
            "bisa jadi titik awal. Aku tetap di sini kalau kamu mau lanjut bercerita.",
        "If feelings like this have lasted a long time or are getting in the way of your daily life, talking to a psychologist or " +
            "psychiatrist can help a lot, and it's a strong step, not a sign of weakness. In Indonesia, Healing119 " +
            "(${CrisisResources.healingEn()}, free) can be a starting point. I'm still here if you want to keep talking."
    )

    private fun identity(l: AppLang): String = l.t(
        "Aku Nine, asisten digital. Aku bukan psikolog atau konselor profesional, tapi aku bisa menjadi pendengar yang tenang " +
            "untukmu. Kalau kamu butuh bantuan profesional, aku senang membantumu menyemangati langkah itu. Apa yang ingin kamu ceritakan?",
        "I'm Nine, a digital assistant. I'm not a psychologist or a professional counselor, but I can be a calm listener for you. " +
            "If you need professional help, I'm glad to support you in taking that step. What would you like to tell me?"
    )

    private fun diagnosis(l: AppLang): String = l.t(
        "Aku tidak bisa mendiagnosis, dan aku bukan psikolog. Yang kamu rasakan itu nyata dan layak diperhatikan. " +
            "Untuk tahu pastinya, psikolog atau psikiater bisa menilai langsung. Sementara itu, mau ceritakan apa yang paling mengganggumu?",
        "I can't diagnose anything, and I'm not a psychologist. What you feel is real and deserves attention. " +
            "To know for sure, a psychologist or psychiatrist can assess you in person. Meanwhile, what bothers you the most?"
    )

    private fun advice(e: Emotion?, l: AppLang): String {
        val items = when (e) {
            Emotion.ANXIOUS, Emotion.OVERWHELMED -> l.t(
                "1. Tarik napas pelan (4 detik masuk, 6 detik keluar) beberapa kali.\n2. Tulis semua yang kamu khawatirkan, lalu tandai mana yang bisa kamu kendalikan.\n3. Pilih satu langkah paling kecil untuk hari ini.",
                "1. Breathe slowly (in for 4, out for 6) a few times.\n2. Write down everything you're worried about, then mark what you can control.\n3. Pick the smallest possible step for today.")
            Emotion.ANGRY -> l.t(
                "1. Beri jeda sebelum merespons: jalan sebentar atau minum air.\n2. Tuliskan semua yang kamu rasakan tanpa disaring.\n3. Setelah lebih tenang, pikirkan apa yang kamu butuhkan dan sampaikan dengan kalimat \"aku merasa ...\".",
                "1. Pause before responding: take a short walk or drink some water.\n2. Write down everything you feel, unfiltered.\n3. Once calmer, think about what you need and say it as \"I feel ...\".")
            Emotion.HOPELESS, Emotion.NUMB, Emotion.GRIEF, Emotion.SELF_WORTH -> l.t(
                "1. Jangan menanggungnya sendirian: ceritakan ke satu orang yang kamu percaya.\n2. Jaga hal dasar dulu: makan, minum, dan istirahat.\n3. Pertimbangkan bicara dengan psikolog atau konselor; Healing119 (${CrisisResources.healingId()}) bisa jadi langkah awal.",
                "1. Don't carry it alone: tell one person you trust.\n2. Look after the basics first: food, water, and rest.\n3. Consider talking to a psychologist or counselor; in Indonesia, Healing119 (${CrisisResources.healingEn()}) can be a first step.")
            else -> l.t(
                "1. Izinkan dirimu merasa seperti ini tanpa menghakimi.\n2. Lakukan satu hal kecil yang menenangkan: mandi air hangat, jalan sebentar, atau minum yang hangat.\n3. Hubungi satu orang yang kamu percaya; cukup bilang \"aku sedang kurang baik\".",
                "1. Allow yourself to feel this way without judging it.\n2. Do one small soothing thing: a warm shower, a short walk, or a warm drink.\n3. Reach out to one person you trust; it's enough to say \"I'm not doing so well\".")
        }
        return l.t(
            "Aku bukan ahli, tapi ada beberapa hal kecil yang sering membantu. Pilih yang terasa cocok:\n$items\n\nMana yang paling mungkin kamu coba sekarang?",
            "I'm not an expert, but a few small things often help. Pick what feels right:\n$items\n\nWhich one feels most doable right now?"
        )
    }

    private fun exerciseFor(e: Emotion?, rd: Reading): Exercise = when (e) {
        Emotion.ANXIOUS -> if (rd.intense) Exercise.GROUNDING else Exercise.BREATHING
        Emotion.OVERWHELMED, Emotion.ANGRY, Emotion.TIRED -> Exercise.BREATHING
        Emotion.GUILT, Emotion.SELF_WORTH -> Exercise.SELF_COMPASSION
        else -> Exercise.WRITE_DOWN
    }

    private fun offerText(ex: Exercise, l: AppLang): String {
        val name = when (ex) {
            Exercise.BREATHING -> l.t("napas pelan", "slow breathing")
            Exercise.GROUNDING -> l.t("teknik grounding 5-4-3-2-1", "the 5-4-3-2-1 grounding technique")
            Exercise.WRITE_DOWN -> l.t("menuliskan isi kepalamu", "writing down what's on your mind")
            Exercise.SELF_COMPASSION -> l.t("latihan bersikap baik pada diri sendiri", "a short self-kindness exercise")
        }
        return l.t(
            "Kalau kamu mau, kita bisa coba latihan singkat: $name. Mau coba? (iya / tidak)",
            "If you'd like, we can try a short exercise: $name. Want to try? (yes / no)"
        )
    }

    fun exerciseText(ex: Exercise, l: AppLang): String = when (ex) {
        Exercise.BREATHING -> l.t(
            "Yuk napas pelan bersama. Duduk dengan nyaman, biarkan bahumu turun.\n\n1. Tarik napas lewat hidung, 4 hitungan.\n2. Tahan 2 hitungan.\n3. Hembuskan pelan lewat mulut, 6 hitungan.\n\nUlangi 4–5 kali, tanpa terburu-buru. Kalau sudah, ceritakan bagaimana rasanya sekarang.",
            "Let's breathe slowly together. Sit comfortably and let your shoulders drop.\n\n1. Breathe in through your nose for 4 counts.\n2. Hold for 2 counts.\n3. Breathe out slowly through your mouth for 6 counts.\n\nRepeat 4–5 times, no rush. When you're done, tell me how it feels now.")
        Exercise.GROUNDING -> l.t(
            "Coba teknik 5-4-3-2-1 untuk kembali ke saat ini. Sebutkan dalam hati:\n\n• 5 hal yang kamu lihat\n• 4 hal yang kamu rasakan lewat sentuhan\n• 3 hal yang kamu dengar\n• 2 hal yang kamu cium\n• 1 hal yang kamu kecap\n\nPelan-pelan saja. Setelah itu, ceritakan bagaimana rasanya sekarang.",
            "Try the 5-4-3-2-1 technique to come back to the present. Silently name:\n\n• 5 things you can see\n• 4 things you can feel by touch\n• 3 things you can hear\n• 2 things you can smell\n• 1 thing you can taste\n\nTake it slowly. Afterwards, tell me how it feels now.")
        Exercise.WRITE_DOWN -> l.t(
            "Coba tuliskan apa yang ada di kepalamu, tanpa disaring, di kertas atau catatan. Tidak perlu rapi atau masuk akal. Menuliskannya sering membuat beban terasa sedikit lebih ringan.\n\nKalau sudah, kamu boleh ceritakan padaku, atau cukup bilang bagaimana rasanya sekarang.",
            "Try writing down whatever is in your head, unfiltered, on paper or in a note. It doesn't need to be neat or make sense. Writing it out often makes the weight a little lighter.\n\nWhen you're done, you can tell me about it, or just say how it feels now.")
        Exercise.SELF_COMPASSION -> l.t(
            "Coba lakukan ini sebentar: letakkan tangan di dada, tarik napas pelan, lalu katakan pada dirimu, \"Ini sulit, dan wajar aku merasa begini. Aku boleh bersikap baik pada diriku sendiri.\"\n\nKalau sudah, ceritakan bagaimana rasanya sekarang.",
            "Try this for a moment: put a hand on your chest, breathe slowly, and tell yourself, \"This is hard, and it makes sense that I feel this way. I'm allowed to be kind to myself.\"\n\nWhen you're done, tell me how it feels now.")
    }

    // ------------------------------------------------------------------ AI lokal

    private fun aiPrompt(text: String, rd: Reading, l: AppLang): String = SupportPrompt.build(text, rd, l, turns)

    private fun <T> AppLang.list(id: List<T>, en: List<T>): List<T> = if (this == AppLang.EN) en else id

    private companion object {
        val UNSAFE = Regex("""^(?:tidak|belum|no|nope|not)\b|\b(?:tidak aman|belum aman|tidak merasa aman|not safe|unsafe)\b""")
        val SAFE = Regex("""^(?:iya|ya|yes|aman|aku aman|i am safe|i am okay|i am ok|baik|ok|oke|okay)\b""")
    }
}

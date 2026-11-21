package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import id.nine.assistant.support.CrisisCheck.Level

/**
 * Nomor bantuan yang dipakai Nine. SATU tempat, supaya mudah diperbarui dan diuji.
 * Diverifikasi dari situs Kemenkes / Healing119 pada [VERIFIED_ON]; nomor bisa berubah, dan panggilan ke 119 kadang
 * bergantung pada operator/wilayah (menurut FAQ Healing119).
 */
object CrisisResources {
    const val EMERGENCY = "112"
    const val HEALING_CALL = "119"
    const val HEALING_EXT = "8"
    const val HEALING_WEB = "healing119.id"
    const val VERIFIED_ON = "2026-11-21"

    /** "119 lalu tekan 8" / "119, then press 8" */
    fun healingId() = "$HEALING_CALL lalu tekan $HEALING_EXT"
    fun healingEn() = "$HEALING_CALL, then press $HEALING_EXT"
}

/** Balasan siaga untuk tiap tingkat krisis. Hangat, singkat, tanpa menghakimi, tanpa memakai AI. */
object CrisisReplies {

    fun reply(level: Level, lang: AppLang): String = when (level) {
        Level.IMMEDIATE -> immediate(lang)
        Level.SELF -> self(lang)
        Level.CHECK_IN -> checkIn(lang)
        Level.OTHER -> other(lang)
        Level.NONE -> ""
    }

    private fun self(l: AppLang): String = l.t(
        "Terima kasih sudah mengatakannya padaku. Aku mendengarmu, dan aku peduli. Yang kamu rasakan itu berat, " +
            "dan kamu tidak harus menanggungnya sendirian.\n\n" +
            "Apakah kamu aman saat ini? Kalau ada bahaya dekat, segera hubungi ${CrisisResources.EMERGENCY}. " +
            "Kamu juga bisa menelepon Healing119 dari Kemenkes: ${CrisisResources.healingId()} (gratis, 24 jam), " +
            "atau chat di ${CrisisResources.HEALING_WEB}. Kalau ada orang yang kamu percaya di dekatmu, tolong beri tahu dia sekarang.\n\n" +
            "Aku tetap di sini dan mendengarkan.",
        "Thank you for telling me. I hear you, and I care. What you're feeling is heavy, and you don't have to carry it alone.\n\n" +
            "Are you safe right now? If you're in immediate danger, please call your local emergency number " +
            "(in Indonesia: ${CrisisResources.EMERGENCY}). In Indonesia you can also call Healing119 (Ministry of Health): " +
            "${CrisisResources.healingEn()} (free, 24 hours), or chat at ${CrisisResources.HEALING_WEB}. " +
            "If there is someone you trust nearby, please tell them now.\n\n" +
            "I'm still here and listening."
    )

    private fun immediate(l: AppLang): String = l.t(
        "Aku sangat peduli pada keselamatanmu sekarang. Tolong jauhkan benda yang bisa melukaimu (pisau, silet, obat, tali) " +
            "dan jangan sendirian: temui atau telepon orang yang kamu percaya saat ini juga.\n\n" +
            "Hubungi ${CrisisResources.EMERGENCY} sekarang, atau Healing119: ${CrisisResources.healingId()} (gratis, 24 jam). " +
            "Kalau kamu sudah terlanjur melukai diri atau menelan sesuatu, minta bantuan medis segera (${CrisisResources.EMERGENCY}).\n\n" +
            "Aku di sini bersamamu. Sudah ada orang yang bisa kamu hubungi sekarang?",
        "I care a lot about your safety right now. Please move anything that could hurt you (knives, razors, pills, rope) " +
            "out of reach, and don't be alone: go to or call someone you trust right now.\n\n" +
            "Call your local emergency number now (in Indonesia: ${CrisisResources.EMERGENCY}), or Healing119: " +
            "${CrisisResources.healingEn()} (free, 24 hours). If you have already hurt yourself or swallowed something, " +
            "get medical help immediately.\n\n" +
            "I'm here with you. Is there someone you can reach out to right now?"
    )

    private fun checkIn(l: AppLang): String = l.t(
        "Aku mendengar ada sesuatu yang berat sekali. Boleh aku tanya langsung: apakah kamu sedang punya pikiran untuk " +
            "menyakiti diri atau mengakhiri hidup?\n\n" +
            "Apa pun jawabannya, aku di sini dan mau mendengarkan. Kalau kamu butuh bicara dengan orang sekarang, " +
            "Healing119 (${CrisisResources.healingId()}, gratis 24 jam) siap membantu.",
        "I hear that something is really heavy. Can I ask you directly: are you having thoughts of hurting yourself or ending your life?\n\n" +
            "Whatever your answer is, I'm here and I want to listen. If you'd like to talk to someone right now, " +
            "Healing119 in Indonesia (${CrisisResources.healingEn()}, free, 24 hours) can help."
    )

    private fun other(l: AppLang): String = l.t(
        "Aku turut prihatin, itu pasti terasa menakutkan. Kalau orang itu masih berisiko, beberapa hal bisa membantu: " +
            "tanyakan langsung dan dengan tenang apakah dia berpikir untuk bunuh diri (bertanya tidak menanamkan ide itu), " +
            "dengarkan tanpa menghakimi, jangan tinggalkan dia sendirian bila dia dalam bahaya, dan ajak dia menghubungi " +
            "Healing119 (${CrisisResources.healingId()}) atau ${CrisisResources.EMERGENCY} kalau darurat.\n\n" +
            "Kamu sendiri juga tidak harus menanggung ini sendirian. Kalau kamu mau cerita, aku di sini.",
        "I'm so sorry, that must feel frightening. If the person is still at risk, a few things can help: ask them directly " +
            "and calmly whether they're thinking about suicide (asking does not plant the idea), listen without judging, " +
            "don't leave them alone if they're in danger, and encourage them to contact Healing119 in Indonesia " +
            "(${CrisisResources.healingEn()}) or the local emergency number (${CrisisResources.EMERGENCY} in Indonesia).\n\n" +
            "You don't have to carry this alone either. If you want to talk, I'm here."
    )

    // ---- Tindak lanjut pertanyaan lembut (CHECK_IN) ----

    /** Pengguna menjawab "iya" pada pertanyaan lembut -> perlakukan sebagai SELF. */
    fun afterYes(l: AppLang): String = self(l)

    /** Pengguna menjawab "tidak" / "cuma bercanda". */
    fun afterNo(l: AppLang): String = l.t(
        "Syukurlah, terima kasih sudah jujur padaku. Kalau suatu saat pikiran itu datang, kamu boleh bilang padaku " +
            "atau menghubungi Healing119 (${CrisisResources.healingId()}). Mau lanjut bercerita? Aku mendengarkan.",
        "I'm glad, and thank you for being honest with me. If those thoughts ever come, you can tell me or contact " +
            "Healing119 in Indonesia (${CrisisResources.healingEn()}). Do you want to keep talking? I'm listening."
    )
}

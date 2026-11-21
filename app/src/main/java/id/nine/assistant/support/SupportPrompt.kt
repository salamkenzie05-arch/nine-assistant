package id.nine.assistant.support

import id.nine.assistant.core.AppLang
import id.nine.assistant.support.Feelings.Emotion
import id.nine.assistant.support.Feelings.Reading
import id.nine.assistant.support.Feelings.Topic

/**
 * Instruksi untuk model AI lokal (Gemma) agar membalas sebagai pendengar yang hangat. Dikirim di setiap giliran
 * karena instruksi sistem model dipegang oleh persona Nine. Balasan model SELALU diperiksa [SupportSafety.vet];
 * bila ditolak, dipakai balasan mesin aturan. Kotlin murni.
 */
object SupportPrompt {

    private const val MAX_USER_CHARS = 600

    fun build(text: String, rd: Reading, lang: AppLang, turns: Int): String {
        val user = text.trim().replace('"', '\'').take(MAX_USER_CHARS)
        val feel = rd.emotions.filter { it != Emotion.HAPPY }.take(3).joinToString(", ") { name(it, lang) }
        val topic = rd.topic?.let { topicName(it, lang) }
        return if (lang == AppLang.EN) {
            buildString {
                append("You are Nine, a warm, calm listener for someone who is venting. You are NOT a psychologist or a doctor.\n")
                append("Rules:\n")
                append("- Reply in English, 2 to 4 short sentences, in a gentle and human tone.\n")
                append("- Acknowledge and reflect the person's feelings in your own words, then ask exactly ONE gentle open question.\n")
                append("- Never diagnose, never name a mental disorder, never suggest medication or doses.\n")
                append("- Never judge or lecture. Never say \"move on\", \"think positive\", \"don't be sad\" or \"at least\".\n")
                append("- No lists, no markdown, no emoji, no commands (never write \"CMD:\").\n")
                append("- If it sounds very heavy, gently suggest talking to someone they trust or a professional.\n")
                if (feel.isNotEmpty()) append("Likely feelings: $feel.\n")
                if (topic != null) append("Topic: $topic.\n")
                if (turns <= 1) append("This is early in the conversation: focus on making the person feel heard.\n")
                append("The person said: \"$user\"")
            }
        } else {
            buildString {
                append("Kamu adalah Nine, pendengar yang hangat dan tenang untuk orang yang sedang curhat. Kamu BUKAN psikolog atau dokter.\n")
                append("Aturan:\n")
                append("- Balas dalam bahasa Indonesia, 2 sampai 4 kalimat pendek, dengan nada lembut dan manusiawi.\n")
                append("- Akui dan pantulkan perasaan orang itu dengan kata-katamu sendiri, lalu ajukan tepat SATU pertanyaan terbuka yang lembut.\n")
                append("- Jangan mendiagnosis, jangan menyebut nama gangguan jiwa, jangan menyarankan obat atau dosis.\n")
                append("- Jangan menghakimi atau menggurui. Jangan bilang \"move on\", \"pikirkan yang positif\", \"jangan sedih\" atau \"setidaknya\".\n")
                append("- Jangan memakai daftar, tanda markdown, emoji, atau perintah apa pun (jangan pernah menulis \"CMD:\").\n")
                append("- Jika terdengar sangat berat, sarankan dengan lembut untuk bicara dengan orang tepercaya atau profesional.\n")
                if (feel.isNotEmpty()) append("Perkiraan perasaan: $feel.\n")
                if (topic != null) append("Topik: $topic.\n")
                if (turns <= 1) append("Ini awal percakapan: fokus agar orang itu merasa didengar.\n")
                append("Ucapan orang itu: \"$user\"")
            }
        }
    }

    private fun name(e: Emotion, l: AppLang): String = when (e) {
        Emotion.SAD -> l.t("sedih", "sad")
        Emotion.ANXIOUS -> l.t("cemas", "anxious")
        Emotion.ANGRY -> l.t("kesal", "frustrated")
        Emotion.TIRED -> l.t("lelah", "tired")
        Emotion.LONELY -> l.t("kesepian", "lonely")
        Emotion.GUILT -> l.t("bersalah", "guilty")
        Emotion.HOPELESS -> l.t("putus asa", "hopeless")
        Emotion.OVERWHELMED -> l.t("kewalahan", "overwhelmed")
        Emotion.GRIEF -> l.t("kehilangan", "grieving")
        Emotion.SELF_WORTH -> l.t("merasa tidak cukup baik", "feeling not good enough")
        Emotion.NUMB -> l.t("hampa", "numb")
        Emotion.CONFUSED -> l.t("bingung", "confused")
        Emotion.HAPPY -> l.t("lega", "relieved")
    }

    private fun topicName(t: Topic, l: AppLang): String = when (t) {
        Topic.WORK -> l.t("pekerjaan", "work")
        Topic.STUDY -> l.t("kuliah atau sekolah", "studies")
        Topic.FAMILY -> l.t("keluarga", "family")
        Topic.LOVE -> l.t("hubungan", "relationship")
        Topic.FRIENDS -> l.t("pertemanan", "friends")
        Topic.MONEY -> l.t("keuangan", "money")
        Topic.HEALTH -> l.t("kesehatan", "health")
        Topic.SLEEP -> l.t("tidur", "sleep")
        Topic.FUTURE -> l.t("masa depan", "the future")
    }
}

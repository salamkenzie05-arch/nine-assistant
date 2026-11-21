package id.nine.assistant.coach

import id.nine.assistant.core.AppLang
import java.util.Locale

/** Menyusun teks umpan balik mentor: dipakai sesi latihan dan perintah "koreksi kalimat saya". */
object CoachFeedback {

    class Review(val text: String, val perfect: Boolean, val issues: List<Issue>)

    private val PRAISE = listOf(
        "Perfect! No mistakes.",
        "Great sentence, no mistakes!",
        "Well said! That was correct.",
        "Nicely put, all correct."
    )

    /** Dua bahasa untuk pengguna Indonesia: Inggris dulu (latihan), terjemahan di bawahnya. */
    fun both(lang: AppLang, en: String, id: String): String = if (lang == AppLang.ID) "$en\n\n$id" else en

    /**
     * Periksa [sentence]. [praise] memutar kalimat pujian agar tidak monoton.
     * Maksimal tiga perbaikan dan satu saran kata ditampilkan supaya muat di layar HP.
     */
    fun review(sentence: String, lang: AppLang, praise: Int = 0): Review {
        val r = GrammarCheck.check(sentence)
        val ups = WordChoice.suggest(sentence, if (r.issues.size >= 3) 0 else 1)
        val sb = StringBuilder()
        if (r.ok) {
            sb.append(PRAISE[Math.floorMod(praise, PRAISE.size)])
        } else {
            sb.append("Better: \"").append(r.corrected).append('"')
            for (i in r.issues.take(3)) {
                sb.append("\n• ").append(i.from).append(" → ").append(i.to).append(": ").append(lang.t(i.tipId, i.tipEn))
            }
            if (r.issues.size > 3) sb.append("\n(+").append(r.issues.size - 3).append(lang.t(" lagi)", " more)"))
        }
        for (u in ups) {
            sb.append("\nWord tip: \"").append(u.from).append("\" → ").append(u.options.joinToString(" / "))
            sb.append(" — ").append(lang.t(u.noteId, u.noteEn))
        }
        return Review(sb.toString(), r.ok, r.issues)
    }

    /** Balasan untuk perintah "koreksi kalimat saya: ..." di luar sesi. */
    fun correctionReply(sentence: String, lang: AppLang): String {
        val rv = review(sentence, lang)
        val head = if (rv.perfect) "Your sentence: \"${sentence.trim()}\"\n" else ""
        val tail = lang.t(
            "\n\nMau latihan lebih banyak? Katakan \"jadi mentor bahasa Inggris saya\".",
            "\n\nWant more practice? Say \"be my English mentor\"."
        )
        return head + rv.text + tail
    }

    /** Balasan untuk "kata lain dari good". */
    fun betterWordReply(word: String, lang: AppLang): String {
        val a = WordChoice.alternatives(word)
        if (a != null) {
            val list = a.options.joinToString(", ")
            return both(
                lang,
                "Instead of \"${a.word}\" you can say: $list.\nExample: ${a.example}",
                "Sebagai pengganti \"${a.word}\" kamu bisa pakai: $list."
            )
        }
        val ex = WordChoice.KNOWN_EXAMPLES.joinToString(", ")
        return both(
            lang,
            "I don't have a list for \"${word.trim()}\" yet. Try asking about: $ex.",
            "Belum ada daftar untuk \"${word.trim()}\". Coba tanya: $ex."
        )
    }

    // ------------------------------------------------------------------ deteksi bahasa Indonesia

    private val ID_WORDS = setOf(
        "saya", "aku", "kamu", "anda", "dia", "kami", "kita", "mereka", "yang", "dan", "atau", "tidak", "bukan", "belum", "sudah",
        "akan", "mau", "ingin", "bisa", "dapat", "ini", "itu", "dengan", "untuk", "dari", "ke", "di", "pada", "adalah", "apa",
        "siapa", "kenapa", "bagaimana", "kapan", "dimana", "berapa", "sangat", "sekali", "juga", "lagi", "saja", "aja", "dong",
        "deh", "nih", "sih", "ya", "gak", "nggak", "ga", "enggak", "banget", "makan", "minum", "pergi", "pulang", "rumah",
        "kerja", "sekolah", "teman", "terima", "kasih", "selamat", "pagi", "malam", "siang", "sore", "halo", "kemarin", "besok",
        "hari", "sama", "karena", "tapi", "jadi", "kalau", "kalo", "sedang", "lagi", "punya", "ada", "masih"
    )

    /** Ucapan yang jelas bahasa Indonesia (bukan Inggris): tidak diperiksa grammar-nya. */
    fun looksIndonesian(text: String): Boolean {
        val w = Regex("[A-Za-z]+").findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
        if (w.isEmpty()) return false
        val hits = w.count { it in ID_WORDS }
        return hits >= 1 && hits * 100 >= w.size * 40 && (hits >= 2 || w.size <= 3)
    }

    fun wordCount(text: String): Int = Regex("[A-Za-z']+").findAll(text).count()
}

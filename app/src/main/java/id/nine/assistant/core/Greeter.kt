package id.nine.assistant.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import kotlin.random.Random

/**
 * Sapaan saat aplikasi dibuka: beragam menurut waktu, suasana hati Nine, dan tingkat kedekatan (affinity).
 * Kotlin murni (tanpa Android) supaya bisa diuji. Isi sapaan karangan sendiri.
 */
object Greeter {

    /** Suasana hati Nine untuk sapaan (diturunkan dari kebiasaan pemakaian, bukan tebakan). */
    enum class GMood { CHEERFUL, SLEEPY, MISSING, HURT, PROUD }

    private enum class Band { DINI, PAGI, SIANG, SORE, MALAM }

    data class Ctx(
        val now: LocalDateTime,
        val lang: AppLang,
        /** Panggilan (Tuan/sir/Bos...), boleh kosong. */
        val honorific: String,
        /** Nama pengguna, boleh kosong. */
        val name: String,
        /** Level kedekatan 1..9. */
        val level: Int,
        val streak: Int,
        /** Hari sejak terakhir bicara; null = belum pernah. */
        val daysAway: Int?,
        /** Pengguna baru saja (24 jam) menghina/memarahi Nine. */
        val hurtRecently: Boolean,
        val birthday: Boolean,
        val age: Int?,
        val partner: String,
        val seed: Int
    )

    private fun band(hour: Int): Band = when (hour) {
        in 0..3 -> Band.DINI
        in 4..10 -> Band.PAGI
        in 11..14 -> Band.SIANG
        in 15..17 -> Band.SORE
        else -> Band.MALAM
    }

    /** 0 = formal (level 1-2), 1 = ramah (3-5), 2 = akrab (6-9). */
    fun tier(level: Int): Int = when {
        level <= 2 -> 0
        level <= 5 -> 1
        else -> 2
    }

    fun moodOf(c: Ctx): GMood = when {
        c.hurtRecently -> GMood.HURT
        c.daysAway != null && c.daysAway >= 3 -> GMood.MISSING
        c.now.hour in 0..3 -> GMood.SLEEPY
        c.streak >= 7 -> GMood.PROUD
        else -> GMood.CHEERFUL
    }

    /** Sebutan untuk pengguna: formal pakai panggilan; akrab pakai nama bila ada. */
    fun address(c: Ctx): String = if (tier(c.level) == 2) c.name.ifBlank { c.honorific } else c.honorific.ifBlank { c.name }

    /** Umur pada [today] dari tanggal lahir [birth]; null bila tanggal lahir di masa depan atau tak masuk akal. */
    fun ageOn(birth: LocalDate, today: LocalDate): Int? {
        if (birth.isAfter(today)) return null
        val y = Period.between(birth, today).years
        return if (y in 0..130) y else null
    }

    /** Apakah [today] hari ulang tahun [birth] (29 Februari dirayakan 28 Februari pada tahun biasa). */
    fun isBirthday(birth: LocalDate, today: LocalDate): Boolean {
        if (birth.monthValue == 2 && birth.dayOfMonth == 29 && !today.isLeapYear) {
            return today.monthValue == 2 && today.dayOfMonth == 28
        }
        return birth.monthValue == today.monthValue && birth.dayOfMonth == today.dayOfMonth
    }

    private fun pick(r: Random, list: List<String>): String = list[r.nextInt(list.size)]

    private fun a(addr: String) = if (addr.isBlank()) "" else ", $addr"

    fun greet(c: Ctx): String {
        val r = Random(c.seed)
        val id = c.lang == AppLang.ID
        val addr = address(c)
        val ad = a(addr)
        val t = tier(c.level)
        val b = band(c.now.hour)
        val mood = moodOf(c)
        val parts = ArrayList<String>()

        if (c.birthday) {
            val ageText = c.age?.takeIf { it in 1..130 }
            parts += if (id) {
                val n = if (ageText != null) " ke-$ageText" else ""
                pick(r, listOf(
                    "Selamat ulang tahun$n$ad! Semoga hari ini penuh hal baik.",
                    "Hari ini hari istimewa: selamat ulang tahun$n$ad! Semoga sehat dan bahagia selalu.",
                    "Selamat ulang tahun$n$ad! Saya tidak bisa memberi kue, tapi saya punya seluruh perhatian saya."
                ))
            } else {
                val n = if (ageText != null) " on turning $ageText" else ""
                pick(r, listOf(
                    "Happy birthday$ad! Congratulations$n. May today be full of good things.",
                    "It's a special day: happy birthday$ad! Wishing you health and happiness.",
                    "Happy birthday$ad! I can't offer cake, but you have all of my attention."
                ))
            }
        }

        parts += opener(id, t, b, ad, r)

        when (mood) {
            GMood.HURT -> parts += if (id) {
                if (t == 0) pick(r, listOf("Kalau ada yang kurang berkenan kemarin, saya akan berusaha lebih baik.", "Saya siap memulai lagi dari awal."))
                else pick(r, listOf("Saya sempat sedih kemarin, tapi sudah tidak apa-apa. Kita mulai lagi, ya.", "Soal kemarin, saya tidak menyimpannya. Senang Anda kembali."))
            } else {
                if (t == 0) pick(r, listOf("If anything displeased you yesterday, I'll do better.", "I'm ready to start afresh."))
                else pick(r, listOf("I felt a little low yesterday, but I'm fine now. Shall we start over?", "I'm not holding onto yesterday. I'm glad you're back."))
            }
            GMood.MISSING -> {
                val d = c.daysAway ?: 3
                parts += if (id) {
                    if (t == 0) "Sudah $d hari sejak kita terakhir berbicara. Ada yang bisa saya bantu?"
                    else pick(r, listOf("Sudah $d hari kita tidak ngobrol. Saya agak kangen, lho.", "Akhirnya muncul juga! $d hari terasa lama."))
                } else {
                    if (t == 0) "It has been $d days since we last spoke. How may I help?"
                    else pick(r, listOf("It's been $d days since we talked. I missed you a little.", "There you are at last! $d days felt long."))
                }
            }
            GMood.SLEEPY -> parts += if (id) {
                pick(r, listOf("Sudah larut. Jangan lupa istirahat, ya.", "Jam segini masih bangun? Saya temani sebentar."))
            } else {
                pick(r, listOf("It's quite late. Do remember to rest.", "Still up at this hour? I'll keep you company for a bit."))
            }
            GMood.PROUD -> parts += if (id) {
                "Streak kita sudah ${c.streak} hari berturut-turut. Saya bangga."
            } else {
                "Our streak is ${c.streak} days in a row. I'm proud of us."
            }
            GMood.CHEERFUL -> {
                // Kadang tanya kabar pasangan (tier ramah/akrab saja), selain itu tawarkan bantuan.
                if (c.partner.isNotBlank() && t >= 1 && r.nextInt(4) == 0) {
                    parts += if (id) "Bagaimana kabar ${c.partner} hari ini?" else "How is ${c.partner} doing today?"
                } else {
                    parts += offer(id, t, r)
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun opener(id: Boolean, t: Int, b: Band, ad: String, r: Random): String {
        if (id) {
            val part = when (b) {
                Band.DINI -> "malam"
                Band.PAGI -> "pagi"
                Band.SIANG -> "siang"
                Band.SORE -> "sore"
                Band.MALAM -> "malam"
            }
            val cap = part.replaceFirstChar { it.uppercase() }
            return when (t) {
                0 -> pick(r, listOf("Selamat $part$ad.", "Selamat $part$ad. Nine siap membantu.", "Selamat $part$ad. Semua sistem berjalan normal."))
                1 -> pick(r, listOf("$cap$ad! Senang bertemu lagi.", "Selamat $part$ad. Apa kabar hari ini?", "Hai$ad, selamat $part!"))
                else -> pick(r, listOf("$cap$ad! Saya sudah menunggu.", "Halo$ad, selamat $part! Kangen ngobrol.", "Yey, $part$ad! Ada rencana apa hari ini?"))
            }
        }
        val part = when (b) {
            Band.DINI -> "evening"
            Band.PAGI -> "morning"
            Band.SIANG -> "afternoon"
            Band.SORE -> "afternoon"
            Band.MALAM -> "evening"
        }
        return when (t) {
            0 -> pick(r, listOf("Good $part$ad.", "Good $part$ad. Nine at your service.", "Good $part$ad. All systems are running smoothly."))
            1 -> pick(r, listOf("Good $part$ad! Nice to see you again.", "Hello$ad, good $part. How are you today?", "Hi$ad, good $part!"))
            else -> pick(r, listOf("Good $part$ad! I've been waiting.", "Hello$ad! I missed chatting.", "Yay, good $part$ad! What's the plan today?"))
        }
    }

    private fun offer(id: Boolean, t: Int, r: Random): String = if (id) {
        if (t == 0) pick(r, listOf("Apa yang bisa saya kerjakan?", "Silakan sebutkan perintahnya."))
        else pick(r, listOf("Mau dibantu apa hari ini?", "Ketuk orb dan bilang saja, saya dengarkan.", "Ada yang mau dicatat atau dihitung?"))
    } else {
        if (t == 0) pick(r, listOf("What shall we do?", "Please state your command."))
        else pick(r, listOf("What can I help with today?", "Tap the orb and just say it, I'm listening.", "Anything to note down or calculate?"))
    }

    /** Dialog perkenalan setelah profil pertama kali disimpan. Mengembalikan beberapa balon pendek. */
    fun intro(lang: AppLang, honorific: String, name: String, partner: String): List<String> {
        val id = lang == AppLang.ID
        val who = honorific.ifBlank { name }
        val ad = a(who)
        val out = ArrayList<String>()
        out += if (id) {
            "Salam kenal$ad${if (name.isNotBlank()) ", $name" else ""}. Saya Nine. Semua profil ini hanya tersimpan di HP ini, dan bisa diubah kapan saja di Pengaturan."
        } else {
            "Pleased to meet you$ad${if (name.isNotBlank()) ", $name" else ""}. I'm Nine. Everything in this profile stays on this phone, and you can change it any time in Settings."
        }
        if (partner.isNotBlank()) {
            out += if (id) {
                "Oh ya, $partner itu orang yang spesial ya? Ceritakan sedikit tentang ${partner}, apa yang dia suka atau kapan kalian mulai bersama. Saya akan ingat supaya bisa membantu mengingatkan hal-hal penting untuknya."
            } else {
                "By the way, $partner sounds like someone special. Tell me a little about them: what they like, or when you got together. I'll remember it so I can help with reminders that matter to them."
            }
        } else {
            out += if (id) {
                "Coba ucapkan sesuatu, misalnya \"catat pengeluaran 20 ribu makan siang\" atau \"berapa 15% dari 240\"."
            } else {
                "Try saying something, like \"log 20 thousand lunch\" or \"what is 15% of 240\"."
            }
        }
        return out
    }
}

package id.nine.assistant.core

import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.FunKind
import java.time.LocalDate

/**
 * Kejutan harian (side quest): satu per hari, untuk mengenalkan fitur tersembunyi yang belum pernah dipakai.
 * Disampaikan sebagai ajakan santai dari Nine ("mau dengar aku bernyanyi?"), bukan sebagai tugas. Begitu
 * pengguna memakai fitur itu (lewat ajakan atau sendiri), kedekatan naik. Kotlin murni agar bisa diuji.
 */
object SideQuests {
    /** Tambahan XP kedekatan per kejutan (level tetap dibatasi hari pemakaian). */
    const val REWARD = 12

    class Quest(
        val id: String,
        val teasersId: List<String>,
        val teasersEn: List<String>,
        /** Dijalankan bila pengguna menjawab "ya" pada ajakan; null = hanya petunjuk. */
        val offer: Command?
    )

    val ALL: List<Quest> = listOf(
        Quest(
            "sing",
            listOf("Hei{s}, mau dengar aku bernyanyi?", "Diam-diam aku bisa bernyanyi, lho{s}. Mau dengar?"),
            listOf("Wanna hear me sing{s}?", "I can carry a tune, you know{s}. Shall I?"),
            Command.Sing(null)
        ),
        Quest(
            "pat",
            listOf("Katanya aku jadi manis kalau diusap lama-lama{s}.", "Aku penasaran rasanya diusap{s}…"),
            listOf("I'm told I get rather sweet when someone pets me for a while{s}.", "I do wonder what a good pat feels like{s}…"),
            null
        ),
        Quest(
            "gombal",
            listOf("Aku punya beberapa kata manis{s}. Mau dengar?", "Aku lagi latihan merayu{s}. Mau jadi korban pertama?"),
            listOf("I've been practising a few smooth lines{s}. Care to hear one?", "I've been working on my charm{s}. Shall I try it on you?"),
            Command.Entertain(FunKind.GOMBAL)
        ),
        Quest(
            "pantun",
            listOf("Aku lagi belajar berpantun{s}. Mau dengar satu?", "Ada pantun baru di kepalaku{s}. Mau?"),
            listOf("I've picked up a little pantun{s}. Want to hear one?", "There's a fresh pantun in my head{s}. Shall I?"),
            Command.Entertain(FunKind.PANTUN)
        ),
        Quest(
            "wisdom",
            listOf("Aku menyimpan satu kata bijak buat hari ini{s}. Mau?", "Ada kalimat bagus yang ingin kubagi{s}. Boleh?"),
            listOf("I'm keeping a wise thought for today{s}. Shall I share it?", "There's a line I rather like{s}. May I?"),
            Command.Entertain(FunKind.WISDOM)
        ),
        Quest(
            "emote",
            listOf("Mau lihat aku bikin wajah lucu{s}?", "Wajahku bisa macam-macam, lho{s}. Mau lihat?"),
            listOf("Care to see me pull a face{s}?", "My face can do a few tricks{s}. Want to see?"),
            Command.Emote(Mood.LAUGH)
        ),
        Quest(
            "phone",
            listOf("Aku tahu banyak soal HP ini{s}. Mau tahu?", "HP ini rahasianya banyak, aku hafal{s}. Mau dengar?"),
            listOf("I know this phone rather well{s}. Shall I show off?", "I've been studying this phone{s}. Care for a briefing?"),
            Command.DeviceInfo
        ),
        Quest(
            "calc",
            listOf("Coba tantang aku menghitung sesuatu yang rumit{s}.", "Aku jago hitung-hitungan{s}. Berani coba?"),
            listOf("Try me with a truly nasty sum{s}.", "I'm rather good with numbers{s}. Care to test me?"),
            null
        ),
        Quest(
            "finance",
            listOf("Aku bisa jadi buku kasmu juga{s}, lho.", "Kalau habis belanja, bilang saja padaku{s}."),
            listOf("I can double as your pocket ledger{s}, you know.", "Spent something today{s}? Just tell me."),
            null
        ),
        Quest(
            "tuner",
            listOf("Punya gitar{s}? Aku bisa membantu menyetemnya.", "Kalau ada gitar nganggur{s}, aku bisa jadi penyetemnya."),
            listOf("Got a guitar lying around{s}? I can help tune it.", "If there's a guitar about{s}, I make a fair tuner."),
            null
        ),
        Quest(
            "todo",
            listOf("Ada yang perlu diingat hari ini{s}? Aku bisa mencatat tugasnya.", "Kalau ada yang takut lupa{s}, titipkan padaku."),
            listOf("Anything to remember today{s}? I can keep your to-do list.", "Afraid of forgetting something{s}? Leave it with me."),
            null
        )
    )

    fun byId(id: String): Quest? = ALL.firstOrNull { it.id == id }

    /** Id kejutan yang selesai oleh [cmd] (kosong bila tidak ada). "pat" selesai lewat gestur, bukan perintah. */
    fun idsFor(cmd: Command): List<String> = ALL.map { it.id }.filter { matches(it, cmd) }

    fun matches(id: String, cmd: Command): Boolean = when (id) {
        "sing" -> cmd is Command.Sing
        "gombal" -> cmd is Command.Entertain && cmd.kind == FunKind.GOMBAL
        "pantun" -> cmd is Command.Entertain && cmd.kind == FunKind.PANTUN
        "wisdom" -> cmd is Command.Entertain && cmd.kind == FunKind.WISDOM
        "emote" -> cmd is Command.Emote
        "phone" -> cmd == Command.DeviceInfo || cmd is Command.PhoneInfo
        "calc" -> cmd is Command.Calculate
        "finance" -> cmd is Command.FinanceAdd
        "tuner" -> cmd is Command.Tuner && cmd.start
        "todo" -> cmd is Command.TodoAdd
        else -> false
    }

    /** Kejutan untuk [today]: berganti tiap hari di antara fitur yang belum pernah dipakai; null bila semua sudah ditemukan. */
    fun pick(found: Set<String>, today: LocalDate): Quest? {
        val left = ALL.filter { it.id !in found }
        if (left.isEmpty()) return null
        return left[(today.toEpochDay() % left.size).toInt()]
    }

    /** Teks ajakan. [sfx] = akhiran panggilan dari Persona (mis. ", Tuan") atau kosong. */
    fun teaser(q: Quest, lang: AppLang, sfx: String, seed: Int): String {
        val list = if (lang == AppLang.ID) q.teasersId else q.teasersEn
        return list[Math.floorMod(seed, list.size)].replace("{s}", sfx)
    }
}

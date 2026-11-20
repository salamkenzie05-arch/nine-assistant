package id.nine.assistant.audio

/**
 * Melodi lagu tradisional/domain publik yang bisa "dinyanyikan" Nine (hanya nada, tanpa lirik).
 * Notasi: "C4:1 D4:1 E4:2" = nama nada + oktaf, titik dua, panjang dalam ketukan. "R:1" = jeda. '#' = kres.
 * Kotlin murni.
 */
object Songs {

    data class Note(val midi: Int, val beats: Double) {
        val rest: Boolean get() = midi < 0
    }

    data class Song(val id: String, val titleId: String, val titleEn: String, val aliases: List<String>, val bpm: Int, val notation: String) {
        val notes: List<Note> by lazy { parse(notation) }
    }

    private val NAMES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** Ubah notasi teks menjadi daftar nada. Token yang tidak valid dilewati. */
    fun parse(text: String): List<Note> {
        val out = ArrayList<Note>()
        for (tok in text.trim().split(Regex("\\s+"))) {
            if (tok.isEmpty() || tok == "|") continue
            val parts = tok.split(':')
            val name = parts[0]
            val beats = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (name.equals("R", ignoreCase = true)) {
                out.add(Note(-1, beats))
                continue
            }
            val base = NAMES[name[0].uppercaseChar()] ?: continue
            var i = 1
            var semi = 0
            while (i < name.length && (name[i] == '#' || name[i] == 'b')) {
                semi += if (name[i] == '#') 1 else -1
                i++
            }
            val octave = name.substring(i).toIntOrNull() ?: continue
            out.add(Note(12 * (octave + 1) + base + semi, beats))
        }
        return out
    }

    val ALL: List<Song> = listOf(
        Song(
            "birthday", "Selamat Ulang Tahun", "Happy Birthday",
            listOf("ulang tahun", "happy birthday", "birthday", "selamat ulang tahun"), 96,
            "G4:.75 G4:.25 A4:1 G4:1 C5:1 B4:2 | G4:.75 G4:.25 A4:1 G4:1 D5:1 C5:2 | " +
                "G4:.75 G4:.25 G5:1 E5:1 C5:1 B4:1 A4:1 | F5:.75 F5:.25 E5:1 C5:1 D5:1 C5:2"
        ),
        Song(
            "twinkle", "Bintang Kecil", "Twinkle Twinkle Little Star",
            listOf("bintang kecil", "twinkle", "twinkle twinkle", "little star"), 100,
            "C4 C4 G4 G4 A4 A4 G4:2 | F4 F4 E4 E4 D4 D4 C4:2 | G4 G4 F4 F4 E4 E4 D4:2 | " +
                "G4 G4 F4 F4 E4 E4 D4:2 | C4 C4 G4 G4 A4 A4 G4:2 | F4 F4 E4 E4 D4 D4 C4:2"
        ),
        Song(
            "jingle", "Jingle Bells", "Jingle Bells",
            listOf("jingle bells", "jingle", "natal"), 120,
            "E4 E4 E4:2 E4 E4 E4:2 E4 G4 C4:1.5 D4:.5 E4:4 | F4 F4 F4:1.5 F4:.5 F4 E4 E4 E4:.5 E4:.5 E4 D4 D4 E4 D4:2 G4:2"
        ),
        Song(
            "ode", "Ode untuk Kegembiraan", "Ode to Joy",
            listOf("ode to joy", "ode", "beethoven", "kegembiraan"), 108,
            "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4:1.5 D4:.5 D4:2 | " +
                "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | D4:1.5 C4:.5 C4:2"
        ),
        Song(
            "jacques", "Frère Jacques", "Frère Jacques",
            listOf("frere jacques", "jacques", "are you sleeping", "brother john"), 104,
            "C4 D4 E4 C4 | C4 D4 E4 C4 | E4 F4 G4:2 | E4 F4 G4:2 | " +
                "G4:.5 A4:.5 G4:.5 F4:.5 E4 C4 | G4:.5 A4:.5 G4:.5 F4:.5 E4 C4 | C4 G3 C4:2 | C4 G3 C4:2"
        ),
        Song(
            "lamb", "Mary Punya Domba Kecil", "Mary Had a Little Lamb",
            listOf("mary had a little lamb", "little lamb", "mary", "domba kecil"), 112,
            "E4 D4 C4 D4 | E4 E4 E4:2 | D4 D4 D4:2 | E4 G4 G4:2 | E4 D4 C4 D4 | E4 E4 E4 E4 | D4 D4 E4 D4 | C4:4"
        ),
        // KARANGAN SENDIRI bergaya musik bar droid (swing, D dorian). BUKAN melodi "Cantina Band" dari Star Wars,
        // yang berhak cipta; sengaja tidak ditulis di sini. Alias "cantina" hanya mengarahkan ke lagu ini.
        Song(
            "bardroid", "Lagu Bar Droid (karangan Nine)", "Bar Droid Tune (original by Nine)",
            listOf("cantina", "cantina band", "star wars", "starwars", "bar droid", "lagu bar", "lagu cantina"), 132,
            "A3:.5 C4:.5 D4:1 R:.5 F4:.5 E4:.5 D4:.5 | C4:1 A3:1 R:.5 D4:.5 E4:1 | F4:.5 G4:.5 A4:1 G4:.5 F4:.5 E4:.5 D4:.5 | E4:2 R:1 A3:.5 C4:.5 | " +
                "D4:.5 F4:.5 A4:1 R:.5 C5:.5 A4:.5 G4:.5 | F4:1 D4:1 R:.5 E4:.5 F4:1 | G4:.5 A4:.5 Bb4:1 A4:.5 G4:.5 F4:.5 E4:.5 | D4:3 R:1 | " +
                "F4:.5 A4:.5 C5:1 A4:.5 F4:.5 A4:1 | G4:.5 Bb4:.5 D5:1 Bb4:.5 G4:.5 Bb4:1 | A4:.5 C5:.5 E5:1 D5:.5 C5:.5 A4:.5 G4:.5 | F4:1 E4:1 D4:2 | " +
                "A3:.5 C4:.5 D4:1 R:.5 F4:.5 E4:.5 D4:.5 | C4:1 A3:1 R:.5 D4:.5 E4:1 | F4:.5 G4:.5 A4:1 G4:.5 F4:.5 E4:.5 D4:.5 | D4:4"
        )
    )

    /** Lagu yang cocok dengan [query] (judul/alias), atau null. */
    fun find(query: String?): Song? {
        val q = query?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (q.isEmpty()) return null
        return ALL.firstOrNull { s -> s.aliases.any { a -> q.contains(a) || (a.length > 5 && a.contains(q) && q.length > 3) } }
    }

    /** Melodi karangan sendiri (pentatonik) yang selalu sama untuk [seed] yang sama. */
    fun improvise(seed: Int): List<Note> {
        val scale = intArrayOf(0, 2, 4, 7, 9) // C pentatonik mayor
        var s = seed * 1103515245 + 12345
        fun next(n: Int): Int {
            s = s * 1103515245 + 12345
            return ((s ushr 16) and 0x7FFF) % n
        }
        val out = ArrayList<Note>()
        var degree = 2
        repeat(16) { i ->
            degree = (degree + next(5) - 2).coerceIn(0, 9)
            val midi = 60 + scale[degree % 5] + 12 * (degree / 5)
            val beats = if (i % 4 == 3) 2.0 else if (next(3) == 0) 0.5 else 1.0
            out.add(Note(midi, beats))
        }
        out.add(Note(72, 3.0))
        return out
    }
}

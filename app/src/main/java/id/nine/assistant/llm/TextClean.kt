package id.nine.assistant.llm

/** Utilitas teks untuk keluaran LLM: membersihkan markdown dan memotong per kalimat untuk TTS. */
object TextClean {

    private val reBold = Regex("""\*\*(.+?)\*\*""")
    private val reItalic = Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])""")
    private val reHeading = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
    private val reBullet = Regex("""(?m)^\s*(?:[-*•]|\d+[.)])\s+""")
    private val reCode = Regex("""`{1,3}""")
    private val reLink = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val reEmoji = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{1F000}-\x{1F2FF}\x{FE0F}]""")
    private val reSpaces = Regex("""[ \t]{2,}""")

    /** Hapus format markdown dan emoji agar enak dibaca dan diucapkan. */
    fun clean(s: String): String {
        var t = s
        t = reLink.replace(t, "$1")
        t = reBold.replace(t, "$1")
        t = reItalic.replace(t, "$1")
        t = reHeading.replace(t, "")
        t = reBullet.replace(t, "")
        t = reCode.replace(t, "")
        t = t.replace("*", "")
        t = reEmoji.replace(t, "")
        t = reSpaces.replace(t, " ")
        return t.trim()
    }

    /**
     * Pemotong kalimat untuk teks yang datang bertahap (streaming).
     * [push] mengembalikan kalimat-kalimat yang sudah lengkap; [flush] mengembalikan sisanya.
     */
    class SentenceSplitter(private val minLength: Int = 24) {
        private val buf = StringBuilder()

        fun push(chunk: String): List<String> {
            buf.append(chunk)
            val out = ArrayList<String>()
            while (true) {
                val cut = findCut() ?: break
                val sentence = buf.substring(0, cut).trim()
                buf.delete(0, cut)
                if (sentence.isNotEmpty()) out.add(sentence)
            }
            return out
        }

        fun flush(): String? {
            val rest = buf.toString().trim()
            buf.setLength(0)
            return rest.ifEmpty { null }
        }

        /** Posisi setelah akhir kalimat pertama yang cukup panjang, atau null. */
        private fun findCut(): Int? {
            var i = 0
            while (i < buf.length) {
                val c = buf[i]
                val isEnd = c == '.' || c == '!' || c == '?' || c == '\n' || c == '。'
                if (isEnd) {
                    // Butuh karakter berikutnya untuk memastikan ini bukan angka desimal ("3.5") atau "..."
                    if (i + 1 >= buf.length) return null
                    val next = buf[i + 1]
                    val boundary = c == '\n' || next == ' ' || next == '\n' || next == '"' || next == '\''
                    val decimal = c == '.' && i > 0 && buf[i - 1].isDigit() && next.isDigit()
                    if (boundary && !decimal && (i + 1 >= minLength || c == '\n')) {
                        return i + 1
                    }
                }
                i++
            }
            return null
        }
    }
}

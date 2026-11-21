package id.nine.assistant.llm

/** Memilih riwayat obrolan yang dikirim ke AI online: cukup panjang untuk nyambung, tapi tak membengkakkan kuota token. */
object AiHistory {

    /**
     * [turns]: (dariPengguna, teks) berurutan waktu, sudah disaring (tanpa pesan pribadi). Hasil: pasangan (role, teks)
     * dengan role "user"/"model" yang bergantian dan diawali pengguna.
     */
    fun build(turns: List<Pair<Boolean, String>>, maxTurns: Int = 24, maxChars: Int = 8000, perTurn: Int = 1500): List<Pair<String, String>> {
        val picked = ArrayList<Pair<Boolean, String>>()
        var used = 0
        for ((fromUser, raw) in turns.takeLast(maxTurns).asReversed()) {
            val text = clip(raw.trim(), perTurn)
            if (text.isEmpty()) continue
            if (used + text.length > maxChars && picked.isNotEmpty()) break
            used += text.length
            picked.add(fromUser to text)
        }
        picked.reverse()
        val out = ArrayList<Pair<String, String>>()
        for ((fromUser, text) in picked) {
            val role = if (fromUser) "user" else "model"
            if (out.isNotEmpty() && out.last().first == role) out[out.size - 1] = role to (out.last().second + "\n" + text)
            else out.add(role to text)
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(' ', max).let { if (it > max / 2) it else max }
        return s.substring(0, cut).trimEnd() + "…"
    }
}

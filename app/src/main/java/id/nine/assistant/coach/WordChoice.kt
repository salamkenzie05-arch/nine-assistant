package id.nine.assistant.coach

import java.util.Locale

/** Saran kata/frasa yang lebih bagus: [from] bisa diganti salah satu dari [options]. */
data class Upgrade(val from: String, val options: List<String>, val noteEn: String, val noteId: String)

/** Daftar pengganti satu kata untuk perintah "another word for ...". */
data class Alternatives(val word: String, val options: List<String>, val example: String)

/**
 * Pilihan kata yang lebih bagus, 100% offline dari tabel kecil buatan tangan (bukan AI). Sengaja hanya memberi saran
 * pada pola yang hampir selalu bisa diperbaiki ("very good" → "excellent"); kalimat tetap benar tanpa saran ini.
 */
object WordChoice {

    private const val INTENS_EN = "One strong word sounds more natural than \"very\" + a plain word."
    private const val INTENS_ID = "Satu kata yang kuat terdengar lebih alami daripada \"very\" + kata biasa."
    private const val PLAIN_EN = "A more specific word makes your English sound richer."
    private const val PLAIN_ID = "Kata yang lebih spesifik membuat bahasa Inggrismu terdengar lebih kaya."

    /** "very X" → satu kata yang lebih kuat. */
    private val INTENSIFIED: Map<String, List<String>> = mapOf(
        "good" to listOf("excellent", "great", "wonderful"),
        "bad" to listOf("terrible", "awful"),
        "big" to listOf("huge", "enormous"),
        "small" to listOf("tiny"),
        "little" to listOf("tiny"),
        "tired" to listOf("exhausted"),
        "happy" to listOf("delighted", "thrilled"),
        "sad" to listOf("heartbroken", "miserable"),
        "hungry" to listOf("starving"),
        "beautiful" to listOf("gorgeous", "stunning"),
        "pretty" to listOf("gorgeous", "stunning"),
        "important" to listOf("crucial", "essential"),
        "difficult" to listOf("challenging", "demanding"),
        "hard" to listOf("challenging", "demanding"),
        "easy" to listOf("effortless", "straightforward"),
        "angry" to listOf("furious"),
        "scared" to listOf("terrified"),
        "afraid" to listOf("terrified"),
        "surprised" to listOf("astonished", "amazed"),
        "interesting" to listOf("fascinating"),
        "funny" to listOf("hilarious"),
        "cold" to listOf("freezing"),
        "hot" to listOf("scorching", "boiling"),
        "smart" to listOf("brilliant"),
        "sure" to listOf("certain"),
        "clean" to listOf("spotless"),
        "old" to listOf("ancient"),
        "fast" to listOf("rapid", "lightning-fast"),
        "tasty" to listOf("delicious"),
        "nice" to listOf("lovely", "delightful"),
        "busy" to listOf("swamped", "hectic"),
        "rich" to listOf("wealthy"),
        "dirty" to listOf("filthy"),
        "wet" to listOf("soaked"),
        "loud" to listOf("deafening"),
        "lucky" to listOf("fortunate"),
        "strong" to listOf("powerful"),
        "dark" to listOf("pitch-black"),
        "expensive" to listOf("pricey", "costly"),
        "cheap" to listOf("inexpensive", "affordable"),
        "kind" to listOf("caring", "generous"),
        "excited" to listOf("thrilled"),
        "worried" to listOf("anxious")
    )
    private val INTENSIFIERS = setOf("very", "really", "so", "extremely")

    /** Kata sifat biasa setelah be/linking verb → pilihan yang lebih spesifik. */
    private val AFTER_BE: Map<String, List<String>> = mapOf(
        "good" to listOf("great", "excellent", "wonderful"),
        "bad" to listOf("terrible", "awful"),
        "nice" to listOf("lovely", "pleasant"),
        "big" to listOf("huge", "large"),
        "happy" to listOf("delighted", "glad")
    )
    private val LINKING = setOf("is", "are", "was", "were", "am", "be", "looks", "look", "feels", "feel", "sounds", "sound", "tastes", "taste", "seems", "seem")

    /** Frasa umum → alternatif. Kunci = token berurutan; [startOnly] = hanya di awal klausa. */
    private class Phrase(val key: List<String>, val options: List<String>, val noteEn: String, val noteId: String, val startOnly: Boolean = false)

    private val PHRASES = listOf(
        Phrase(listOf("a", "lot", "of"), listOf("plenty of", "numerous", "many"), "\"A lot of\" is casual; these sound more polished.", "\"A lot of\" itu santai; pilihan ini terdengar lebih rapi."),
        Phrase(listOf("i", "think"), listOf("In my opinion,", "I believe"), "To share an opinion you can also say:", "Untuk menyampaikan pendapat kamu juga bisa bilang:", startOnly = true),
        Phrase(listOf("i", "want"), listOf("I would like"), "\"I would like\" is more polite than \"I want\".", "\"I would like\" lebih sopan daripada \"I want\".", startOnly = true),
        Phrase(listOf("maybe"), listOf("perhaps", "possibly"), "More formal options:", "Pilihan yang lebih formal:"),
        Phrase(listOf("because", "of", "that"), listOf("therefore", "as a result"), "Link ideas more smoothly with:", "Sambungkan gagasan dengan lebih halus:"),
        Phrase(listOf("i", "don't", "know"), listOf("I'm not sure", "I have no idea"), "Softer or stronger ways to say it:", "Cara lain yang lebih halus atau lebih kuat:", startOnly = true),
        Phrase(listOf("i", "don't", "like"), listOf("I'm not a fan of", "I dislike"), "Other ways to say it:", "Cara lain mengatakannya:", startOnly = true),
        Phrase(listOf("i", "like"), listOf("I enjoy", "I love", "I'm fond of"), "Instead of \"I like\" you can say:", "Selain \"I like\" kamu bisa bilang:", startOnly = true),
        Phrase(listOf("thank", "you"), listOf("thanks a lot", "I really appreciate it"), "Other ways to thank someone:", "Cara lain berterima kasih:", startOnly = true),
        Phrase(listOf("no", "problem"), listOf("you're welcome", "my pleasure"), "Other ways to answer thanks:", "Cara lain menjawab terima kasih:", startOnly = true)
    )

    /** Sampai [max] saran untuk kalimat [text], urutan: kata penguat dulu, lalu frasa. */
    fun suggest(text: String, max: Int = 2): List<Upgrade> {
        val w = Regex("[A-Za-z']+").findAll(text).map { it.value.lowercase(Locale.ROOT).replace('’', '\'') }.toList()
        val out = ArrayList<Upgrade>()
        // "very good" → excellent
        for (i in 0 until w.size - 1) {
            if (w[i] in INTENSIFIERS) {
                val opts = INTENSIFIED[w[i + 1]]
                if (opts != null && out.none { it.from == "${w[i]} ${w[i + 1]}" }) {
                    out.add(Upgrade("${w[i]} ${w[i + 1]}", opts, INTENS_EN, INTENS_ID))
                }
            }
        }
        // "it is good" → great
        for (i in 1 until w.size) {
            val opts = AFTER_BE[w[i]]
            if (opts != null && w[i - 1] in LINKING && out.none { it.from.endsWith(w[i]) }) {
                out.add(Upgrade(w[i], opts, PLAIN_EN, PLAIN_ID))
            }
        }
        for (p in PHRASES) {
            val n = p.key.size
            for (i in 0..w.size - n) {
                if (p.startOnly && i != 0 && w[i - 1] !in setOf("and", "but", "so", "because", "then")) continue
                if ((0 until n).all { w[i + it] == p.key[it] }) {
                    // "I think so", "I like it very much" tetap alami: lewati frasa pendek tanpa isi
                    if (p.key == listOf("i", "think") && w.getOrNull(i + 2) in setOf("so", "not", null)) continue
                    out.add(Upgrade(p.key.joinToString(" "), p.options, p.noteEn, p.noteId))
                    break
                }
            }
        }
        return out.take(max)
    }

    // ------------------------------------------------------------------ "another word for ..."

    /** nama → pilihan dipisah koma → contoh kalimat, satu baris per kata. */
    private val TABLE = """
        good|great, excellent, wonderful, fantastic, outstanding|She did an excellent job.
        bad|terrible, awful, poor, dreadful|The weather was terrible.
        big|large, huge, enormous, massive|They live in a huge house.
        small|tiny, little, compact, petite|It is a tiny village.
        happy|glad, delighted, cheerful, thrilled|I'm delighted to meet you.
        sad|unhappy, down, miserable, heartbroken|He looked miserable.
        nice|pleasant, lovely, kind, delightful|What a lovely day!
        beautiful|gorgeous, stunning, lovely, attractive|The view is stunning.
        important|crucial, essential, vital, significant|Sleep is essential for health.
        difficult|hard, challenging, tough, demanding|It was a challenging exam.
        hard|difficult, challenging, tough, demanding|It was a tough week.
        easy|simple, effortless, straightforward, painless|The task was straightforward.
        tired|exhausted, sleepy, worn out, drained|I'm exhausted after work.
        angry|mad, furious, annoyed, upset|She was furious about the delay.
        interesting|fascinating, engaging, intriguing, absorbing|That was a fascinating story.
        funny|hilarious, amusing, entertaining, witty|The movie was hilarious.
        smart|clever, intelligent, bright, brilliant|She is a brilliant student.
        fast|quick, rapid, speedy, swift|We need a quick answer.
        slow|gradual, sluggish, leisurely, unhurried|Progress was gradual.
        old|ancient, elderly, aged, vintage|It is an ancient temple.
        new|fresh, modern, recent, brand-new|She bought a brand-new phone.
        scary|frightening, terrifying, creepy, spooky|It was a terrifying night.
        tasty|delicious, flavorful, yummy, mouth-watering|The soup is delicious.
        hungry|starving, famished, ravenous|I'm starving!
        cold|chilly, freezing, icy, cool|It's freezing outside.
        hot|boiling, scorching, warm, sweltering|It was a scorching day.
        very|extremely, really, incredibly, remarkably|It's incredibly useful.
        say|mention, state, explain, announce|She mentioned a new idea.
        think|believe, consider, suppose, reckon|I believe you are right.
        like|enjoy, love, adore, appreciate|I enjoy reading.
        want|would like, wish, desire, hope for|I would like some water.
        get|receive, obtain, earn, gain|She received a prize.
        make|create, build, produce, prepare|He prepared a delicious dinner.
        look|glance, stare, gaze, examine|She gazed at the stars.
        walk|stroll, wander, hike, stride|We strolled along the beach.
        talk|chat, speak, discuss, converse|We chatted for an hour.
        help|assist, support, aid, guide|Can you assist me?
        ask|inquire, request, question, wonder|He inquired about the price.
        show|display, demonstrate, reveal, present|She demonstrated the new app.
        tell|inform, explain, notify, let know|Please inform me of any changes.
        try|attempt, test, give it a go, strive|I will attempt it again.
        start|begin, launch, commence, kick off|Let's begin the lesson.
        end|finish, conclude, complete, wrap up|We will conclude at five.
        buy|purchase, get, pick up, acquire|I purchased a new laptop.
        problem|issue, challenge, difficulty, obstacle|We found an issue.
        thing|object, item, matter, detail|That is an important detail.
        lot|plenty, great deal, large amount, ton|There is plenty of time.
        job|career, profession, position, occupation|She has a great career.
        house|home, residence, apartment, place|They bought a lovely home.
        friend|companion, buddy, mate, ally|He is my closest companion.
        money|cash, funds, income, savings|I need some cash.
        idea|plan, thought, concept, suggestion|That's a great suggestion.
        know|understand, realize, recognize, be aware of|I understand your point.
        love|adore, cherish, treasure, be fond of|I adore this song.
        hate|dislike, can't stand, detest, despise|I can't stand loud noise.
        fun|enjoyable, entertaining, exciting, amusing|The trip was enjoyable.
        sure|certain, confident, positive, convinced|I'm certain about it.
        wrong|incorrect, mistaken, inaccurate, false|That answer is incorrect.
        right|correct, accurate, exact, proper|That's the correct answer.
        very good|excellent, outstanding, superb, wonderful|Your work is outstanding.
        very bad|terrible, awful, dreadful, horrible|That was a dreadful idea.
        very big|huge, enormous, gigantic, massive|It's an enormous building.
        very small|tiny, minute, miniature|It's a miniature model.
        very tired|exhausted, drained, worn out|I'm completely drained.
        very happy|delighted, thrilled, overjoyed, ecstatic|We're thrilled with the result.
        very sad|heartbroken, devastated, miserable|She was devastated by the news.
        very important|crucial, essential, vital, critical|This step is crucial.
        very hungry|starving, famished, ravenous|I'm famished.
        very beautiful|gorgeous, stunning, breathtaking|The sunset was breathtaking.
        very difficult|challenging, demanding, daunting|It's a daunting task.
        very easy|effortless, straightforward, simple|It was effortless.
        very angry|furious, enraged, livid|He was livid.
        very interesting|fascinating, captivating, intriguing|It was a captivating talk.
        very funny|hilarious, hysterical, side-splitting|That joke was hilarious.
        very cold|freezing, frigid, icy|It's freezing today.
        very hot|scorching, boiling, sweltering|It's sweltering in here.
        very smart|brilliant, sharp, gifted|She's a gifted writer.
        very nice|lovely, delightful, charming|It was a delightful evening.
    """.trimIndent()

    private val LOOKUP: Map<String, Alternatives> by lazy {
        val m = LinkedHashMap<String, Alternatives>()
        for (line in TABLE.lines()) {
            val p = line.split("|")
            if (p.size != 3) continue
            val key = p[0].trim()
            m[key] = Alternatives(key, p[1].split(",").map { it.trim() }, p[2].trim())
        }
        m
    }

    /** Beberapa kata yang dikenali, untuk contoh di pesan "tidak ada daftar untuk kata itu". */
    val KNOWN_EXAMPLES: List<String> = listOf("good", "bad", "big", "happy", "nice", "important", "difficult", "tired", "very good")

    /** Pilihan pengganti untuk [word] (kata atau frasa pendek); null bila belum ada di tabel. */
    fun alternatives(word: String): Alternatives? {
        var k = word.lowercase(Locale.ROOT).replace('’', '\'').trim().trim('"', '\'', '.', ',', '?', '!', '“', '”')
        k = k.replace(Regex("\\s+"), " ")
        for (pre in listOf("the word ", "word ", "the ")) if (k.startsWith(pre)) k = k.removePrefix(pre)
        LOOKUP[k]?.let { return it }
        // "really good" / "so good" disamakan dengan "very good"
        val parts = k.split(" ")
        if (parts.size == 2 && parts[0] in INTENSIFIERS) LOOKUP["very " + parts[1]]?.let { return it.copy(word = k) }
        return null
    }
}

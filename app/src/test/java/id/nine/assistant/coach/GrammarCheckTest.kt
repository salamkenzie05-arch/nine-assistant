package id.nine.assistant.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes pemeriksa grammar: kalimat salah diperbaiki, dan (lebih penting) kalimat yang sudah benar TIDAK disentuh.
 */
class GrammarCheckTest {

    private fun fix(input: String, expected: String) {
        val r = GrammarCheck.check(input)
        assertEquals("input: $input", expected, r.corrected)
        assertFalse("seharusnya ada perbaikan: $input", r.ok)
    }

    private fun ok(input: String) {
        val r = GrammarCheck.check(input)
        assertTrue("kalimat benar jangan diubah: \"$input\" -> \"${r.corrected}\" ${r.issues.map { it.from + "=>" + it.to }}", r.ok)
        assertEquals(input.trim(), r.corrected)
    }

    // ------------------------------------------------------------------ perbaikan

    @Test
    fun lampauDenganPenandaWaktu() {
        fix("i goes to school yesterday", "I went to school yesterday.")
        fix("yesterday i go to the market", "Yesterday I went to the market.")
        fix("i go to school yesterday", "I went to school yesterday.")
        fix("last night i sleep early", "Last night I slept early.")
        fix("two days ago i meet him", "Two days ago I met him.")
        fix("yesterday i am sick", "Yesterday I was sick.")
        fix("last week i am in Bali", "Last week I was in Bali.")
        fix("do you go to school yesterday", "Did you go to school yesterday?")
    }

    @Test
    fun subjekKataKerja() {
        fix("she go to work every day", "She goes to work every day.")
        fix("he don't like it", "He doesn't like it.")
        fix("it don't work", "It doesn't work.")
        fix("they likes pizza", "They like pizza.")
        fix("i doesn't know", "I don't know.")
        fix("you doesn't care", "You don't care.")
        fix("she have a car", "She has a car.")
        fix("he have two sisters", "He has two sisters.")
        fix("I think he go home", "I think he goes home.")
        fix("if it rain we stay", "If it rains we stay.")
        fix("He do it every day", "He does it every day.")
    }

    @Test
    fun beSesuaiSubjek() {
        fix("we was late", "We were late.")
        fix("you is nice", "You are nice.")
        fix("it are good", "It is good.")
        fix("they was at home", "They were at home.")
        fix("he were late", "He was late.")
    }

    @Test
    fun beDanKataKerja() {
        fix("i am agree with you", "I agree with you.")
        fix("i am not understand", "I don't understand.")
        fix("he is go to school", "He is going to school.")
        fix("i'm go to school", "I'm going to school.")
        fix("he is eat now", "He is eating now.")
        fix("I am interesting in football", "I am interested in football.")
        fix("i have 20 years old", "I am 20 years old.")
        fix("i very like it", "I really like it.")
    }

    @Test
    fun beHilang() {
        fix("i happy", "I am happy.")
        fix("you happy", "You are happy.")
        fix("she very beautiful", "She is very beautiful.")
        fix("we late", "We are late.")
        fix("i tired", "I am tired.")
        fix("they hungry", "They are hungry.")
    }

    @Test
    fun negatifTanpaDo() {
        fix("i not like it", "I don't like it.")
        fix("he no come", "He doesn't come.")
        fix("i no have money", "I don't have money.")
    }

    @Test
    fun setelahDoModalDanTo() {
        fix("did you went there", "Did you go there?")
        fix("she doesn't likes coffee", "She doesn't like coffee.")
        fix("what did you went", "What did you go?")
        fix("what does he wants", "What does he want?")
        fix("where do you lives", "Where do you live?")
        fix("how do you knows", "How do you know?")
        fix("she can sings very well", "She can sing very well.")
        fix("he must goes now", "He must go now.")
        fix("they will comes tomorrow", "They will come tomorrow.")
        fix("i can to swim", "I can swim.")
        fix("you must to go", "You must go.")
        fix("i don't wanted it", "I don't want it.")
        fix("did she called you", "Did she call you?")
        fix("he didn't came", "He didn't come.")
        fix("i want to went there", "I want to go there.")
        fix("we need to goes now", "We need to go now.")
        fix("she is going to buys a car", "She is going to buy a car.")
        fix("i want go home", "I want to go home.")
    }

    @Test
    fun haveDenganParticiple() {
        fix("i have went there", "I have gone there.")
        fix("she has ate already", "She has eaten already.")
        fix("they have took it", "They have taken it.")
        fix("i have eat already", "I have eaten already.")
    }

    @Test
    fun artikel() {
        fix("an university", "A university.")
        fix("a hour ago", "An hour ago.")
        fix("an unique idea", "A unique idea.")
        fix("a interesting book", "An interesting book.")
        fix("a honest man", "An honest man.")
        fix("a apple and an book", "An apple and a book.")
    }

    @Test
    fun katadepanDanLainnya() {
        fix("I listen music every day", "I listen to music every day.")
        fix("please explain me how it works", "Please explain to me how it works.")
        fix("it depends of the weather", "It depends on the weather.")
        fix("this is different with that", "This is different from that.")
        fix("i am interested about music", "I am interested in music.")
        fix("discuss about the plan", "Discuss the plan.")
        fix("I will enter to the room", "I will enter the room.")
        fix("since 3 years", "For 3 years.")
        fix("see you in Monday", "See you on Monday.")
        fix("on the morning I drink coffee", "In the morning I drink coffee.")
        fix("at the afternoon we eat", "In the afternoon we eat.")
        fix("in morning I run", "In the morning I run.")
    }

    @Test
    fun perbandinganDanKataBenda() {
        fix("more better than before", "Better than before.")
        fix("more cheaper than that", "Cheaper than that.")
        fix("most best player", "Best player.")
        fix("it is more easy than I thought", "It is easier than I thought.")
        fix("I have a lot of homeworks", "I have a lot of homework.")
        fix("informations and advices", "Information and advice.")
    }

    @Test
    fun urutanPertanyaanDanKataGanti() {
        fix("what you are doing", "What are you doing?")
        fix("where you from", "Where are you from?")
        fix("how old you", "How old are you?")
        fix("what you doing", "What are you doing?")
        fix("what you want", "What do you want?")
        fix("where you live", "Where do you live?")
        fix("how you know", "How do you know?")
        fix("what your name", "What is your name?")
        fix("me want to go", "I want to go.")
        fix("me name is Budi", "My name is Budi.")
    }

    @Test
    fun laporanPerbaikanBerisiPenjelasan() {
        val r = GrammarCheck.check("i goes to school yesterday")
        assertEquals("i goes to school yesterday", r.original)
        assertEquals(1, r.issues.size)
        val i = r.issues[0]
        assertEquals(Topic.TENSE, i.topic)
        assertEquals("i goes", i.from)
        assertEquals("i went", i.to)
        assertTrue(i.tipEn.isNotBlank())
        assertTrue(i.tipId.isNotBlank())
    }

    @Test
    fun beberapaKesalahanDalamSatuKalimat() {
        val r = GrammarCheck.check("she go to a university and he have an book")
        assertEquals("She goes to a university and he has a book.", r.corrected)
        assertEquals(3, r.issues.size)
    }

    @Test
    fun hurufBesarAwalTetapDipertahankan() {
        assertEquals("He doesn't like it.", GrammarCheck.check("He don't like it").corrected)
        assertEquals("I went to school yesterday.", GrammarCheck.check("I goes to school yesterday").corrected)
    }

    // ------------------------------------------------------------------ kalimat benar tidak disentuh

    @Test
    fun masukanKosongAtauBukanKata() {
        assertTrue(GrammarCheck.check("").ok)
        assertTrue(GrammarCheck.check("   ").ok)
        assertTrue(GrammarCheck.check("123").ok)
        assertTrue(GrammarCheck.check("?!").ok)
    }

    @Test
    fun kalimatSehariHariYangBenar() {
        listOf(
            "I like to read books every day", "She works at a bank and he works at a school", "It rains a lot in Jakarta",
            "My brother plays football on Sundays", "They go to the market every morning", "We need to talk about it",
            "He wants to know why", "I'm going to the store", "I was at home last night", "She was very happy yesterday",
            "They were tired after the trip", "What do you want to eat tonight", "Where does she live", "How do you know that",
            "When you come home, call me", "When you come home call me", "The book that I read yesterday was great",
            "I saw a movie yesterday", "I met him two days ago", "Yesterday we went to the beach and we swam",
            "Last week I visited my grandmother", "I have been to Japan twice", "She has gone to the store", "I have never seen it",
            "Have you eaten lunch yet", "He has a car and she has a bike", "I do my homework every day", "He does his best",
            "Did you see that", "I didn't go there", "She doesn't have time", "Don't worry about it", "It doesn't matter",
            "I can speak English a little", "You should go to bed early", "He will come tomorrow", "I would like a cup of coffee",
            "I'd like to go home", "Could you help me please", "I used to live in Bali", "I'm used to getting up early",
            "He is used to working hard", "I want a new phone", "I want it now", "I need a break", "I need to go", "I need more time",
            "This is more important than that", "That is easier than I thought", "It is better than nothing", "He is taller than me",
            "This one is the best", "It is the most beautiful place", "I think it is good", "I think that he is right",
            "I know he goes there often", "I hope you feel better", "Let me know when you arrive", "Let him go", "Make him happy",
            "It made me smile", "She can play the piano", "She plays the piano well", "They are playing outside", "I am learning English",
            "He is studying for the exam", "We are going to the park", "I'm so tired", "She is busy today",
            "I am very happy to see you", "You look tired", "You are late", "Is he coming", "Are you ready", "Do you like it",
            "Does it work", "Can he swim", "Who is she", "What is that", "This is a book", "That is an apple", "I ate an orange",
            "It takes an hour", "He is an honest man", "She goes to a university", "I have an umbrella", "My name is Budi",
            "I live in Jakarta", "I work in an office", "I'm from Indonesia", "Nice to meet you", "See you on Monday",
            "I will see you in the morning", "I go to school in the morning", "We had dinner at eight", "He is good at math",
            "I'm interested in music", "She is afraid of dogs", "I agree with you", "I don't agree", "We are looking forward to it",
            "I look forward to meeting you", "Thanks for your help", "I miss you", "He misses her", "I love you",
            "They love each other", "I like him", "He likes me", "They told me", "She asked him", "I'm sorry",
            "He said that he was tired", "I think he is tired", "what time is it", "how much is this", "how are you today",
            "why are you late", "I do not know", "She does not like it", "I read a book yesterday", "Yesterday was a good day",
            "Tomorrow is Monday", "My phone is old"
        ).forEach { ok(it) }
    }

    @Test
    fun polaMiripKesalahanTapiSahDibiarkan() {
        listOf(
            "I think yesterday was fun", "I told him yesterday that I love him", "What did you do yesterday?", "let it go",
            "make it work", "I like it when it rains", "my brother lives in Bali", "I will go tomorrow", "If I were you, I would go",
            "It's an honest answer", "I can't believe it", "I need help", "I need sleep", "he like a boss",
            "yesterday i went to the market and i buy fruit", "I have a book", "Will works at a bank", "if he were here",
            "i wish i were rich", "that movie is interesting in many ways", "please explain me this", "I want you to go",
            "You need to see this", "I want him to come", "Will you go with me", "May I go now", "Can you help me",
            "Mark loves her", "She does the dishes every night", "He has to leave", "I don't have to go", "I could have gone earlier",
            "I like cooking", "They are cooks", "What are you doing here", "I think you are right", "I saw him leave", "Let me see",
            "It looks good", "He is working at home", "She said she was happy", "He hit me yesterday", "I read it yesterday",
            "Yesterday I fixed it", "I like you", "She wants to dance", "Where do you go", "Do you want to go", "Why did he leave",
            "Where did you buy it", "What did she say", "How did you do that", "Who are you", "Who does she like", "When will you come",
            "I have to do it", "I have done it", "You have to go", "We have to talk", "It has to work", "I need to eat",
            "I wanted to see you", "He wanted to go", "I went to see him", "I'm going to see him", "Going to the park is fun",
            "Having fun is important", "They have a dog", "You have a nice house", "I have no idea", "She has no money",
            "I feel happy", "You seem tired", "He looks sad", "She became a teacher", "He is tall and she is short",
            "My sister is very smart", "I'm hungry", "You're right", "He's late", "They're here", "We're ready", "It's ok",
            "I've seen it", "I'd go", "He's gone", "That's what I said", "This is what I want", "There is a problem",
            "There are two cats", "Here is your coffee", "How about you", "What about me", "Thank you for coming", "Good night",
            "Happy birthday", "Excuse me", "I can't wait", "She can't come", "We won't go", "They didn't know", "He doesn't work here",
            "I have been working here since 2020", "I have lived here for three years", "I have worked here for 2 years",
            "an X-ray", "an MRI", "a USB cable", "an SMS", "an herb", "a one-time offer", "an hour later", "a European city",
            "a useful tip", "an unusual story", "an umbrella", "in the morning we eat", "on Monday morning we go",
            "on the morning of the exam", "in morning traffic I wait"
        ).forEach { ok(it) }
    }

    @Test
    fun hurufBesarDanTandaBacaBukanKesalahan() {
        ok("i went to school yesterday")
        ok("she likes music")
        ok("What are you doing?")
        ok("I went there, and she came too!")
    }
}

package id.nine.assistant.fit

import id.nine.assistant.nlu.NumberWords

/**
 * Mengenali permintaan fitness/gizi dari ucapan. Sengaja ketat: butuh kata kunci khas (nama gerakan + angka, kata makan +
 * nama makanan, "inbody", dst.) supaya tidak menyerobot perintah lain atau obrolan biasa. Kotlin murni.
 */
object HealthIntent {
    private fun r(p: String) = Regex(p)

    private val MONEY = r("""\b(?:ribu|rb|rp|rupiah|juta|jt|harga|\d+\s?k|bayar|beli|membeli|transfer|gaji)\b""")
    private val WANT_OR_ASK = r("""\b(?:mau|ingin|pengen|pingin|akan|nanti|rencana|enaknya|sebaiknya|rekomendasi|saran|apa|gimana|bagaimana|berapa|boleh|haruskah|kenapa|mengapa|yuk|ayo|mari|kuy|jom|should|want|going to|will|plan|what|how|can i|why|let s|lets)\b""")
    private val MEAL_VERB = r("""\b(?:makan|sarapan|minum|ngemil|nyemil|konsumsi|menu|ate|had|drank|eat|drink|breakfast|lunch|dinner|snack|log meal|log food|catat makanan|catat makan)\b""")
    private val QUESTION_CUE = r("""\b(?:apa|apakah|berapa|bagaimana|gimana|kenapa|mengapa|kapan|bolehkah|haruskah|sebaiknya|manfaat|fungsi|sumber|cara|tips|info|informasi|jelaskan|ceritakan|ilmu|penjelasan|tahu|what|how|why|when|should i|can i|do i|is it|are there|tell me|explain|benefits|sources|bahaya|aman|safe)\b""")

    private val EATING = listOf(
        r("""\bmuntah(?:in|kan)?\s+(?:makanan|lagi|setelah makan|habis makan|biar|agar|supaya)"""),
        r("""\b(?:sengaja )?muntah\s+(?:agar|biar|supaya)\s+(?:kurus|tidak gemuk|nggak gemuk)"""),
        r("""\b(?:pencahar|laxative|purging|purge|binge)\b"""),
        r("""\b(?:make myself|force myself to)\s+(?:throw up|vomit)|\bthrow up (?:after|to)\b"""),
        r("""\b(?:tidak|ga|gak|nggak|enggak)\s+makan\s+(?:seharian|berhari|selama (?:berhari|seminggu))"""),
        r("""\bpuasa\s+(?:berhari|tiga hari|3 hari|seminggu)\b"""),
        r("""\bstarv\w+\b|\bkelaparan sengaja\b"""),
        r("""\b(?:cuma|hanya|saja|only)\s+(?:makan\s+)?\d{2,3}\s*(?:kalori|kkal|kcal|calories?)\s*(?:sehari|per hari|seharian|a day|per day)?"""),
        r("""\b\d{2,3}\s*(?:kalori|kkal|kcal|calories?)\s*(?:sehari|per hari|seharian|a day|per day)\b"""),
        r("""\bmakan\s+(?:berlebihan|tidak terkendali|kalap)\b|\btidak bisa berhenti makan\b|\bcan'?t stop eating\b"""),
        r("""\b(?:benci|jijik|muak)\s+(?:dengan\s+)?(?:tubuhku|badanku|diriku|tubuh saya|badan saya)\b.*\b(?:makan|gemuk|lemak)\b"""),
        r("""\b(?:bersalah|menyesal)\s+(?:setelah|habis|abis)\s+makan\b|\bhukum\w*\s+(?:diri|badan)\s+(?:dengan\s+)?(?:olahraga|lari|tidak makan)\b"""),
        r("""\b(?:takut|phobia)\s+(?:banget\s+|sekali\s+)?makan\b|\bphobia\s+(?:gemuk|naik berat badan)\b|\btakut\s+(?:banget|sekali|sangat)\s+(?:gemuk|naik berat badan)\b|\b(?:gemuk|naik berat badan)\s+(?:itu\s+)?menakutkan\b""")
    )

    fun eatingConcern(raw: String): Boolean {
        val t = FitText.norm(raw)
        for (re in EATING) {
            val m = re.find(t) ?: continue
            // angka kalori ekstrem hanya bermasalah bila di bawah 1000 per hari
            val num = Regex("""(\d{2,4})\s*(?:kalori|kkal|kcal|calories?)""").find(m.value)?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num >= 1000) continue
            return true
        }
        return false
    }

    private val INBODY_WORD = r("""\b(?:inbody|in body|komposisi tubuh|body composition)\b""")

    /** Keterangan foto yang menyebut InBody: foto itu dibaca sebagai lembar hasil InBody. */
    /** Keterangan foto yang menyebut InBody ("in body" saja tidak cukup: bisa berarti "di badan teks"). */
    private val CAPTION_WORD = r("""\b(?:inbody|in body (?:result|results|scan|test|report|analysis|hasil|analisis)|komposisi tubuh|body composition)\b""")

    fun mentionsInBody(caption: String): Boolean = CAPTION_WORD.containsMatchIn(FitText.norm(caption))

    // ------------------------------------------------------------------ utama

    fun parse(raw: String): HealthRequest? {
        val t = FitText.norm(raw)
        if (t.isEmpty()) return null
        if (eatingConcern(raw)) return HealthRequest.EatingConcern
        parseHelp(t)?.let { return it }
        parseUndo(t)?.let { return it }
        parseInbody(raw, t)?.let { return it }
        parseProgram(t)?.let { return it }
        parseTargets(t)?.let { return it }
        parseProfile(t)?.let { return it }
        parseGoal(t)?.let { return it }
        parseFoodInfo(t)?.let { return it }
        parseMealQuery(t)?.let { return it }
        parseWorkoutQuery(t)?.let { return it }
        parseWeight(t)?.let { return it }
        parseMealLog(raw, t)?.let { return it }
        parseWorkoutLog(raw, t)?.let { return it }
        parseKnowledge(t)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ bantuan, inbody, undo

    private val HELP = r("""^(?:bantuan|help|fitur|menu|apa (?:saja )?(?:yang )?(?:bisa|dapat) (?:kamu |nine )?(?:lakukan |bantu )?(?:untuk |soal |tentang )?)\s*(?:fitness|gym|latihan|gizi|nutrisi|diet|pelatih|coach|inbody|kebugaran)\b|^(?:fitness|gym|nutrition|nutritionist|fitness coach)\s+(?:help|features?|menu)$|^(?:jadi |jadilah |be |act as )?(?:pelatih (?:fitness|gym)|ahli gizi|personal trainer|fitness coach|nutritionist)(?: saya| aku)?$""")

    private fun parseHelp(t: String): HealthRequest? = if (HELP.containsMatchIn(t)) HealthRequest.Help else null

    private fun parseInbody(raw: String, t: String): HealthRequest? {
        if (!Regex("""\b(?:inbody|in body)\b""").containsMatchIn(t)) return null
        if (Regex("""\b(?:apa itu|what is|cara membaca|how to read|arti angka|arti)\b""").containsMatchIn(t) && !Regex("""\d""").containsMatchIn(t)) return null
        if (Regex("""\b(?:bandingkan|perbandingan|compare|progres|progress|perubahan)\b""").containsMatchIn(t)) return HealthRequest.InBodyCompare
        if (Regex("""\b(?:terakhir|last|latest|riwayat|history|lihat|tampilkan|show)\b""").containsMatchIn(t) && !Regex("""\d""").containsMatchIn(t)) return HealthRequest.InBodyLast
        InBodyParser.parseManual(raw.replace(Regex("""(?i)\bin ?body\b"""), " "))?.let { return HealthRequest.InBodyManual(it) }
        return HealthRequest.InBodyStart
    }

    private val UNDO = r("""^(?:hapus|batalkan|batal|undo|cancel|delete|remove)\s+(?:catatan\s+|log\s+|entry\s+)?(?:(?:the|my|last|latest)\s+){0,2}(makan|menu|makanan|meal|food|latihan|workout|berat badan|berat|weight|inbody|in body)(?:\s+(?:log|entry))?(?:\s+(?:saya|aku))?(?:\s+terakhir|\s+tadi)?$""")

    private fun parseUndo(t: String): HealthRequest? {
        val m = UNDO.find(t) ?: return null
        val kind = when (m.groupValues[1]) {
            "makan", "menu", "makanan", "meal", "food" -> HealthRequest.UndoKind.MEAL
            "latihan", "workout" -> HealthRequest.UndoKind.WORKOUT
            "inbody", "in body" -> HealthRequest.UndoKind.INBODY
            else -> HealthRequest.UndoKind.WEIGHT
        }
        return HealthRequest.Undo(kind)
    }

    // ------------------------------------------------------------------ program, target, profil, tujuan

    private val PROGRAM = r("""\b(?:program|jadwal|rencana|split|plan)\s+(?:latihan|gym|workout|fitness|training|olahraga)\b|\b(?:latihan|gym|workout|training)\s+(?:plan|program)\b|\bbuatkan\s+(?:aku\s+|saya\s+)?(?:program|jadwal|split)\b|\bworkout (?:plan|routine)\b|\brutinitas (?:gym|latihan)\b""")
    private val DAYS = r("""(\d)\s*(?:hari|x|kali|days?)""")

    private fun parseProgram(t: String): HealthRequest? {
        if (!PROGRAM.containsMatchIn(t)) return null
        if (Regex("""\b(?:ingatkan|alarm|jadwalkan|kalender|agenda)\b""").containsMatchIn(t)) return null
        val d = DAYS.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..6 }
        return HealthRequest.Program(d)
    }

    private val TARGETS = r("""\b(?:kebutuhan|target|jumlah)\s+(?:kalori|kkal|kcal|protein|makro|gizi|nutrisi|energi)\b|\bkalori\s+harian\b|\btdee\b|\bbmr\s+(?:saya|aku|ku)\b|\bberapa\s+(?:kalori|protein|gram protein)\s+(?:yang\s+)?(?:harus\s+|perlu\s+)?(?:saya|aku)\s+(?:butuh|perlu|makan|konsumsi)|\bdaily\s+calories\b|\bcalorie\s+(?:target|needs?)\b|\bmy\s+(?:macros|calories|protein\s+target)\b|\bhow\s+(?:many|much)\s+(?:calories|protein)\s+(?:do\s+i|should\s+i)""")

    private fun parseTargets(t: String): HealthRequest? = if (TARGETS.containsMatchIn(t) && !MEAL_VERB.containsMatchIn(t)) HealthRequest.ShowTargets else null

    private val SEX_M = r("""\b(?:laki laki|laki|pria|cowok|male|man|boy)\b""")
    private val SEX_F = r("""\b(?:perempuan|wanita|cewek|female|woman|girl)\b""")
    private val HEIGHT = listOf(
        r("""\b(?:tinggi(?: badan)?|tb|height)\s*(?:saya|aku|ku|ku adalah)?\s*(?:adalah|sekitar|sekarang|is|=)?\s*(\d{3}(?:\.\d)?)\s*(?:cm|sentimeter)?\b"""),
        r("""\b(?:tinggi(?: badan)?|tb|height)\s*(?:saya|aku|ku)?\s*(?:adalah|sekitar|sekarang|is|=)?\s*(1\.\d{1,2})\s*(?:m|meter)\b"""),
        r("""\b(\d{3}(?:\.\d)?)\s*(?:cm|sentimeter)\b""")
    )
    private val AGE = listOf(
        r("""\b(?:umur|usia|age)\s*(?:saya|aku|ku)?\s*(?:adalah|sekitar|sekarang|is)?\s*(\d{1,2})\b"""),
        r("""\b(\d{2})\s*(?:tahun|thn|th|yo|years? old|years)\b""")
    )
    private val WEIGHT_P = listOf(
        r("""\b(?:berat(?: badan)?|bb|weight)\s*(?:saya|aku|ku)?\s*(?:adalah|sekitar|sekarang|is|=)?\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilo|kilogram)?\b"""),
        r("""\b(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilo|kilogram)\b""")
    )
    private val PROFILE_WORD = r("""\b(?:profil|data diri|profile|biodata)\b""")

    private fun activityOf(t: String): Activity? {
        Regex("""\baktivitas(?: harian)?(?: saya| aku)?\s*(?:itu |adalah |level )?(rendah|ringan|sedang|berat|tinggi|sangat aktif|low|light|moderate|high)\b""").find(t)?.let {
            return when (it.groupValues[1]) {
                "rendah", "low" -> Activity.LOW
                "ringan", "light" -> Activity.LIGHT
                "sedang", "moderate" -> Activity.MODERATE
                else -> Activity.HIGH
            }
        }
        Regex("""\b(?:my\s+)?activity(?:\s+level)?\s*(?:is|=)?\s*(sedentary|low|light|moderate|high|very active)\b""").find(t)?.let {
            return when (it.groupValues[1]) {
                "sedentary", "low" -> Activity.LOW
                "light" -> Activity.LIGHT
                "moderate" -> Activity.MODERATE
                else -> Activity.HIGH
            }
        }
        if (Regex("""\b(?:jarang|tidak pernah|tak pernah|never)\s+(?:olahraga|latihan|exercise|work ?out)\b""").containsMatchIn(t)) return Activity.LOW
        return null
    }

    private fun daysOf(t: String): Int? =
        Regex("""\b(?:olahraga|latihan|gym|ngegym|nge gym|workout|work out|train|training|exercise|lift)\s*(?:sekitar\s*|about\s*)?(\d)\s*(?:kali|x|hari|times?|days?)\s*(?:seminggu|per minggu|sepekan|a week|per week|each week|every week)""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..7 }

    private fun parseProfile(t: String): HealthRequest? {
        if (QUESTION_CUE.containsMatchIn(t) && !PROFILE_WORD.containsMatchIn(t)) return null
        var height: Double? = null
        for (re in HEIGHT) { re.find(t)?.let { m -> val v = m.groupValues[1].toDoubleOrNull(); if (v != null) { height = if (v < 3.0) v * 100.0 else v; return@let } }; if (height != null) break }
        height = height?.takeIf { it in 100.0..230.0 }
        var age: Int? = null
        for (re in AGE) { age = re.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 10..99 }; if (age != null) break }
        var weight: Double? = null
        for (re in WEIGHT_P) { weight = re.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 25.0..250.0 }; if (weight != null) break }
        val sex = when {
            SEX_F.containsMatchIn(t) -> Sex.FEMALE
            SEX_M.containsMatchIn(t) -> Sex.MALE
            else -> null
        }
        val days = daysOf(t)
        val act = activityOf(t) ?: days?.let { when { it <= 0 -> Activity.LOW; it <= 3 -> Activity.LIGHT; it <= 5 -> Activity.MODERATE; else -> Activity.HIGH } }
        val fields = listOfNotNull(sex, age, height, weight, act).size
        val hasProfileWord = PROFILE_WORD.containsMatchIn(t)
        val words = t.split(' ').size
        // Nilai tunggal hanya diterima dalam kalimat eksplisit ("tinggi badan saya 170", "umur saya 28", "aku perempuan").
        val explicitSingle = (height != null && Regex("""\b(?:tinggi|tb|height)\b""").containsMatchIn(t)) ||
            (age != null && Regex("""\b(?:umur|usia|age)\b""").containsMatchIn(t)) ||
            (sex != null && words <= 4 && Regex("""^(?:aku|saya|gue|i am|i m|im)\b""").containsMatchIn(t)) ||
            act != null
        // "berat badan 72 kg" saja = catatan berat, bukan profil
        val weightOnly = fields == 1 && weight != null && !hasProfileWord
        if (weightOnly) return null
        if (fields == 0) return null
        if (!(hasProfileWord || fields >= 2 || explicitSingle)) return null
        if (MEAL_VERB.containsMatchIn(t) || Exercises.find(t).isNotEmpty()) return null
        return HealthRequest.SetProfile(sex, age, height, weight, act, days)
    }

    private val GOAL_CUT = r("""\b(?:cutting|cut|menurunkan\s+(?:berat|lemak)|turun\s+(?:berat|lemak)|menurunkan\s+bb|defisit|diet|fat\s*loss|lose\s+(?:weight|fat)|weight\s*loss|kurus|langsing)\b""")
    private val GOAL_BULK = r("""\b(?:bulking|bulk|menaikkan\s+berat|naik\s+berat|menambah\s+(?:otot|massa|berat)|nambah\s+(?:otot|massa|berat)|naik\s+massa|gain\s+(?:muscle|weight)|muscle\s+gain|build\s+muscle)\b""")
    private val GOAL_MAINT = r("""\b(?:maintenance|maintain|menjaga\s+berat|mempertahankan\s+berat|pertahankan\s+berat|jaga\s+berat|stabil)\b""")
    private val GOAL_RECOMP = r("""\b(?:rekomposisi|recomp|recomposition)\b""")
    private val GOAL_CUE = r("""\b(?:target|tujuan|goal|mau|ingin|pengen|pingin|fokus|mode|ganti|ubah|set|want|aim|trying|tujuanku|targetku|goalku)\b""")
    /** "Aku sedang cutting": fase yang sedang dijalani, hanya untuk istilah tujuan yang tegas. */
    private val GOAL_PHASE = r("""\b(?:sedang|lagi|currently|i m|im)\s+(?:di\s+fase\s+|dalam\s+fase\s+|in\s+a\s+)?(?:cutting|bulking|recomp|recomposition|maintenance|bulk|cut)\b""")

    private val BARE_GOAL = r("""^(?:mode\s+)?(cutting|bulking|maintenance|recomp|recomposition)$""")

    private fun parseGoal(t: String): HealthRequest? {
        if (t.split(' ').size > 10) return null
        BARE_GOAL.find(t)?.let { m ->
            return HealthRequest.SetGoal(when (m.groupValues[1]) {
                "cutting" -> Goal.CUT
                "bulking" -> Goal.BULK
                "maintenance" -> Goal.MAINTAIN
                else -> Goal.RECOMP
            })
        }
        if (!GOAL_CUE.containsMatchIn(t) && !GOAL_PHASE.containsMatchIn(t)) return null
        if (Regex("""\b(?:program|jadwal|menu|resep|makan|apa|bagaimana|gimana|cara|tips|how|what)\b""").containsMatchIn(t)) return null
        val g = when {
            GOAL_RECOMP.containsMatchIn(t) -> Goal.RECOMP
            GOAL_BULK.containsMatchIn(t) -> Goal.BULK
            GOAL_MAINT.containsMatchIn(t) -> Goal.MAINTAIN
            GOAL_CUT.containsMatchIn(t) -> Goal.CUT
            else -> null
        } ?: return null
        return HealthRequest.SetGoal(g)
    }

    // ------------------------------------------------------------------ pertanyaan gizi makanan & catatan makan

    private val FOOD_INFO = r("""\b(?:berapa\s+)?(?:kalori|kkal|kcal|protein|gizi|nutrisi|kandungan|karbo|karbohidrat|lemak|calories?|nutrition|macros)\s*(?:dalam|di|pada|dari|nya|of|in)?\s+(.+)$|^(.+?)\s+(?:berapa\s+kalori|kalorinya|berapa\s+protein|proteinnya|berapa\s+kkal)$""")

    private fun parseFoodInfo(t: String): HealthRequest? {
        if (MEAL_VERB.containsMatchIn(t) && !Regex("""\bberapa\b""").containsMatchIn(t)) return null
        if (TARGETS.containsMatchIn(t)) return null
        val m = FOOD_INFO.find(t) ?: return null
        val phrase = (m.groups[1]?.value ?: m.groups[2]?.value ?: return null).trim()
        // "berapa kalori yang saya butuhkan": bukan soal makanan
        if (Regex("""^(?:yang|saya|aku|per hari|harian|sehari)\b""").containsMatchIn(phrase)) return null
        val food = Foods.lookup(phrase) ?: return null
        return HealthRequest.FoodInfo(food.key)
    }

    private val MEALS_Q = r("""\b(?:makan\s+apa\s+(?:saja|aja)|menu|makanan|asupan|kalori|gizi|nutrisi|protein|konsumsi)\s*(?:saya|aku|ku)?\s*(?:hari ini|kemarin|tadi|today|yesterday)\b|\b(?:sisa|kurang)\s+(?:kalori|protein)\b|\b(?:ringkasan|rekap)\s+(?:gizi|nutrisi|makan|asupan)\b|\bmakan apa(?: saja| aja)?(?: aku| saya)?\s+(?:hari ini|kemarin|tadi)\b|\b(?:aku|saya) (?:sudah |udah )?makan apa(?: saja| aja)?\b|\bwhat\s+did\s+i\s+eat\b|\bcalories?\s+(?:today|so\s+far)\b|\bmy\s+(?:meals|intake)\s+today\b|\btotal\s+kalori\b|^(?:show\s+(?:me\s+)?)?(?:my\s+)?(?:today s|todays|yesterday s)\s+(?:meals?|menu|food|intake|calories)$|^(?:my\s+)?(?:meals?|food|intake|menu)\s+(?:today|yesterday)$""")
    private val WEEK = r("""\b(?:ringkasan|rekap|laporan|summary|recap)\s+(?:mingguan|minggu ini|fitness|latihan dan gizi|kebugaran|weekly)\b|\bweekly\s+(?:summary|recap|report)\b""")

    private fun parseMealQuery(t: String): HealthRequest? {
        if (WEEK.containsMatchIn(t)) return HealthRequest.WeekSummary
        if (!MEALS_Q.containsMatchIn(t)) return null
        // Pernyataan makan ("tadi makan nasi") bukan pertanyaan
        if (Regex("""\bmakan\b""").containsMatchIn(t) && !Regex("""\bapa\b""").containsMatchIn(t) && Foods.find(t).isNotEmpty()) return null
        val ago = if (Regex("""\b(?:kemarin|yesterday)\b""").containsMatchIn(t)) 1 else 0
        return HealthRequest.MealsDay(ago)
    }

    /** "martabak 400 kalori" tanpa kata kerja: dicatat sebagai makanan (Nine sendiri menyarankan bentuk ini). */
    private val BARE_CAL = r("""^[a-z][a-z ]{1,40}?\s+\d{2,4}\s*(?:kalori|kkal|kcal|calories|calorie|cals?)$""")
    private val BURN_WORDS = r("""\b(?:bakar|membakar|terbakar|burn|burned|burnt|burning|habis|sisa|kurang|target|limit|batas|maksimal|max|jalan|jalan kaki|berenang|renang|bersepeda|sepeda|senam|yoga|olahraga|walk|walking|swim|swimming|cycling|cycle|bike|hike|hiking|treadmill|zumba|futsal|badminton|basket|sport)\b""")

    private fun parseMealLog(raw: String, t: String): HealthRequest? {
        if (!MEAL_VERB.containsMatchIn(t)) {
            if (!BARE_CAL.matches(t) || BURN_WORDS.containsMatchIn(t) || WANT_OR_ASK.containsMatchIn(t) || MONEY.containsMatchIn(t) ||
                Exercises.find(t).isNotEmpty() || Regex("""\b(?:ingatkan|alarm|timer|jam|pukul)\b""").containsMatchIn(t)) return null
            return parseMealLog("makan " + raw.trim(), "makan $t")
        }
        if (WANT_OR_ASK.containsMatchIn(t)) return null
        if (MONEY.containsMatchIn(t)) return null
        val hasCal = Regex("""\d+\s*(?:kalori|kkal|kcal|calories|calorie|cals?)\b""").containsMatchIn(t)
        if (Foods.find(t).isEmpty() && !hasCal) return null
        val p = MealParser.parse(raw)
        if (p.items.isEmpty()) return null
        return HealthRequest.LogMeal(p)
    }

    // ------------------------------------------------------------------ latihan

    private val WORKOUT_PROMPT = r("""^(?:tadi |barusan |baru saja |hari ini )?(?:aku |saya |gue )?(?:habis|abis|selesai|baru selesai|sudah|udah)\s+(?:gym|ngegym|nge gym|latihan|workout|olahraga|angkat beban|fitness)(?:\s+(?:tadi|barusan|pagi|sore|malam|siang))?$|^(?:catat|log)\s+(?:latihan|workout)(?:\s+(?:saya|aku|hari ini))?$|^(?:i )?(?:just )?(?:finished|did)\s+(?:a )?(?:workout|gym|training)$""")
    private val HISTORY = r("""\b(?:riwayat|history|catatan)\s+(?:latihan|workout|gym)\b|\blatihan\s+(?:saya\s+)?(?:minggu ini|terakhir|kemarin|bulan ini|pekan ini)\b|\b(?:aku|saya)\s+(?:sudah\s+)?latihan\s+apa\s+(?:saja|aja)\b|\bmy\s+workouts?\b|\bworkout\s+history\b|\blast\s+workout\b""")
    private val PROGRESS_CUE = r("""\b(?:progres|progress|perkembangan|kemajuan|rekor|pr|personal record|max|1rm|riwayat|history|naik berapa)\b""")
    private val SUGGEST_CUE = r("""\b(?:saran beban|beban berikutnya|beban selanjutnya|beban lanjutan|naik berapa kg|berapa kg (?:berikutnya|selanjutnya)|next weight|what weight|progressive overload|naikkan beban|harus naik berapa|suggest\s+(?:\w+\s+){1,3}weight|suggested\s+weight|how much\s+(?:weight\s+)?should\s+i\s+(?:lift|add|use))\b""")

    private fun parseWorkoutQuery(t: String): HealthRequest? {
        if (WORKOUT_PROMPT.containsMatchIn(t)) return HealthRequest.WorkoutPrompt
        if (HISTORY.containsMatchIn(t)) {
            val days = if (Regex("""\b(?:bulan ini|month)\b""").containsMatchIn(t)) 30 else if (Regex("""\b(?:terakhir|last|kemarin)\b""").containsMatchIn(t)) 1 else 7
            return HealthRequest.WorkoutHistory(days)
        }
        val ex = Exercises.find(t)
        if (ex.isNotEmpty()) {
            if (SUGGEST_CUE.containsMatchIn(t)) return HealthRequest.SuggestWeight(ex.first().def.key)
            // pertanyaan progres/rekor, bukan pencatatan (tanpa angka beban)
            if (PROGRESS_CUE.containsMatchIn(t) && !Regex("""\d+\s*(?:kg|x|set|rep)""").containsMatchIn(t)) return HealthRequest.ExerciseProgress(ex.first().def.key)
        }
        return null
    }

    private val WEIGHT_LOG = r("""^(?:tadi pagi |pagi ini |hari ini |barusan )?(?:aku |saya )?(?:timbang|menimbang|nimbang)?\s*(?:berat(?: badan)?|bb|timbangan|weight)\s*(?:saya|aku|ku)?\s*(?:sekarang|hari ini|pagi ini|tadi pagi|jadi|menjadi|adalah|is|=)?\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilo|kilogram)?$|^(?:tadi pagi |pagi ini |hari ini )?(?:aku |saya )?(?:timbang|menimbang|nimbang)\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilo|kilogram)?$|^(?:i weigh|my weight is|weight today|weighed)\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilos?)?$|^(?:catat\s+)?(?:berat badan|bb)\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:kg|kilo)?$""")
    private val WEIGHT_TREND = r("""\b(?:tren|trend|progres|progress|perkembangan|grafik|riwayat|history)\s+(?:berat(?: badan)?|bb|weight)\b|\bberat\s+badan\s+(?:saya\s+)?(?:berapa|terakhir|sekarang berapa)\b|\bmy\s+weight\b|\bweight\s+(?:trend|progress|history)\b""")

    private fun parseWeight(t: String): HealthRequest? {
        if (WEIGHT_TREND.containsMatchIn(t) && !Regex("""\d{2}""").containsMatchIn(t)) return HealthRequest.WeightTrend
        val m = WEIGHT_LOG.find(t) ?: return null
        val v = (1..4).mapNotNull { m.groups[it]?.value?.toDoubleOrNull() }.firstOrNull() ?: return null
        if (v !in 25.0..250.0) return null
        return HealthRequest.LogBodyWeight(v)
    }

    private fun parseWorkoutLog(raw: String, t: String): HealthRequest? {
        if (WANT_OR_ASK.containsMatchIn(t) && !Regex("""\d""").containsMatchIn(t)) return null
        if (Regex("""\b(?:mau|ingin|pengen|rencana|nanti|besok|akan|want to|going to|will|tomorrow)\b""").containsMatchIn(t)) return null
        if (MONEY.containsMatchIn(t)) return null
        if (Regex("""\b(?:ingatkan|ingetin|pengingat|reminder|remind|alarm|timer|jadwal|agenda|kalender|pukul|jam\s+\d)""").containsMatchIn(t)) return null
        if (!Regex("""\d""").containsMatchIn(t)) return null
        val p = WorkoutParser.parse(raw) ?: return null
        return HealthRequest.LogWorkout(p.entries, p.dayOffset, p.warnings)
    }

    // ------------------------------------------------------------------ ilmu gizi & kebugaran

    private fun parseKnowledge(t: String): HealthRequest? {
        if (!QUESTION_CUE.containsMatchIn(t.split(' ').take(6).joinToString(" "))) return null
        val topic = NutritionKnowledge.match(t) ?: return null
        return HealthRequest.Ask(topic.key)
    }
}

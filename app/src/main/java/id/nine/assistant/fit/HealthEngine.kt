package id.nine.assistant.fit

import id.nine.assistant.core.AppLang
import id.nine.assistant.support.CrisisResources
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pelatih fitness + ahli gizi: menjalankan [HealthRequest] terhadap data di [FitStore] dan menyusun balasan.
 * Kotlin murni (jam dan zona waktu disuntikkan) supaya semua hitungan dan teks bisa diuji di JVM.
 *
 * Angka gizi makanan adalah PERKIRAAN; target kalori/protein adalah perkiraan awal dengan pagar pengaman; InBody
 * dibaca dari OCR dan SELALU dikonfirmasi pengguna lebih dulu. Bukan nasihat medis.
 */
class HealthEngine(
    private val store: FitStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    companion object {
        const val DRAFT_TTL_MS = 30L * 60_000L
        private const val MAX_WORKOUTS = 3000
        private const val MAX_MEALS = 6000
        private const val MAX_WEIGHTS = 3000
        private const val MAX_INBODY = 200

        private val YES = Regex("""^(?:ya|iya|yap|yup|betul|benar|bener|oke|ok|okay|siap|simpan|sudah benar|ya benar|ya betul|ya simpan|simpan saja|yes|correct|right|save|save it|looks good|that s right)$""")
        private val NO = Regex("""^(?:batal|batalkan|tidak|nggak|enggak|gak|ga|jangan|buang|cancel|no|nope|discard|salah semua|bukan)$""")
    }

    /** False bila pembaca foto dimatikan pengguna: InBody hanya lewat ketikan angka. */
    var photoEnabled: Boolean = true

    private var draft: InBodyReading? = null
    private var draftAt = 0L
    private var awaiting = false
    private var awaitingAt = 0L

    /** Ada bacaan InBody dari foto yang menunggu "ya"/koreksi pengguna. */
    fun hasDraft(): Boolean { expire(); return draft != null }

    /** Pengguna sudah minta analisis InBody dan diminta mengirim foto. */
    fun isAwaitingPhoto(): Boolean { expire(); return awaiting }

    fun clearInBodyState() { draft = null; awaiting = false }

    private fun expire() {
        val n = clock()
        if (draft != null && n - draftAt > DRAFT_TTL_MS) draft = null
        if (awaiting && n - awaitingAt > DRAFT_TTL_MS) awaiting = false
    }

    // ------------------------------------------------------------------ pembantu umum

    private fun f(d: Double, l: AppLang, digits: Int = 1) = FitText.fmt(d, l, digits)
    private fun exName(key: String, l: AppLang) = Exercises.byKey[key]?.name(l) ?: key
    private fun foodName(name: String, l: AppLang) = Foods.byKey[name]?.name(l) ?: name
    private fun today(): LocalDate = FitTime.day(clock(), zone)

    private fun dateLabel(d: LocalDate, l: AppLang): String = when (d) {
        today() -> l.t("hari ini", "today")
        today().minusDays(1) -> l.t("kemarin", "yesterday")
        else -> "%02d/%02d".format(d.dayOfMonth, d.monthValue)
    }

    private fun shortDate(t: Long) = FitTime.day(t, zone).let { "%02d/%02d".format(it.dayOfMonth, it.monthValue) }

    private fun mealTypeName(t: MealType, l: AppLang) = when (t) {
        MealType.BREAKFAST -> l.t("sarapan", "breakfast")
        MealType.LUNCH -> l.t("makan siang", "lunch")
        MealType.DINNER -> l.t("makan malam", "dinner")
        MealType.SNACK -> l.t("camilan", "snack")
    }

    private fun latestKg(data: FitData): Double? = data.weights.maxByOrNull { it.time }?.kg

    private fun targetOf(data: FitData): Targets.Result =
        Targets.compute(data.profile, latestKg(data), data.inbody.lastOrNull()?.bmr)

    private fun <T> trim(list: MutableList<T>, max: Int) { while (list.size > max) list.removeAt(0) }

    private fun addWeightIfNew(data: FitData, kg: Double, now: Long) {
        val last = data.weights.maxByOrNull { it.time }
        if (last != null && FitTime.day(last.time, zone) == FitTime.day(now, zone) && Math.abs(last.kg - kg) < 0.05) return
        data.weights.add(WeightEntry(data.newId(), now, kg))
        trim(data.weights, MAX_WEIGHTS)
    }

    // ------------------------------------------------------------------ pintu masuk

    fun handle(req: HealthRequest, l: AppLang): String = when (req) {
        is HealthRequest.LogWorkout -> logWorkout(req, l)
        HealthRequest.WorkoutPrompt -> workoutPrompt(l)
        is HealthRequest.WorkoutHistory -> history(req.days, l)
        is HealthRequest.ExerciseProgress -> exerciseProgress(req.exercise, l)
        is HealthRequest.SuggestWeight -> suggest(req.exercise, l)
        is HealthRequest.LogBodyWeight -> logWeight(req.kg, l)
        HealthRequest.WeightTrend -> weightTrendText(l)
        is HealthRequest.SetProfile -> setProfile(req, l)
        is HealthRequest.SetGoal -> setGoal(req.goal, l)
        HealthRequest.ShowTargets -> targets(l)
        is HealthRequest.LogMeal -> logMeal(req.parsed, l)
        is HealthRequest.MealsDay -> mealsDay(req.daysAgo, l)
        HealthRequest.WeekSummary -> week(l)
        is HealthRequest.FoodInfo -> foodInfo(req.foodKey, l)
        is HealthRequest.Ask -> ask(req.topicKey, l)
        is HealthRequest.Program -> program(req.days, l)
        HealthRequest.InBodyStart -> inbodyStart(l)
        is HealthRequest.InBodyManual -> inbodyManual(req.reading, l)
        HealthRequest.InBodyLast -> inbodyLast(l)
        HealthRequest.InBodyCompare -> inbodyCompare(l)
        is HealthRequest.Undo -> undo(req.kind, l)
        HealthRequest.EatingConcern -> eatingConcern(l)
        HealthRequest.Help -> help(l)
    }

    // ------------------------------------------------------------------ latihan

    private fun workoutPrompt(l: AppLang) = l.t(
        "Latihan apa tadi? Sebutkan gerakan, beban, dan set × rep. Contoh: \"bench press 60 kg 3x8, squat 80 kg 5x5\". Kardio: \"lari 5 km 30 menit\". Tanpa alat: \"push up 3x20\".",
        "What did you train? Say the exercise, weight and sets × reps. Example: \"bench press 60 kg 3x8, squat 80 kg 5x5\". Cardio: \"run 5 km 30 minutes\". Bodyweight: \"push up 3x20\"."
    )

    fun entryLine(e: WorkoutEntry, l: AppLang): String {
        val def = Exercises.byKey[e.exercise]
        val name = exName(e.exercise, l)
        return when (def?.kind) {
            Exercises.Kind.CARDIO -> {
                val parts = ArrayList<String>()
                if (e.km > 0.0) parts.add("${f(e.km, l)} km")
                if (e.minutes > 0.0) parts.add(l.t("${f(e.minutes, l, 0)} menit", "${f(e.minutes, l, 0)} min"))
                var s = "$name ${parts.joinToString(", ")}"
                if (e.km > 0.0 && e.minutes > 0.0 && (e.exercise == "running" || e.exercise == "walking")) {
                    val total = Math.round(e.minutes / e.km * 60.0).toInt()
                    s += " (${total / 60}:${"%02d".format(total % 60)}/km)"
                }
                s
            }
            Exercises.Kind.HOLD -> if (e.sets > 1) "$name ${e.sets}×${e.seconds} ${l.t("dtk", "sec")}" else "$name ${e.seconds} ${l.t("dtk", "sec")}"
            else -> {
                val w = if (e.weightKg > 0.0) " @ ${f(e.weightKg, l)} kg" else ""
                "$name ${e.sets}×${e.reps}$w"
            }
        }
    }

    private fun warningText(ws: List<WorkoutParser.Warning>, l: AppLang): String {
        if (ws.isEmpty()) return ""
        return ws.joinToString("\n") { w ->
            val n = exName(w.exercise, l)
            when (w.kind) {
                WorkoutParser.WarnKind.NO_WEIGHT -> l.t("• $n: bebannya tidak disebut, dicatat tanpa beban. Lain kali: \"$n 60 kg 3x8\".", "• $n: no weight given, logged without weight. Next time: \"$n 60 kg 3x8\".")
                WorkoutParser.WarnKind.NO_REPS -> l.t("• $n: jumlah rep tidak terdengar, tidak dicatat. Coba: \"$n 3x8\".", "• $n: reps not heard, not logged. Try: \"$n 3x8\".")
                WorkoutParser.WarnKind.ODD_NUMBER -> l.t("• $n: angkanya tampak janggal, tidak dicatat. Coba ucapkan ulang.", "• $n: the numbers look odd, not logged. Please say it again.")
                WorkoutParser.WarnKind.NO_AMOUNT -> l.t("• $n: durasi atau jarak tidak terdengar, tidak dicatat. Coba: \"$n 30 menit\".", "• $n: duration or distance not heard, not logged. Try: \"$n 30 minutes\".")
            }
        }
    }

    private fun logWorkout(req: HealthRequest.LogWorkout, l: AppLang): String {
        val warn = warningText(req.warnings, l)
        if (req.entries.isEmpty()) return if (warn.isEmpty()) workoutPrompt(l) else l.t("Belum ada yang tercatat:\n", "Nothing logged yet:\n") + warn
        val data = store.load()
        val now = clock()
        val day = today().plusDays(req.dayOffset.toLong().coerceAtMost(0L))
        val time = if (req.dayOffset < 0) FitTime.noon(day, zone) else now
        val prs = Progress.detectPrs(data, req.entries, zone)
        data.workouts.add(Workout(data.newId(), time, req.entries))
        trim(data.workouts, MAX_WORKOUTS)
        store.save(data)

        val sb = StringBuilder()
        sb.append(if (req.dayOffset < 0) l.t("Latihan kemarin tercatat:", "Yesterday's workout logged:") else l.t("Latihan tercatat:", "Workout logged:"))
        for (e in req.entries) sb.append("\n• ").append(entryLine(e, l))
        val vol = req.entries.sumOf { Progress.volume(it) }
        if (vol > 0.0) sb.append("\n").append(l.t("Volume angkat: ${f(vol, l, 0)} kg.", "Lifting volume: ${f(vol, l, 0)} kg."))
        for (pr in prs) {
            val n = exName(pr.exercise, l)
            when (pr.kind) {
                Progress.PrKind.FIRST -> sb.append("\n").append(l.t("Catatan pertama untuk $n: jadi patokan awal.", "First log for $n: that's your baseline."))
                Progress.PrKind.WEIGHT -> sb.append("\n").append(l.t("Rekor beban baru: $n ${f(pr.kg, l)} kg × ${pr.reps}. Mantap!", "New weight record: $n ${f(pr.kg, l)} kg × ${pr.reps}. Nice!"))
                Progress.PrKind.E1RM -> sb.append("\n").append(l.t("Kekuatan $n naik (${f(pr.kg, l)} kg × ${pr.reps} lebih berat dari sebelumnya setara 1RM).", "$n strength is up (${f(pr.kg, l)} kg × ${pr.reps} beats your previous estimated 1RM)."))
                Progress.PrKind.REPS -> sb.append("\n").append(l.t("Rekor rep baru: $n ${pr.reps} rep.", "New rep record: $n ${pr.reps} reps."))
            }
        }
        if (warn.isNotEmpty()) sb.append("\n").append(warn)
        val days = Progress.trainingDays(data, now, zone, 7)
        if (days >= 2) sb.append("\n").append(l.t("Hari latihan 7 hari terakhir: $days.", "Training days in the last 7 days: $days."))
        return sb.toString()
    }

    private fun history(days: Int, l: AppLang): String {
        val data = store.load()
        val now = clock()
        val from = FitTime.startOf(today().minusDays((days - 1).toLong()), zone)
        val ws = data.workouts.filter { it.time >= from && it.time <= now + 86_400_000L }.sortedBy { it.time }
        if (ws.isEmpty()) return l.t(
            "Belum ada latihan tercatat dalam $days hari terakhir. Contoh mencatat: \"bench press 60 kg 3x8\".",
            "No workouts logged in the last $days days. Example: \"bench press 60 kg 3x8\"."
        )
        val shown = ws.takeLast(6)
        val sb = StringBuilder()
        val daysTrained = ws.map { FitTime.day(it.time, zone) }.toSet().size
        sb.append(l.t("${ws.size} sesi dalam $days hari ($daysTrained hari latihan):", "${ws.size} sessions in $days days ($daysTrained training days):"))
        for (w in shown) sb.append("\n").append(shortDate(w.time)).append(": ").append(w.entries.joinToString(", ") { entryLine(it, l) })
        if (ws.size > shown.size) sb.append("\n").append(l.t("(menampilkan ${shown.size} terbaru)", "(showing the latest ${shown.size})"))
        return sb.toString()
    }

    private fun exerciseProgress(key: String, l: AppLang): String {
        val def = Exercises.byKey[key] ?: return help(l)
        val data = store.load()
        val name = def.name(l)
        if (def.kind == Exercises.Kind.CARDIO || def.kind == Exercises.Kind.HOLD) return cardioProgress(data, def, l)
        val st = Progress.stats(data, key, zone) ?: return l.t(
            "Belum ada catatan $name. Mulai dengan: \"$name 40 kg 3x10\".",
            "No $name logs yet. Start with: \"$name 40 kg 3x10\"."
        )
        val sb = StringBuilder()
        sb.append(l.t("$name: ${st.totalSessions} sesi tercatat.", "$name: ${st.totalSessions} sessions logged."))
        if (st.prKg > 0.0) {
            sb.append("\n").append(l.t("Beban terberat ${f(st.prKg, l)} kg", "Heaviest ${f(st.prKg, l)} kg"))
            st.prKgDate?.let { sb.append(" (").append("%02d/%02d".format(it.dayOfMonth, it.monthValue)).append(")") }
            st.prE1rm?.let { sb.append("\n").append(l.t("Perkiraan 1RM ±${f(it, l, 0)} kg (rumus Epley, kasar).", "Estimated 1RM ±${f(it, l, 0)} kg (Epley formula, rough).")) }
        } else if (st.maxReps > 0) {
            sb.append("\n").append(l.t("Rep terbanyak per set: ${st.maxReps}.", "Most reps in a set: ${st.maxReps}."))
        }
        val last = st.days.takeLast(5)
        sb.append("\n").append(l.t("Terakhir:", "Latest:"))
        for (d in last) {
            val w = if (d.bestKg > 0.0) "${f(d.bestKg, l)} kg × ${d.bestReps}" else l.t("${d.bestReps} rep", "${d.bestReps} reps")
            sb.append("\n• ").append("%02d/%02d".format(d.date.dayOfMonth, d.date.monthValue)).append(": ").append(w).append(" (${d.sets} set)")
        }
        if (st.days.size >= 3 && st.days.first().bestKg > 0.0) {
            val first = st.days.first(); val lastD = st.days.last()
            val diff = lastD.bestKg - first.bestKg
            if (Math.abs(diff) >= 0.1) sb.append("\n").append(l.t(
                "Sejak catatan pertama: ${if (diff > 0) "+" else ""}${f(diff, l)} kg pada set terberat.",
                "Since the first log: ${if (diff > 0) "+" else ""}${f(diff, l)} kg on the top set."))
        }
        return sb.toString()
    }

    private fun cardioProgress(data: FitData, def: Exercises.Def, l: AppLang): String {
        val name = def.name(l)
        val rows = data.workouts.flatMap { w -> w.entries.filter { it.exercise == def.key }.map { w.time to it } }.sortedBy { it.first }
        if (rows.isEmpty()) return l.t("Belum ada catatan $name.", "No $name logs yet.")
        val sb = StringBuilder(l.t("$name: ${rows.size} sesi tercatat.", "$name: ${rows.size} sessions logged."))
        val km = rows.sumOf { it.second.km }
        val min = rows.sumOf { it.second.minutes }
        if (km > 0.0) sb.append("\n").append(l.t("Total jarak ${f(km, l)} km.", "Total distance ${f(km, l)} km."))
        if (min > 0.0) sb.append("\n").append(l.t("Total waktu ${f(min, l, 0)} menit.", "Total time ${f(min, l, 0)} minutes."))
        sb.append("\n").append(l.t("Terakhir:", "Latest:"))
        for ((t, e) in rows.takeLast(4)) sb.append("\n• ").append(shortDate(t)).append(": ").append(entryLine(e, l))
        return sb.toString()
    }

    private fun suggest(key: String, l: AppLang): String {
        val def = Exercises.byKey[key] ?: return help(l)
        val name = def.name(l)
        if (def.kind != Exercises.Kind.STRENGTH) return l.t(
            "Saran beban hanya untuk gerakan beban. Untuk $name, naikkan sedikit demi sedikit durasi atau jumlah repnya tiap minggu.",
            "Weight suggestions are only for loaded lifts. For $name, add a little duration or reps each week."
        )
        val st = Progress.stats(store.load(), key, zone) ?: return l.t(
            "Belum ada catatan $name. Mulailah dengan beban ringan yang bisa kamu angkat 10-12 rep dengan teknik rapi, lalu catat.",
            "No $name logs yet. Start with a light weight you can lift for 10-12 clean reps, then log it."
        )
        val nw = Progress.nextWeight(st, def) ?: return l.t("Belum ada beban tercatat untuk $name.", "No weight logged for $name yet.")
        val sb = StringBuilder()
        sb.append(l.t("Terakhir $name: ${f(nw.lastKg, l)} kg × ${nw.lastReps}.", "Last $name: ${f(nw.lastKg, l)} kg × ${nw.lastReps}."))
        if (nw.increase) sb.append("\n").append(l.t(
            "Saran berikutnya: naik ke ${f(nw.kg, l)} kg dan mulai dari sekitar ${nw.repsTarget} rep.",
            "Next time: go up to ${f(nw.kg, l)} kg and start around ${nw.repsTarget} reps."))
        else sb.append("\n").append(l.t(
            "Saran berikutnya: tetap ${f(nw.kg, l)} kg dan kejar ${nw.repsTarget} rep. Bila sudah 12 rep rapi, baru naik ±${f(def.step, l)} kg.",
            "Next time: stay at ${f(nw.kg, l)} kg and aim for ${nw.repsTarget} reps. Once you hit 12 clean reps, add ±${f(def.step, l)} kg."))
        sb.append("\n").append(l.t("Naikkan hanya bila teknik tetap rapi.", "Only add weight when your form stays clean."))
        return sb.toString()
    }

    // ------------------------------------------------------------------ berat badan

    private fun logWeight(kg: Double, l: AppLang): String {
        val data = store.load()
        val now = clock()
        val prev = data.weights.maxByOrNull { it.time }
        data.weights.add(WeightEntry(data.newId(), now, kg))
        trim(data.weights, MAX_WEIGHTS)
        store.save(data)
        val sb = StringBuilder(l.t("Berat ${f(kg, l)} kg tercatat.", "Weight ${f(kg, l)} kg logged."))
        if (prev != null) {
            val diff = kg - prev.kg
            if (Math.abs(diff) >= 0.05) sb.append(" ").append(l.t(
                "${if (diff > 0) "Naik" else "Turun"} ${f(Math.abs(diff), l)} kg dari catatan ${shortDate(prev.time)}.",
                "${if (diff > 0) "Up" else "Down"} ${f(Math.abs(diff), l)} kg from the ${shortDate(prev.time)} entry."))
            if (Math.abs(diff) >= 4.0 && now - prev.time < 7L * 86_400_000L) sb.append("\n").append(l.t(
                "Perubahan sebesar itu dalam seminggu tidak biasa; bisa salah ketik atau beda timbangan. Bilang \"hapus berat badan\" bila salah.",
                "A change that big in a week is unusual; it may be a typo or a different scale. Say \"delete weight\" if it's wrong."))
        }
        val tr = Progress.weightTrend(data, now, zone)
        tr?.avg7?.let { sb.append("\n").append(l.t("Rata-rata 7 hari: ${f(it, l)} kg.", "7-day average: ${f(it, l)} kg.")) }
        return sb.toString()
    }

    private fun weightTrendText(l: AppLang): String {
        val data = store.load()
        val now = clock()
        val tr = Progress.weightTrend(data, now, zone) ?: return l.t(
            "Belum ada catatan berat badan. Contoh: \"berat badan 72,5\". Timbang pagi hari, setelah buang air, kondisi serupa tiap kali.",
            "No weight logs yet. Example: \"weight 72.5\". Weigh in the morning, after the bathroom, under similar conditions each time."
        )
        val sb = StringBuilder()
        sb.append(l.t("Berat terakhir ${f(tr.latest.kg, l)} kg (${shortDate(tr.latest.time)}).", "Latest weight ${f(tr.latest.kg, l)} kg (${shortDate(tr.latest.time)})."))
        if (tr.avg7 != null) {
            sb.append("\n").append(l.t("Rata-rata 7 hari ${f(tr.avg7, l)} kg", "7-day average ${f(tr.avg7, l)} kg"))
            if (tr.prevAvg7 != null) {
                val d = tr.avg7 - tr.prevAvg7
                sb.append(l.t(" (minggu sebelumnya ${f(tr.prevAvg7, l)}; ${if (d > 0) "+" else ""}${f(d, l)})", " (previous week ${f(tr.prevAvg7, l)}; ${if (d > 0) "+" else ""}${f(d, l)})"))
            }
            sb.append(".")
        }
        if (tr.perWeek != null) sb.append("\n").append(l.t("Laju 4 minggu terakhir ±${if (tr.perWeek > 0) "+" else ""}${f(tr.perWeek, l, 2)} kg per minggu.", "4-week rate ±${if (tr.perWeek > 0) "+" else ""}${f(tr.perWeek, l, 2)} kg per week."))
        if (tr.first.id != tr.latest.id) {
            val d = tr.latest.kg - tr.first.kg
            sb.append("\n").append(l.t("Sejak ${shortDate(tr.first.time)} (${f(tr.first.kg, l)} kg): ${if (d > 0) "+" else ""}${f(d, l)} kg.", "Since ${shortDate(tr.first.time)} (${f(tr.first.kg, l)} kg): ${if (d > 0) "+" else ""}${f(d, l)} kg."))
        }
        val goal = data.profile.goal
        val pct = tr.perWeek?.let { it / tr.latest.kg * 100.0 }
        if (pct != null) {
            if (pct < -1.0) sb.append("\n").append(l.t("Turunnya cukup cepat (>1% berat badan per minggu). Pastikan makan cukup, terutama protein, agar otot tidak ikut hilang.", "Weight is dropping fairly fast (>1% of body weight per week). Make sure you eat enough, especially protein, so you don't lose muscle."))
            else if (goal == Goal.BULK && pct > 0.5) sb.append("\n").append(l.t("Naiknya cukup cepat untuk fase menambah massa; sebagian besar kemungkinan lemak. Pertimbangkan surplus lebih kecil.", "That's fairly fast for a gaining phase; much of it is likely fat. Consider a smaller surplus."))
        }
        sb.append("\n").append(l.t("Berat harian wajar naik-turun 1-2 kg karena air; lihat rata-rata mingguan, bukan satu hari.", "Daily weight normally swings 1-2 kg from water; look at the weekly average, not a single day."))
        return sb.toString()
    }

    // ------------------------------------------------------------------ profil, tujuan, target

    private fun activityLabel(a: Activity, l: AppLang) = when (a) {
        Activity.LOW -> l.t("rendah (jarang olahraga)", "low (rarely exercise)")
        Activity.LIGHT -> l.t("ringan (1-3 hari/minggu)", "light (1-3 days/week)")
        Activity.MODERATE -> l.t("sedang (3-5 hari/minggu)", "moderate (3-5 days/week)")
        Activity.HIGH -> l.t("tinggi (6-7 hari/minggu)", "high (6-7 days/week)")
    }

    private fun goalLabel(g: Goal, l: AppLang) = when (g) {
        Goal.CUT -> l.t("menurunkan lemak (cutting)", "fat loss (cutting)")
        Goal.BULK -> l.t("menambah otot (bulking)", "muscle gain (bulking)")
        Goal.MAINTAIN -> l.t("menjaga berat (maintenance)", "maintenance")
        Goal.RECOMP -> l.t("rekomposisi tubuh", "body recomposition")
    }

    private fun minorText(l: AppLang) = l.t(
        "Untuk usia di bawah 18 tahun aku tidak membuat target kalori atau diet, karena tubuh sedang tumbuh dan kebutuhannya berbeda. Fokus saja pada makan teratur, cukup protein, sayur dan buah, tidur, dan aktif bergerak. Bicarakan dengan orang tua, guru olahraga, atau dokter/ahli gizi.",
        "For anyone under 18 I don't make calorie or diet targets, because growing bodies have different needs. Focus on regular meals, enough protein, fruit and veg, sleep and staying active. Talk to a parent, PE teacher, or a doctor/dietitian."
    )

    private fun underweightText(l: AppLang) = l.t(
        "BMI-mu saat ini tergolong kurang (di bawah 18,5), jadi aku tidak membuat target menurunkan berat. Aku bisa bantu target menjaga atau menambah berat; ganti tujuan dengan \"target saya bulking\" atau \"maintenance\".",
        "Your current BMI is in the underweight range (below 18.5), so I won't set a weight-loss target. I can help with maintaining or gaining; switch with \"my goal is bulking\" or \"maintenance\"."
    )

    private fun missingText(missing: List<String>, l: AppLang): String {
        val labels = missing.map {
            when (it) {
                "sex" -> l.t("jenis kelamin", "sex")
                "age" -> l.t("usia", "age")
                "height" -> l.t("tinggi badan", "height")
                else -> l.t("berat badan", "weight")
            }
        }
        return l.t(
            "Aku butuh data dulu: ${labels.joinToString(", ")}. Contoh: \"aku laki-laki 28 tahun tinggi 170 berat 72\". Tujuan: \"target saya cutting\" (atau bulking / maintenance).",
            "I need a few details first: ${labels.joinToString(", ")}. Example: \"I'm male 28 years old height 170 weight 72\". Goal: \"my goal is cutting\" (or bulking / maintenance)."
        )
    }

    private fun targetBrief(data: FitData, l: AppLang): String {
        val res = targetOf(data)
        val t = res.target
        if (t != null) return l.t(
            "Perkiraan target: ±${f(t.kcal, l, 0)} kkal/hari, protein ±${f(t.proteinG, l, 0)} g. Detail: \"target kalori saya\".",
            "Estimated target: ±${f(t.kcal, l, 0)} kcal/day, protein ±${f(t.proteinG, l, 0)} g. Details: \"my calorie target\"."
        )
        return when (res.block) {
            Targets.Block.MINOR -> minorText(l)
            Targets.Block.UNDERWEIGHT_CUT -> underweightText(l)
            else -> missingText(res.missing, l)
        }
    }

    private fun setProfile(req: HealthRequest.SetProfile, l: AppLang): String {
        val data = store.load()
        val p = data.profile
        data.profile = p.copy(
            sex = req.sex ?: p.sex, age = req.age ?: p.age, heightCm = req.heightCm ?: p.heightCm,
            weightKg = req.weightKg ?: p.weightKg, activity = req.activity ?: p.activity, daysPerWeek = req.days ?: p.daysPerWeek
        )
        if (req.weightKg != null) addWeightIfNew(data, req.weightKg, clock())
        store.save(data)
        val np = data.profile
        val parts = ArrayList<String>()
        np.sex?.let { parts.add(if (it == Sex.MALE) l.t("laki-laki", "male") else l.t("perempuan", "female")) }
        np.age?.let { parts.add(l.t("$it tahun", "$it years")) }
        np.heightCm?.let { parts.add("${f(it, l, 0)} cm") }
        (latestKg(data) ?: np.weightKg)?.let { parts.add("${f(it, l)} kg") }
        np.activity?.let { parts.add(l.t("aktivitas ${activityLabel(it, l)}", "activity ${activityLabel(it, l)}")) }
        np.goal?.let { parts.add(l.t("tujuan ${goalLabel(it, l)}", "goal ${goalLabel(it, l)}")) }
        return l.t("Profil disimpan: ", "Profile saved: ") + parts.joinToString(", ") + ".\n" + targetBrief(data, l)
    }

    private fun setGoal(goal: Goal, l: AppLang): String {
        val data = store.load()
        val res = targetOf(data.also { it.profile = it.profile.copy(goal = goal) })
        if (goal == Goal.CUT && res.block == Targets.Block.UNDERWEIGHT_CUT) return underweightText(l)
        store.save(data)
        val intro = when (goal) {
            Goal.CUT -> l.t(
                "Tujuan: menurunkan lemak. Defisit moderat (±15-20% di bawah kebutuhan), protein tinggi, dan tetap latihan beban supaya otot terjaga. Laju aman ±0,25-0,75% berat badan per minggu.",
                "Goal: fat loss. A moderate deficit (±15-20% below needs), high protein and keep lifting to hold on to muscle. A safe rate is ±0.25-0.75% of body weight per week.")
            Goal.BULK -> l.t(
                "Tujuan: menambah otot. Surplus kecil (±10%), protein ±1,8 g/kg, latihan beban dengan progresi. Naik ±0,25-0,5% berat badan per minggu sudah cukup; lebih cepat biasanya jadi lemak.",
                "Goal: muscle gain. A small surplus (±10%), protein ±1.8 g/kg and progressive strength training. ±0.25-0.5% body weight per week is enough; faster is usually fat.")
            Goal.MAINTAIN -> l.t(
                "Tujuan: menjaga berat. Kalori sekitar kebutuhan, protein ±1,6 g/kg, dan latihan beban rutin.",
                "Goal: maintenance. Calories around needs, protein ±1.6 g/kg and regular strength training.")
            Goal.RECOMP -> l.t(
                "Tujuan: rekomposisi (otot naik, lemak turun pelan). Kalori sekitar kebutuhan, protein tinggi ±2 g/kg, latihan beban konsisten dan tidur cukup. Hasilnya lambat; paling cocok untuk pemula atau yang kembali berlatih.",
                "Goal: recomposition (slowly gain muscle, lose fat). Calories around needs, high protein ±2 g/kg, consistent lifting and enough sleep. Results are slow; best for beginners or people returning to training.")
        }
        return intro + "\n" + targetBrief(data, l)
    }

    private fun targets(l: AppLang): String {
        val data = store.load()
        val res = targetOf(data)
        val t = res.target
        if (t == null) return when (res.block) {
            Targets.Block.MINOR -> minorText(l)
            Targets.Block.UNDERWEIGHT_CUT -> underweightText(l)
            else -> missingText(res.missing, l)
        }
        val sb = StringBuilder()
        sb.append(l.t("Perkiraan kebutuhan harian (tujuan: ${goalLabel(t.goal, l)}):", "Estimated daily needs (goal: ${goalLabel(t.goal, l)}):"))
        sb.append("\n• ").append(l.t("Kalori ±${f(t.kcal, l, 0)} kkal (kebutuhan total ±${f(t.tdee, l, 0)}, BMR ±${f(t.bmr, l, 0)})", "Calories ±${f(t.kcal, l, 0)} kcal (total needs ±${f(t.tdee, l, 0)}, BMR ±${f(t.bmr, l, 0)})"))
        sb.append("\n• ").append(l.t("Protein ±${f(t.proteinG, l, 0)} g (rentang ${f(t.proteinLow, l, 0)}-${f(t.proteinHigh, l, 0)} g)", "Protein ±${f(t.proteinG, l, 0)} g (range ${f(t.proteinLow, l, 0)}-${f(t.proteinHigh, l, 0)} g)"))
        sb.append("\n• ").append(l.t("Lemak ±${f(t.fatG, l, 0)} g, karbohidrat ±${f(t.carbG, l, 0)} g", "Fat ±${f(t.fatG, l, 0)} g, carbs ±${f(t.carbG, l, 0)} g"))
        sb.append("\n• ").append(l.t("Serat ≥${f(t.fiberG, l, 0)} g, air ±${f(t.waterMl / 1000.0, l)} L", "Fibre ≥${f(t.fiberG, l, 0)} g, water ±${f(t.waterMl / 1000.0, l)} L"))
        for (n in t.notes) {
            val s = when (n) {
                "FLOOR" -> l.t("Kalori dinaikkan ke batas bawah aman; jangan makan di bawah itu tanpa pengawasan dokter/ahli gizi.", "Calories were raised to a safe floor; don't eat below that without a doctor/dietitian.")
                "OBESE_PROTEIN" -> l.t("Protein dihitung dari berat ideal (BMI 25) agar tidak berlebihan.", "Protein is based on a BMI-25 weight so it isn't excessive.")
                "INBODY_BMR" -> l.t("BMR memakai hasil InBody terakhir.", "BMR uses your latest InBody result.")
                "ASSUMED_ACTIVITY" -> l.t("Aktivitas diasumsikan ringan; ubah dengan \"aktivitas saya sedang\" atau \"olahraga 4 kali seminggu\".", "Activity is assumed light; change it with \"my activity is moderate\" or \"I train 4 times a week\".")
                "ASSUMED_GOAL" -> l.t("Tujuan belum diatur, dihitung untuk menjaga berat; atur dengan \"target saya cutting\" atau \"bulking\".", "No goal set, calculated for maintenance; set one with \"my goal is cutting\" or \"bulking\".")
                else -> null
            }
            if (s != null) sb.append("\n").append(s)
        }
        sb.append("\n\n").append(l.t(
            "Ini perkiraan awal (rumus Mifflin-St Jeor dan rentang protein ISSN). Pantau berat 2-3 minggu, lalu sesuaikan ±100-200 kkal. Hamil/menyusui, penyakit ginjal, diabetes, atau riwayat gangguan makan? Ikuti anjuran dokter/ahli gizi.",
            "This is a starting estimate (Mifflin-St Jeor formula and ISSN protein ranges). Track weight for 2-3 weeks, then adjust ±100-200 kcal. Pregnant/breastfeeding, kidney disease, diabetes or a history of eating disorders? Follow your doctor/dietitian's advice."))
        return sb.toString()
    }

    // ------------------------------------------------------------------ makanan

    private fun itemLine(mi: MealItem, l: AppLang): String {
        val q = if (mi.unit == "g" || mi.unit == "ml") "${f(mi.qty, l, 0)} ${mi.unit}" else "${f(mi.qty, l)} ${mi.unit}"
        return "${foodName(mi.name, l)} $q — ${f(mi.kcal, l, 0)} kkal"
    }

    private fun totalsLine(kcal: Double, p: Double, c: Double, fat: Double, l: AppLang) = l.t(
        "${f(kcal, l, 0)} kkal · protein ${f(p, l, 0)} g · karbo ${f(c, l, 0)} g · lemak ${f(fat, l, 0)} g",
        "${f(kcal, l, 0)} kcal · protein ${f(p, l, 0)} g · carbs ${f(c, l, 0)} g · fat ${f(fat, l, 0)} g"
    )

    private fun logMeal(p: MealParser.Parsed, l: AppLang): String {
        val unknown = p.unknown.filter { it.isNotBlank() }.take(3)
        if (p.items.isEmpty()) return l.t(
            "Aku belum mengenali makanannya. Coba sebut dengan jumlah, mis. \"sarapan nasi goreng 1 piring dan telur ceplok 2 butir\", atau tulis kalorinya: \"martabak 400 kalori\".",
            "I didn't recognise the food. Try it with amounts, e.g. \"breakfast fried rice 1 plate and 2 fried eggs\", or give calories: \"martabak 400 calories\"."
        )
        val data = store.load()
        val now = clock()
        val hour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour
        val type = p.type ?: MealParser.typeByHour(hour)
        val day = today().plusDays(p.dayOffset.toLong().coerceAtMost(0L))
        val time = if (p.dayOffset < 0) FitTime.noon(day, zone) else now
        val meal = Meal(data.newId(), time, type, p.items)
        data.meals.add(meal)
        trim(data.meals, MAX_MEALS)
        store.save(data)

        val sb = StringBuilder()
        sb.append(l.t("Tercatat (${mealTypeName(type, l)}${if (p.dayOffset < 0) ", kemarin" else ""}, gizi perkiraan):", "Logged (${mealTypeName(type, l)}${if (p.dayOffset < 0) ", yesterday" else ""}, estimated nutrition):"))
        for (mi in p.items) sb.append("\n• ").append(itemLine(mi, l))
        sb.append("\n").append(l.t("Total: ", "Total: ")).append(totalsLine(meal.kcal, meal.protein, meal.carbs, meal.fat, l))
        if (meal.kcal > 3000.0) sb.append("\n").append(l.t("Angkanya besar untuk satu kali makan; cek porsinya. Bilang \"hapus makan terakhir\" bila salah.", "That's a lot for one meal; check the portions. Say \"delete last meal\" if wrong."))
        if (unknown.isNotEmpty()) sb.append("\n").append(l.t(
            "Tidak kukenali: ${unknown.joinToString("; ")}. Sebutkan kalorinya bila mau dicatat, mis. \"${unknown.first()} 300 kalori\".",
            "Didn't recognise: ${unknown.joinToString("; ")}. Give calories if you want it logged, e.g. \"${unknown.first()} 300 calories\"."))
        if (p.dayOffset == 0) sb.append("\n").append(remainingLine(data, day, l))
        return sb.toString().trimEnd()
    }

    private fun mealsOn(data: FitData, d: LocalDate): List<Meal> = data.meals.filter { FitTime.day(it.time, zone) == d }.sortedBy { it.time }

    private fun remainingLine(data: FitData, d: LocalDate, l: AppLang): String {
        val ms = mealsOn(data, d)
        val kcal = ms.sumOf { it.kcal }; val prot = ms.sumOf { it.protein }
        val t = targetOf(data).target
            ?: return l.t("Hari ini: ${f(kcal, l, 0)} kkal, protein ${f(prot, l, 0)} g. Atur profil untuk melihat target: \"aku perempuan 28 tahun tinggi 160 berat 55\".", "Today: ${f(kcal, l, 0)} kcal, protein ${f(prot, l, 0)} g. Set your profile to see targets: \"I'm female 28 years old height 160 weight 55\".")
        val left = t.kcal - kcal
        val pLeft = t.proteinG - prot
        return if (left >= 0) l.t(
            "Hari ini: ${f(kcal, l, 0)} dari ±${f(t.kcal, l, 0)} kkal (sisa ±${f(left, l, 0)}); protein ${f(prot, l, 0)} dari ±${f(t.proteinG, l, 0)} g.",
            "Today: ${f(kcal, l, 0)} of ±${f(t.kcal, l, 0)} kcal (±${f(left, l, 0)} left); protein ${f(prot, l, 0)} of ±${f(t.proteinG, l, 0)} g.")
        else l.t(
            "Hari ini: ${f(kcal, l, 0)} kkal, ±${f(-left, l, 0)} di atas target ±${f(t.kcal, l, 0)}; protein ${f(prot, l, 0)} dari ±${f(t.proteinG, l, 0)} g. Sesekali tidak masalah; lihat tren mingguan.",
            "Today: ${f(kcal, l, 0)} kcal, ±${f(-left, l, 0)} above the ±${f(t.kcal, l, 0)} target; protein ${f(prot, l, 0)} of ±${f(t.proteinG, l, 0)} g. Now and then is fine; watch the weekly trend.")
    }

    private fun proteinIdeas(l: AppLang): String {
        val picks = Foods.proteinPicks().take(4)
        return picks.joinToString("; ") { fd ->
            val n = fd.per(fd.defaultGrams)
            "1 ${fd.defaultUnit} ${fd.name(l)} (±${f(n[1], l, 0)} g)"
        }
    }

    private fun mealsDay(daysAgo: Int, l: AppLang): String {
        val data = store.load()
        val d = today().minusDays(daysAgo.toLong())
        val ms = mealsOn(data, d)
        val label = dateLabel(d, l)
        if (ms.isEmpty()) return l.t(
            "Belum ada makanan tercatat untuk $label. Contoh: \"sarapan nasi goreng 1 piring dan telur ceplok 2 butir\".",
            "No meals logged for $label. Example: \"breakfast fried rice 1 plate and 2 fried eggs\"."
        )
        val sb = StringBuilder(l.t("Makanan $label:", "Meals $label:"))
        for (m in ms) sb.append("\n• ").append(mealTypeName(m.type, l)).append(": ")
            .append(m.items.joinToString(", ") { foodName(it.name, l) }).append(" — ${f(m.kcal, l, 0)} kkal")
        sb.append("\n").append(l.t("Total: ", "Total: ")).append(totalsLine(ms.sumOf { it.kcal }, ms.sumOf { it.protein }, ms.sumOf { it.carbs }, ms.sumOf { it.fat }, l))
        val t = targetOf(data).target
        if (t != null) {
            val prot = ms.sumOf { it.protein }
            sb.append("\n").append(remainingLine(data, d, l))
            if (daysAgo == 0 && t.proteinG - prot >= 15.0) sb.append("\n").append(l.t(
                "Protein masih kurang ±${f(t.proteinG - prot, l, 0)} g. Ide: ${proteinIdeas(l)}.",
                "Protein is still ±${f(t.proteinG - prot, l, 0)} g short. Ideas: ${proteinIdeas(l)}."))
            if (daysAgo >= 1 && ms.sumOf { it.kcal } < 1000.0) sb.append("\n").append(l.t(
                "Asupannya tampak rendah (di bawah 1000 kkal). Kalau bukan karena lupa mencatat, pastikan makan cukup.",
                "Intake looks low (under 1000 kcal). If that isn't just unlogged meals, make sure you're eating enough."))
        } else sb.append("\n").append(l.t("(Atur profilmu untuk membandingkan dengan target: \"target kalori saya\".)", "(Set up your profile to compare with targets: \"my calorie target\".)"))
        sb.append("\n").append(l.t("Angka gizi adalah perkiraan (±20-30%).", "Nutrition numbers are estimates (±20-30%)."))
        return sb.toString()
    }

    private fun foodInfo(key: String, l: AppLang): String {
        val fd = Foods.byKey[key] ?: return help(l)
        val per = fd.per(fd.defaultGrams)
        val sb = StringBuilder()
        sb.append(l.t("${fd.name(l)} (1 ${fd.defaultUnit} ±${f(fd.defaultGrams, l, 0)} g): ", "${fd.name(l)} (1 ${fd.defaultUnit} ±${f(fd.defaultGrams, l, 0)} g): "))
        sb.append(totalsLine(per[0], per[1], per[2], per[3], l))
        sb.append("\n").append(l.t("Per 100 g: ", "Per 100 g: ")).append(totalsLine(fd.kcal, fd.protein, fd.carbs, fd.fat, l))
        if (fd.units.size > 1) sb.append("\n").append(l.t("Satuan: ", "Units: ")).append(fd.units.entries.joinToString(", ") { "${it.key} = ±${f(it.value, l, 0)} g" })
        sb.append("\n").append(l.t("Perkiraan; masakan nyata bisa beda 20-30% karena minyak, gula, dan porsi.", "Estimates; real dishes can differ by 20-30% due to oil, sugar and portion size."))
        return sb.toString()
    }

    private fun ask(key: String, l: AppLang): String {
        val topic = NutritionKnowledge.topics.firstOrNull { it.key == key } ?: return help(l)
        return NutritionKnowledge.answer(topic, l)
    }

    private fun program(days: Int?, l: AppLang): String {
        val data = store.load()
        val d = days ?: data.profile.daysPerWeek?.takeIf { it in 2..6 } ?: 3
        return Program.build(d, data.profile.goal, l)
    }

    // ------------------------------------------------------------------ ringkasan mingguan

    private fun week(l: AppLang): String {
        val data = store.load()
        val now = clock()
        val sb = StringBuilder()
        var any = false
        val tDays = Progress.trainingDays(data, now, zone, 7)
        val from = FitTime.startOf(today().minusDays(6), zone)
        val sessions = data.workouts.count { it.time >= from && it.time <= now + 86_400_000L }
        if (sessions > 0) {
            any = true
            sb.append(l.t("Latihan: $tDays hari ($sessions sesi).", "Training: $tDays days ($sessions sessions)."))
            val sets = Progress.weeklySets(data, now, zone, 7)
            if (sets.isNotEmpty()) {
                val names = mapOf(
                    Exercises.Group.CHEST to l.t("dada", "chest"), Exercises.Group.BACK to l.t("punggung", "back"), Exercises.Group.LEGS to l.t("kaki", "legs"),
                    Exercises.Group.SHOULDERS to l.t("bahu", "shoulders"), Exercises.Group.ARMS to l.t("lengan", "arms"), Exercises.Group.CORE to l.t("perut", "core"),
                    Exercises.Group.GLUTES to l.t("bokong", "glutes")
                )
                sb.append("\n").append(l.t("Set per kelompok: ", "Sets per group: ")).append(sets.entries.joinToString(", ") { "${names[it.key]} ${it.value}" })
                val missing = listOf(Exercises.Group.CHEST, Exercises.Group.BACK, Exercises.Group.LEGS, Exercises.Group.SHOULDERS).filter { (sets[it] ?: 0) == 0 }
                if (missing.isNotEmpty()) sb.append("\n").append(l.t("Belum dilatih minggu ini: ${missing.joinToString(", ") { names[it]!! }}.", "Not trained this week: ${missing.joinToString(", ") { names[it]!! }}."))
                sb.append("\n").append(l.t("Umumnya 10-20 set keras per kelompok otot per minggu efektif untuk menambah otot.", "Typically 10-20 hard sets per muscle group per week works well for building muscle."))
            }
        }
        val tr = Progress.weightTrend(data, now, zone)
        if (tr?.avg7 != null) {
            any = true
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(l.t("Berat: rata-rata 7 hari ${f(tr.avg7, l)} kg", "Weight: 7-day average ${f(tr.avg7, l)} kg"))
            tr.prevAvg7?.let { val d = tr.avg7 - it; sb.append(l.t(" (${if (d > 0) "+" else ""}${f(d, l)} dari minggu lalu)", " (${if (d > 0) "+" else ""}${f(d, l)} vs last week)")) }
            sb.append(".")
        }
        val perDay = (0..6).map { today().minusDays(it.toLong()) }.map { d -> mealsOn(data, d) }.filter { it.isNotEmpty() }
        if (perDay.isNotEmpty()) {
            any = true
            if (sb.isNotEmpty()) sb.append("\n")
            val avgK = perDay.map { ms -> ms.sumOf { it.kcal } }.average()
            val avgP = perDay.map { ms -> ms.sumOf { it.protein } }.average()
            sb.append(l.t("Makan: rata-rata ±${f(avgK, l, 0)} kkal dan protein ±${f(avgP, l, 0)} g (dari ${perDay.size} hari yang dicatat).", "Eating: average ±${f(avgK, l, 0)} kcal and protein ±${f(avgP, l, 0)} g (from ${perDay.size} logged days)."))
            targetOf(data).target?.let { t ->
                sb.append("\n").append(l.t("Target ±${f(t.kcal, l, 0)} kkal, protein ±${f(t.proteinG, l, 0)} g.", "Target ±${f(t.kcal, l, 0)} kcal, protein ±${f(t.proteinG, l, 0)} g."))
            }
        }
        if (!any) return l.t(
            "Belum ada catatan 7 hari terakhir. Mulai dengan \"bench press 60 kg 3x8\", \"sarapan nasi goreng 1 piring\", atau \"berat badan 72,5\".",
            "Nothing logged in the last 7 days. Start with \"bench press 60 kg 3x8\", \"breakfast fried rice 1 plate\" or \"weight 72.5\"."
        )
        return l.t("Ringkasan 7 hari:\n", "7-day summary:\n") + sb.toString()
    }

    // ------------------------------------------------------------------ InBody

    private fun inbodyStart(l: AppLang): String {
        if (!photoEnabled) return l.t(
            "Pembaca foto InBody sedang dimatikan di Pengaturan. Ketik angkanya saja: \"inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600\".",
            "The InBody photo reader is switched off in Settings. Just type the numbers: \"inbody weight 70 muscle 32 fat 14 pbf 20 visceral 7 bmr 1600\"."
        )
        awaiting = true; awaitingAt = clock()
        return l.t(
            "Kirim foto hasil InBody lewat tombol + di sebelah kolom ketik. Foto tegak lurus, terang, tanpa pantulan, dan seluruh lembar terlihat. Aku membaca angkanya di perangkat ini, lalu kamu konfirmasi sebelum disimpan.\nAtau ketik angkanya: \"inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600\".",
            "Send a photo of your InBody result with the + button next to the text box. Keep it straight, well lit, without glare, with the whole sheet visible. I read the numbers on this device, then you confirm before anything is saved.\nOr type the numbers: \"inbody weight 70 muscle 32 fat 14 pbf 20 visceral 7 bmr 1600\"."
        )
    }

    private fun confirmLine(l: AppLang) = l.t(
        "Benar? Bilang \"ya\" untuk menyimpan dan menganalisis, \"batal\" untuk membuang, atau koreksi angkanya, mis. \"berat 70,5\" atau \"lemak 14\".",
        "Correct? Say \"yes\" to save and analyse, \"cancel\" to discard, or correct a value, e.g. \"weight 70.5\" or \"fat 14\"."
    )

    /** Bacaan dari foto (OCR): tampilkan dulu, simpan hanya setelah pengguna membenarkan. */
    fun proposeInBody(reading: InBodyReading, l: AppLang): String {
        awaiting = false
        val n = reading.fieldCount()
        if (n < 3) {
            draft = null
            return if (n == 0) l.t(
                "Aku tidak bisa membaca angka InBody dari foto itu. Coba foto ulang lebih dekat dan terang, seluruh lembar terlihat, atau ketik angkanya: \"inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600\".",
                "I couldn't read InBody numbers from that photo. Try again closer and brighter with the whole sheet visible, or type them: \"inbody weight 70 muscle 32 fat 14 pbf 20 visceral 7 bmr 1600\"."
            ) else l.t(
                "Aku hanya bisa membaca ${n} angka (${InBodyAnalysis.readback(reading, l)}). Itu terlalu sedikit untuk dianalisis. Foto ulang lebih dekat dan terang, atau ketik angkanya: \"inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600\".",
                "I could only read $n values (${InBodyAnalysis.readback(reading, l)}). That's too few to analyse. Retake it closer and brighter, or type them: \"inbody weight 70 muscle 32 fat 14 pbf 20 visceral 7 bmr 1600\"."
            )
        }
        draft = reading; draftAt = clock()
        return draftText(reading, l.t("Aku membaca dari foto: ", "From the photo I read: "), l)
    }

    private fun draftText(r: InBodyReading, head: String, l: AppLang): String {
        val sb = StringBuilder(head).append(InBodyAnalysis.readback(r, l)).append(".")
        val iss = InBodyAnalysis.issueText(InBodyParser.issues(r), l)
        if (iss.isNotEmpty()) sb.append("\n").append(iss)
        sb.append("\n").append(confirmLine(l))
        return sb.toString()
    }

    /** Dipanggil lebih dulu bila [hasDraft]: "ya"/"batal"/koreksi. Null = ucapan ini bukan bagian dari konfirmasi. */
    fun draftTurn(raw: String, l: AppLang): String? {
        expire()
        val d = draft ?: return null
        val t = FitText.norm(raw)
        if (YES.matches(t)) { draft = null; return saveReading(d, l) }
        if (NO.matches(t)) { draft = null; return l.t("Oke, bacaan InBody dibuang. Kirim foto lagi atau ketik angkanya kapan saja.", "OK, the InBody reading is discarded. Send another photo or type the numbers any time.") }
        val corr = InBodyParser.parseCorrection(raw) ?: return null
        val merged = InBodyParser.merge(d, corr)
        draft = merged; draftAt = clock()
        return draftText(merged, l.t("Diperbarui: ", "Updated: "), l)
    }

    private fun inbodyManual(reading: InBodyReading, l: AppLang): String {
        if (InBodyParser.issues(reading).isNotEmpty()) {
            draft = reading; draftAt = clock(); awaiting = false
            return draftText(reading, l.t("Aku menangkap: ", "I caught: "), l)
        }
        awaiting = false
        return l.t("Tersimpan. Terbaca: ", "Saved. Read: ") + InBodyAnalysis.readback(reading, l) + ".\n\n" + saveReading(reading, l, saveOnlyHint = true)
    }

    private fun saveReading(r0: InBodyReading, l: AppLang, saveOnlyHint: Boolean = false): String {
        val data = store.load()
        val now = clock()
        val r = r0.copy(time = now)
        val prev = data.inbody.lastOrNull()
        var p = data.profile
        val filled = ArrayList<String>()
        if (p.sex == null && r.sex != null) { p = p.copy(sex = r.sex); filled.add(l.t("jenis kelamin", "sex")) }
        if (p.age == null && r.age != null && r.age in 10..99) { p = p.copy(age = r.age); filled.add(l.t("usia", "age")) }
        if (p.heightCm == null && r.heightCm != null && r.heightCm in 100.0..230.0) { p = p.copy(heightCm = r.heightCm); filled.add(l.t("tinggi", "height")) }
        data.profile = p
        data.inbody.add(r)
        trim(data.inbody, MAX_INBODY)
        if (r.weightKg != null && r.weightKg in 25.0..250.0) addWeightIfNew(data, r.weightKg, now)
        store.save(data)
        val sb = StringBuilder()
        if (!saveOnlyHint) sb.append(l.t("Hasil InBody tersimpan.\n\n", "InBody result saved.\n\n"))
        sb.append(InBodyAnalysis.analyze(r, data.profile, prev, l))
        if (filled.isNotEmpty()) sb.append("\n\n").append(l.t("Data ${filled.joinToString(", ")} dari lembar ini kupakai untuk profilmu.", "I used the ${filled.joinToString(", ")} from this sheet for your profile."))
        sb.append("\n").append(l.t("Salah simpan? Bilang \"hapus inbody terakhir\".", "Saved by mistake? Say \"delete last inbody\"."))
        return sb.toString()
    }

    private fun inbodyLast(l: AppLang): String {
        val data = store.load()
        val last = data.inbody.lastOrNull() ?: return l.t(
            "Belum ada hasil InBody tersimpan. Bilang \"analisis inbody\" lalu kirim fotonya.",
            "No InBody results saved yet. Say \"analyse inbody\" then send the photo."
        )
        val prev = data.inbody.getOrNull(data.inbody.size - 2)
        return l.t("Hasil InBody terakhir (${shortDate(last.time)}):\n", "Latest InBody result (${shortDate(last.time)}):\n") + InBodyAnalysis.analyze(last, data.profile, prev, l)
    }

    private fun inbodyCompare(l: AppLang): String {
        val data = store.load()
        if (data.inbody.size < 2) return l.t(
            "Perlu minimal dua hasil InBody tersimpan untuk dibandingkan. Sekarang baru ${data.inbody.size}.",
            "I need at least two saved InBody results to compare. You have ${data.inbody.size} now."
        )
        val a = data.inbody[data.inbody.size - 2]; val b = data.inbody.last()
        return l.t("InBody ${shortDate(a.time)} → ${shortDate(b.time)}\n", "InBody ${shortDate(a.time)} → ${shortDate(b.time)}\n") + InBodyAnalysis.compare(a, b, l)
    }

    // ------------------------------------------------------------------ hapus terakhir, bantuan, kekhawatiran pola makan

    private fun undo(kind: HealthRequest.UndoKind, l: AppLang): String {
        val data = store.load()
        val msg: String = when (kind) {
            HealthRequest.UndoKind.WORKOUT -> {
                val w = data.workouts.maxByOrNull { it.id } ?: return l.t("Tidak ada catatan latihan yang bisa dihapus.", "There's no workout log to delete.")
                data.workouts.remove(w)
                l.t("Latihan terakhir dihapus: ", "Last workout deleted: ") + w.entries.joinToString(", ") { entryLine(it, l) } + "."
            }
            HealthRequest.UndoKind.MEAL -> {
                val m = data.meals.maxByOrNull { it.id } ?: return l.t("Tidak ada catatan makanan yang bisa dihapus.", "There's no meal log to delete.")
                data.meals.remove(m)
                l.t("Makanan terakhir dihapus: ", "Last meal deleted: ") + m.items.joinToString(", ") { foodName(it.name, l) } + " (${f(m.kcal, l, 0)} kkal)."
            }
            HealthRequest.UndoKind.WEIGHT -> {
                val w = data.weights.maxByOrNull { it.id } ?: return l.t("Tidak ada catatan berat badan yang bisa dihapus.", "There's no weight log to delete.")
                data.weights.remove(w)
                l.t("Berat ${f(w.kg, l)} kg (${shortDate(w.time)}) dihapus.", "Weight ${f(w.kg, l)} kg (${shortDate(w.time)}) deleted.")
            }
            HealthRequest.UndoKind.INBODY -> {
                val r = data.inbody.lastOrNull() ?: return l.t("Tidak ada hasil InBody yang bisa dihapus.", "There's no InBody result to delete.")
                data.inbody.removeAt(data.inbody.lastIndex)
                l.t("Hasil InBody ${shortDate(r.time)} dihapus.", "InBody result from ${shortDate(r.time)} deleted.")
            }
        }
        store.save(data)
        return msg
    }

    private fun eatingConcern(l: AppLang): String = l.t(
        "Terima kasih sudah cerita; itu terdengar berat. Untuk hal seperti ini aku tidak akan menghitung kalori atau membuat target berat badan, karena itu bisa memperburuk keadaan. Bicaralah dengan orang yang kamu percaya, dan pertimbangkan dokter, psikolog, atau ahli gizi klinis; pola makan seperti ini bisa dibantu.\n\nKalau kamu mau bercerita padaku, bilang \"aku mau curhat\". Kalau kamu merasa ingin menyakiti diri atau dalam bahaya, hubungi ${CrisisResources.EMERGENCY} atau Healing119: ${CrisisResources.healingId()} (gratis, 24 jam).",
        "Thank you for telling me; that sounds hard. For something like this I won't count calories or set weight targets, since that can make things worse. Please talk to someone you trust, and consider a doctor, psychologist or clinical dietitian; eating patterns like this can be helped.\n\nIf you'd like to talk it through with me, say \"I want to vent\". If you feel like hurting yourself or you're in danger, call ${CrisisResources.EMERGENCY} (Indonesia) or Healing119: ${CrisisResources.healingEn()} (free, 24 hours)."
    )

    fun help(l: AppLang): String = l.t(
        "Pelatih fitness + ahli gizi. Contoh:\n• Latihan: \"bench press 60 kg 3x8\", \"lari 5 km 30 menit\", \"riwayat latihan\", \"progres squat\", \"saran beban squat\"\n• Berat: \"berat badan 72,5\", \"tren berat badan\"\n• Makan: \"sarapan nasi goreng 1 piring dan telur 2 butir\", \"makan apa hari ini\", \"kalori ayam goreng\"\n• Target: \"aku laki-laki 28 tahun tinggi 170 berat 72\", \"target saya cutting\", \"target kalori saya\"\n• Program: \"program latihan 4 hari\"\n• Ilmu gizi: \"manfaat protein\", \"apa itu defisit kalori\"\n• InBody: \"analisis inbody\" lalu kirim foto\n• Salah catat: \"hapus makan terakhir\" / \"hapus latihan terakhir\"\nCatatan: angka gizi hanya perkiraan; ini bukan nasihat medis.",
        "Fitness coach + nutrition specialist. Examples:\n• Workouts: \"bench press 60 kg 3x8\", \"run 5 km 30 minutes\", \"workout history\", \"squat progress\", \"suggest squat weight\"\n• Weight: \"weight 72.5\", \"weight trend\"\n• Meals: \"breakfast fried rice 1 plate and 2 eggs\", \"what did I eat today\", \"calories in fried chicken\"\n• Targets: \"I'm male 28 years old height 170 weight 72\", \"my goal is cutting\", \"my calorie target\"\n• Program: \"4 day workout plan\"\n• Nutrition science: \"benefits of protein\", \"what is a calorie deficit\"\n• InBody: \"analyse inbody\" then send a photo\n• Mistakes: \"delete last meal\" / \"delete last workout\"\nNote: nutrition numbers are estimates only; this isn't medical advice."
    )
}

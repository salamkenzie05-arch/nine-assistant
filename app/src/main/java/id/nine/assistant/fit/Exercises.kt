package id.nine.assistant.fit

/** Kamus gerakan latihan (ID+EN) dengan nama lain yang biasa diucapkan. */
object Exercises {
    enum class Kind { STRENGTH, BODYWEIGHT, CARDIO, HOLD }
    enum class Group { CHEST, BACK, LEGS, SHOULDERS, ARMS, CORE, GLUTES, CARDIO }

    class Def(
        val key: String,
        val kind: Kind,
        val group: Group,
        val id: String,
        val en: String,
        val aliases: List<String>,
        /** Kenaikan beban terkecil yang lazim (kg). */
        val step: Double = 2.5,
        val compound: Boolean = false
    ) {
        fun name(l: id.nine.assistant.core.AppLang): String = l.t(id, en)
    }

    private fun d(key: String, kind: Kind, group: Group, id: String, en: String, vararg aliases: String, step: Double = 2.5, compound: Boolean = false) =
        Def(key, kind, group, id, en, aliases.toList(), step, compound)

    val all: List<Def> = listOf(
        d("bench_press", Kind.STRENGTH, Group.CHEST, "Bench press", "Bench press", "bench press", "benchpress", "bench", "barbell bench press", "flat bench", "press dada", "bench pres", compound = true),
        d("incline_bench", Kind.STRENGTH, Group.CHEST, "Incline bench press", "Incline bench press", "incline bench press", "incline bench", "incline press", "bench incline", compound = true),
        d("dumbbell_press", Kind.STRENGTH, Group.CHEST, "Dumbbell press", "Dumbbell press", "dumbbell press", "dumbbell bench press", "db press", "press dumbbell", "dumbell press", compound = true),
        d("chest_fly", Kind.STRENGTH, Group.CHEST, "Chest fly", "Chest fly", "chest fly", "dumbbell fly", "cable fly", "pec fly", "pec deck", "cable crossover"),
        d("push_up", Kind.BODYWEIGHT, Group.CHEST, "Push up", "Push-up", "push up", "pushup", "push ups", "pushups", compound = true),
        d("dips", Kind.BODYWEIGHT, Group.CHEST, "Dips", "Dips", "dips", "dip", "chest dips", compound = true),
        d("deadlift", Kind.STRENGTH, Group.BACK, "Deadlift", "Deadlift", "deadlift", "dead lift", "conventional deadlift", "angkat mati", step = 5.0, compound = true),
        d("barbell_row", Kind.STRENGTH, Group.BACK, "Barbell row", "Barbell row", "barbell row", "bent over row", "bentover row", "pendlay row", "row", compound = true),
        d("seated_row", Kind.STRENGTH, Group.BACK, "Seated cable row", "Seated cable row", "seated row", "seated cable row", "cable row", "mesin row"),
        d("lat_pulldown", Kind.STRENGTH, Group.BACK, "Lat pulldown", "Lat pulldown", "lat pulldown", "lat pull down", "pulldown", "pull down", "tarik lat", compound = true),
        d("pull_up", Kind.BODYWEIGHT, Group.BACK, "Pull up", "Pull-up", "pull up", "pullup", "pull ups", "pullups", "chin up", "chinup", "chin ups", compound = true),
        d("dumbbell_row", Kind.STRENGTH, Group.BACK, "Dumbbell row", "Dumbbell row", "dumbbell row", "one arm row", "single arm row", "db row"),
        d("squat", Kind.STRENGTH, Group.LEGS, "Squat", "Squat", "squat", "squats", "back squat", "barbell squat", "high bar squat", step = 5.0, compound = true),
        d("front_squat", Kind.STRENGTH, Group.LEGS, "Front squat", "Front squat", "front squat", step = 5.0, compound = true),
        d("goblet_squat", Kind.STRENGTH, Group.LEGS, "Goblet squat", "Goblet squat", "goblet squat", compound = true),
        d("leg_press", Kind.STRENGTH, Group.LEGS, "Leg press", "Leg press", "leg press", "legpress", step = 5.0, compound = true),
        d("leg_extension", Kind.STRENGTH, Group.LEGS, "Leg extension", "Leg extension", "leg extension", "leg ext"),
        d("leg_curl", Kind.STRENGTH, Group.LEGS, "Leg curl", "Leg curl", "leg curl", "hamstring curl", "lying leg curl", "seated leg curl"),
        d("rdl", Kind.STRENGTH, Group.LEGS, "Romanian deadlift", "Romanian deadlift", "romanian deadlift", "rdl", "stiff leg deadlift", "stiff legged deadlift", step = 5.0, compound = true),
        d("lunge", Kind.STRENGTH, Group.LEGS, "Lunge", "Lunge", "lunge", "lunges", "walking lunge", "split squat", "bulgarian split squat", compound = true),
        d("hip_thrust", Kind.STRENGTH, Group.GLUTES, "Hip thrust", "Hip thrust", "hip thrust", "hipthrust", "glute bridge", step = 5.0, compound = true),
        d("calf_raise", Kind.STRENGTH, Group.LEGS, "Calf raise", "Calf raise", "calf raise", "calf raises", "betis", "calf press"),
        d("overhead_press", Kind.STRENGTH, Group.SHOULDERS, "Overhead press", "Overhead press", "overhead press", "ohp", "shoulder press", "military press", "press bahu", "dumbbell shoulder press", compound = true),
        d("lateral_raise", Kind.STRENGTH, Group.SHOULDERS, "Lateral raise", "Lateral raise", "lateral raise", "lateral raises", "side raise", "side lateral raise", step = 1.0),
        d("face_pull", Kind.STRENGTH, Group.SHOULDERS, "Face pull", "Face pull", "face pull", "facepull", step = 1.0),
        d("shrug", Kind.STRENGTH, Group.SHOULDERS, "Shrug", "Shrug", "shrug", "shrugs", "dumbbell shrug", "barbell shrug"),
        d("bicep_curl", Kind.STRENGTH, Group.ARMS, "Bicep curl", "Biceps curl", "bicep curl", "biceps curl", "barbell curl", "dumbbell curl", "hammer curl", "preacher curl", "curl", "curls", step = 1.0),
        d("tricep_pushdown", Kind.STRENGTH, Group.ARMS, "Tricep pushdown", "Triceps pushdown", "tricep pushdown", "triceps pushdown", "pushdown", "push down", "tricep extension", "triceps extension", "skull crusher", "skullcrusher", "overhead tricep extension", step = 1.0),
        d("plank", Kind.HOLD, Group.CORE, "Plank", "Plank", "plank", "planking"),
        d("crunch", Kind.BODYWEIGHT, Group.CORE, "Crunch / sit up", "Crunch / sit-up", "crunch", "crunches", "sit up", "situp", "sit ups", "situps"),
        d("leg_raise", Kind.BODYWEIGHT, Group.CORE, "Leg raise", "Leg raise", "leg raise", "leg raises", "hanging leg raise"),
        d("running", Kind.CARDIO, Group.CARDIO, "Lari", "Running", "lari", "jogging", "joging", "jog", "ran", "run", "running", "lari pagi", "lari sore", "treadmill", "lari di treadmill"),
        d("cycling", Kind.CARDIO, Group.CARDIO, "Bersepeda", "Cycling", "bersepeda", "sepeda", "cycling", "sepeda statis", "spinning", "gowes"),
        d("swimming", Kind.CARDIO, Group.CARDIO, "Berenang", "Swimming", "berenang", "renang", "swimming"),
        d("walking", Kind.CARDIO, Group.CARDIO, "Jalan kaki", "Walking", "jalan kaki", "jalan cepat", "jalan santai", "walking", "brisk walk"),
        d("jump_rope", Kind.CARDIO, Group.CARDIO, "Lompat tali", "Jump rope", "lompat tali", "skipping", "jump rope"),
        d("elliptical", Kind.CARDIO, Group.CARDIO, "Elliptical", "Elliptical", "elliptical", "cross trainer", "crosstrainer"),
        d("hiit", Kind.CARDIO, Group.CARDIO, "HIIT", "HIIT", "hiit", "interval training")
    )

    val byKey: Map<String, Def> = all.associateBy { it.key }

    class Hit(val def: Def, val start: Int, val end: Int)

    private val aliasIndex: List<Pair<String, Def>> =
        all.flatMap { d -> d.aliases.map { it to d } }.sortedByDescending { it.first.length }

    /** Cari semua penyebutan gerakan dalam teks yang sudah dinormalkan (huruf kecil, tanpa tanda baca). Terpanjang menang. */
    fun find(norm: String): List<Hit> {
        val taken = BooleanArray(norm.length)
        val hits = ArrayList<Hit>()
        for ((alias, def) in aliasIndex) {
            var from = 0
            while (true) {
                val i = norm.indexOf(alias, from)
                if (i < 0) break
                val j = i + alias.length
                from = j
                val leftOk = i == 0 || !norm[i - 1].isLetterOrDigit()
                val rightOk = j == norm.length || !norm[j].isLetterOrDigit()
                if (!leftOk || !rightOk) continue
                var free = true
                for (k in i until j) if (taken[k]) { free = false; break }
                if (!free) continue
                for (k in i until j) taken[k] = true
                hits.add(Hit(def, i, j))
            }
        }
        return hits.sortedBy { it.start }
    }

    /** Pencarian satu gerakan dari frasa pendek ("bench", "squat saya"). */
    fun lookup(norm: String): Def? = find(norm).firstOrNull()?.def
}

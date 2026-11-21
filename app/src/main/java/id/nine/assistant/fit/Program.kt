package id.nine.assistant.fit

import id.nine.assistant.core.AppLang

/**
 * Templat program latihan beban untuk pemula-menengah (full body, upper/lower, push/pull/legs). Pola standar yang
 * lazim; bukan program yang disesuaikan dengan cedera atau kondisi medis. Kotlin murni.
 */
object Program {
    class Move(val key: String, val sets: Int, val reps: String)
    class Template(val code: String, val title: String, val moves: List<Move>)

    private fun m(key: String, sets: Int, reps: String) = Move(key, sets, reps)

    private val FB_A = Template("FB_A", "Full body A", listOf(
        m("squat", 3, "6-10"), m("bench_press", 3, "6-10"), m("barbell_row", 3, "8-12"), m("overhead_press", 2, "8-12"), m("plank", 3, "30-60 dtk")))
    private val FB_B = Template("FB_B", "Full body B", listOf(
        m("rdl", 3, "8-10"), m("dumbbell_press", 3, "8-12"), m("lat_pulldown", 3, "8-12"), m("lunge", 2, "10-12"), m("bicep_curl", 2, "10-15"), m("tricep_pushdown", 2, "10-15")))
    private val UP_A = Template("UP_A", "Upper A", listOf(
        m("bench_press", 3, "6-10"), m("barbell_row", 3, "6-10"), m("overhead_press", 3, "8-12"), m("lat_pulldown", 3, "8-12"), m("bicep_curl", 2, "10-15"), m("tricep_pushdown", 2, "10-15")))
    private val LO_A = Template("LO_A", "Lower A", listOf(
        m("squat", 3, "6-10"), m("rdl", 3, "8-10"), m("leg_press", 3, "10-12"), m("leg_curl", 2, "10-15"), m("calf_raise", 3, "10-15"), m("plank", 3, "30-60 dtk")))
    private val UP_B = Template("UP_B", "Upper B", listOf(
        m("incline_bench", 3, "8-12"), m("seated_row", 3, "8-12"), m("lateral_raise", 3, "12-15"), m("lat_pulldown", 3, "8-12"), m("face_pull", 2, "12-20"), m("bicep_curl", 2, "10-15")))
    private val LO_B = Template("LO_B", "Lower B", listOf(
        m("hip_thrust", 3, "8-12"), m("goblet_squat", 3, "8-12"), m("lunge", 3, "10-12"), m("leg_extension", 2, "12-15"), m("leg_curl", 2, "10-15"), m("leg_raise", 3, "10-15")))
    private val PUSH = Template("PUSH", "Push", listOf(
        m("bench_press", 3, "6-10"), m("overhead_press", 3, "8-12"), m("incline_bench", 3, "8-12"), m("lateral_raise", 3, "12-15"), m("tricep_pushdown", 3, "10-15")))
    private val PULL = Template("PULL", "Pull", listOf(
        m("barbell_row", 3, "6-10"), m("lat_pulldown", 3, "8-12"), m("seated_row", 3, "8-12"), m("face_pull", 3, "12-20"), m("bicep_curl", 3, "10-15")))
    private val LEGS = Template("LEGS", "Legs", listOf(
        m("squat", 3, "6-10"), m("rdl", 3, "8-10"), m("leg_press", 3, "10-12"), m("leg_curl", 3, "10-15"), m("calf_raise", 3, "10-15")))

    /** Urutan hari dalam seminggu untuk [days] hari latihan. */
    fun schedule(days: Int): List<Template> = when (days.coerceIn(2, 6)) {
        2 -> listOf(FB_A, FB_B)
        3 -> listOf(FB_A, FB_B, FB_A)
        4 -> listOf(UP_A, LO_A, UP_B, LO_B)
        5 -> listOf(PUSH, PULL, LEGS, UP_B, LO_B)
        else -> listOf(PUSH, PULL, LEGS, PUSH, PULL, LEGS)
    }

    val allTemplates: List<Template> = listOf(FB_A, FB_B, UP_A, LO_A, UP_B, LO_B, PUSH, PULL, LEGS)

    fun build(daysIn: Int, goal: Goal?, l: AppLang): String {
        val days = daysIn.coerceIn(2, 6)
        val sched = schedule(days)
        val sb = StringBuilder()
        sb.append(l.t("Program $days hari seminggu", "$days-day weekly program")).append(":\n")
        sb.append(sched.mapIndexed { i, t -> "${i + 1}. ${t.title}" }.joinToString(" • ")).append("\n\n")
        for (t in sched.distinctBy { it.code }) {
            sb.append(t.title).append(":\n")
            for (mv in t.moves) {
                val def = Exercises.byKey[mv.key]
                val name = def?.name(l) ?: mv.key
                val reps = mv.reps.replace("dtk", l.t("dtk", "sec"))
                sb.append("• $name ${mv.sets}×$reps\n")
            }
            sb.append('\n')
        }
        sb.append(l.t(
            "Cara pakai:\n• Pemanasan 5-10 menit, lalu 1-2 set ringan sebelum gerakan utama.\n• Istirahat 1,5-3 menit untuk gerakan besar, 60-90 detik untuk gerakan kecil.\n• Sisakan 1-3 rep cadangan di sebagian besar set; teknik dulu, baru beban.\n• Bila semua set mencapai rep atas, naikkan beban sedikit (±2,5 kg) dan mulai lagi dari rep bawah.\n• Deload (volume turun ±40-50%) tiap 6-8 minggu atau bila performa terus turun.\n• Kardio sedang 2-3 kali seminggu 20-30 menit; jalan kaki harian juga membantu.",
            "How to use:\n• Warm up 5-10 minutes, then 1-2 light sets before the main lift.\n• Rest 1.5-3 minutes for big lifts, 60-90 seconds for small ones.\n• Leave 1-3 reps in reserve on most sets; technique first, then load.\n• When every set hits the top of the rep range, add a little weight (±2.5 kg) and restart from the low end.\n• Deload (volume down ±40-50%) every 6-8 weeks or when performance keeps dropping.\n• Moderate cardio 2-3 times a week for 20-30 minutes; daily walking helps too."))
        when (goal) {
            Goal.CUT -> sb.append("\n\n").append(l.t("Saat cutting: tetap angkat beban cukup berat agar otot terjaga, defisit kalori moderat, protein tinggi.", "While cutting: keep lifting reasonably heavy to hold on to muscle, use a moderate deficit and keep protein high."))
            Goal.BULK -> sb.append("\n\n").append(l.t("Saat menambah massa: surplus kecil (±5-10%), tidur cukup, dan naikkan beban bertahap.", "While gaining: a small surplus (±5-10%), enough sleep and gradual load increases."))
            else -> {}
        }
        sb.append("\n\n").append(l.t(
            "Ini templat umum. Ada cedera, nyeri sendi, penyakit jantung/tekanan darah, atau hamil? Konsultasikan dulu ke dokter atau pelatih bersertifikat. Berhenti bila nyeri tajam.",
            "This is a general template. With an injury, joint pain, heart/blood-pressure conditions or pregnancy, check with a doctor or certified trainer first. Stop if you feel sharp pain."))
        return sb.toString()
    }
}

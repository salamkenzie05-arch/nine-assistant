package id.nine.assistant.fit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data pelatih fitness + ahli gizi. Kotlin murni, tanpa Android: semua hitungan dan teks bisa diuji di JVM.
 * Penyimpanan lewat [FitStore]; di aplikasi berupa berkas pribadi, di tes berupa memori.
 */
enum class Sex { MALE, FEMALE }

enum class Goal { CUT, BULK, MAINTAIN, RECOMP }

enum class Activity(val factor: Double) { LOW(1.2), LIGHT(1.375), MODERATE(1.55), HIGH(1.725) }

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

data class Profile(
    val sex: Sex? = null,
    val age: Int? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val goal: Goal? = null,
    val activity: Activity? = null,
    val daysPerWeek: Int? = null
)

/** Satu baris latihan: [sets] set berisi [reps] pengulangan dengan beban [weightKg] (0 = berat badan), atau kardio/tahan. */
data class WorkoutEntry(
    val exercise: String,
    val sets: Int = 1,
    val reps: Int = 0,
    val weightKg: Double = 0.0,
    val minutes: Double = 0.0,
    val km: Double = 0.0,
    val seconds: Int = 0
)

data class Workout(val id: Long, val time: Long, val entries: List<WorkoutEntry>)

data class WeightEntry(val id: Long, val time: Long, val kg: Double)

/** Satu makanan yang dicatat; angka gizi sudah dihitung untuk seluruh porsi yang dimakan. */
data class MealItem(
    val name: String,
    val qty: Double,
    val unit: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val estimated: Boolean = true
)

data class Meal(val id: Long, val time: Long, val type: MealType, val items: List<MealItem>) {
    val kcal: Double get() = items.sumOf { it.kcal }
    val protein: Double get() = items.sumOf { it.protein }
    val carbs: Double get() = items.sumOf { it.carbs }
    val fat: Double get() = items.sumOf { it.fat }
}

/** Hasil pemeriksaan komposisi tubuh (InBody). Nilai yang tidak terbaca = null. Rentang normal dari lembar hasil bila ada. */
data class InBodyReading(
    val time: Long = 0L,
    val weightKg: Double? = null,
    val smmKg: Double? = null,
    val bodyFatKg: Double? = null,
    val pbf: Double? = null,
    val bmi: Double? = null,
    val visceral: Double? = null,
    val bmr: Double? = null,
    val tbwL: Double? = null,
    val ffmKg: Double? = null,
    val score: Double? = null,
    val heightCm: Double? = null,
    val age: Int? = null,
    val sex: Sex? = null,
    /** Rentang normal tercetak di lembar: nama metrik -> (bawah, atas). */
    val ranges: Map<String, Pair<Double, Double>> = emptyMap(),
    /** Massa otot per bagian (kg): RA, LA, TR, RL, LL. */
    val segments: Map<String, Double> = emptyMap(),
    /** Persentase terhadap normal per bagian (mis. 108.5). */
    val segmentPct: Map<String, Double> = emptyMap()
) {
    fun fieldCount(): Int = listOfNotNull(weightKg, smmKg, bodyFatKg, pbf, bmi, visceral, bmr, tbwL, ffmKg, score).size
}

class FitData {
    var profile: Profile = Profile()
    val workouts = ArrayList<Workout>()
    val weights = ArrayList<WeightEntry>()
    val meals = ArrayList<Meal>()
    val inbody = ArrayList<InBodyReading>()
    var nextId: Long = 1

    fun newId(): Long = nextId++
}

interface FitStore {
    fun load(): FitData
    fun save(data: FitData)
}

/** Penyimpanan di memori (tes). */
class MemoryFitStore : FitStore {
    var raw: String = ""
    override fun load(): FitData = FitCodec.decode(raw)
    override fun save(data: FitData) { raw = FitCodec.encode(data) }
}

object FitTime {
    fun day(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    fun startOf(d: LocalDate, zone: ZoneId): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    /** Siang hari di tanggal [d] (untuk catatan "kemarin"). */
    fun noon(d: LocalDate, zone: ZoneId): Long = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}

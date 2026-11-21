package id.nine.assistant.fit

/** Permintaan pengguna ke pelatih fitness / ahli gizi, hasil penguraian ucapan. */
sealed class HealthRequest {
    data class LogWorkout(val entries: List<WorkoutEntry>, val dayOffset: Int, val warnings: List<WorkoutParser.Warning>) : HealthRequest()
    /** "habis gym" tanpa rincian: tanyakan apa yang dilatih. */
    data object WorkoutPrompt : HealthRequest()
    data class WorkoutHistory(val days: Int) : HealthRequest()
    data class ExerciseProgress(val exercise: String) : HealthRequest()
    data class SuggestWeight(val exercise: String) : HealthRequest()
    data class LogBodyWeight(val kg: Double) : HealthRequest()
    data object WeightTrend : HealthRequest()
    data class SetProfile(
        val sex: Sex? = null, val age: Int? = null, val heightCm: Double? = null, val weightKg: Double? = null,
        val activity: Activity? = null, val days: Int? = null
    ) : HealthRequest()
    data class SetGoal(val goal: Goal) : HealthRequest()
    data object ShowTargets : HealthRequest()
    data class LogMeal(val parsed: MealParser.Parsed) : HealthRequest()
    data class MealsDay(val daysAgo: Int) : HealthRequest()
    data object WeekSummary : HealthRequest()
    data class FoodInfo(val foodKey: String) : HealthRequest()
    data class Ask(val topicKey: String) : HealthRequest()
    data class Program(val days: Int?) : HealthRequest()
    data object InBodyStart : HealthRequest()
    data class InBodyManual(val reading: InBodyReading) : HealthRequest()
    data object InBodyLast : HealthRequest()
    data object InBodyCompare : HealthRequest()
    enum class UndoKind { WORKOUT, MEAL, WEIGHT, INBODY }
    data class Undo(val kind: UndoKind) : HealthRequest()
    /** Tanda pola makan yang mengkhawatirkan: tanpa angka/target, arahkan ke bantuan manusia. */
    data object EatingConcern : HealthRequest()
    data object Help : HealthRequest()
}

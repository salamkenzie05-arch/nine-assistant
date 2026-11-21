package id.nine.assistant.fit

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramKbTest {
    @Test fun everyProgramMoveExistsInExerciseDb() {
        for (t in Program.allTemplates) {
            assertTrue(t.code, t.moves.isNotEmpty())
            for (m in t.moves) assertNotNull("${t.code}: ${m.key}", Exercises.byKey[m.key])
        }
    }

    @Test fun scheduleLengthsAndClamp() {
        for (d in 2..6) assertEquals(d, Program.schedule(d).size)
        assertEquals(2, Program.schedule(0).size)
        assertEquals(6, Program.schedule(99).size)
    }

    @Test fun programTextBothLanguages() {
        for (d in 2..6) for (l in listOf(AppLang.ID, AppLang.EN)) {
            val t = Program.build(d, Goal.CUT, l)
            assertTrue(t, t.contains(Program.schedule(d).first().title))
            assertTrue("peringatan dokter/cedera: $t", t.contains("dokter") || t.contains("doctor"))
        }
    }

    @Test fun kbKeysUniqueAndAnswersCarryDisclaimer() {
        val keys = NutritionKnowledge.topics.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        for (tp in NutritionKnowledge.topics) {
            assertTrue(tp.key, tp.id.length > 60 && tp.en.length > 60)
            assertTrue(NutritionKnowledge.answer(tp, AppLang.ID).endsWith("bukan nasihat medis.)"))
            assertTrue(NutritionKnowledge.answer(tp, AppLang.EN).endsWith("not medical advice.)"))
        }
    }

    @Test fun kbTriggersMatchSamples() {
        val samples = mapOf(
            "protein_need" to listOf("berapa protein sehari", "how much protein do i need", "manfaat protein"),
            "protein_sources" to listOf("sumber protein", "high protein foods"),
            "carbs" to listOf("apa itu karbohidrat", "carbs before training"),
            "fiber" to listOf("berapa serat sehari", "fiber intake"),
            "eating_out" to listOf("makan di luar", "nasi padang"),
            "fat" to listOf("lemak sehat", "omega 3")
        )
        for ((key, list) in samples) for (s in list) {
            val topic = NutritionKnowledge.match(FitText.norm(s))
            assertNotNull(s, topic)
            assertEquals(s, key, topic!!.key)
        }
    }

    @Test fun kbDoesNotMatchUnrelatedText() {
        for (s in listOf("halo apa kabar", "pasang alarm jam 6", "bench press 60 kg 3x8", "set a timer for 10 minutes")) {
            assertFalse(s, NutritionKnowledge.match(FitText.norm(s)) != null)
        }
    }
}

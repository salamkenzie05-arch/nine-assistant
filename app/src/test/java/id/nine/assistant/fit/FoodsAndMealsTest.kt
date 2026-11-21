package id.nine.assistant.fit

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodsAndMealsTest {
    private fun meal(s: String) = MealParser.parse(s)

    // ------------------------------------------------------------------ database

    @Test fun keysAndAliasesAreUnique() {
        assertEquals(Foods.all.size, Foods.all.map { it.key }.toSet().size)
        val seen = HashMap<String, String>()
        for (fd in Foods.all) for (a in fd.aliases) {
            val prev = seen.put(a, fd.key)
            assertTrue("alias '$a' dipakai ${prev} dan ${fd.key}", prev == null || prev == fd.key)
        }
        for (fd in Foods.all) {
            assertTrue(fd.key, fd.aliases.isNotEmpty())
            assertTrue(fd.key, fd.units.isNotEmpty() && fd.units.values.all { it > 0.0 })
            assertTrue(fd.key + " kalori", fd.kcal >= 0.0 && fd.kcal <= 900.0)
        }
    }

    /** Kalori harus konsisten dengan makronutrien (Atwater 4/4/9), toleransi karena serat dan pembulatan. */
    @Test fun energyMatchesMacros() {
        val bad = ArrayList<String>()
        for (fd in Foods.all) {
            val atwater = 4 * fd.protein + 4 * fd.carbs + 9 * fd.fat
            val tol = Math.max(12.0, fd.kcal * 0.18)
            if (Math.abs(atwater - fd.kcal) > tol) bad.add("${fd.key}: kcal=${fd.kcal} atwater=$atwater")
        }
        assertTrue("energi tidak konsisten:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun macroSumsAreSane() {
        for (fd in Foods.all) {
            assertTrue("${fd.key} makro > 100 g per 100 g", fd.protein + fd.carbs + fd.fat <= 100.5)
        }
    }

    @Test fun proteinPicksExist() {
        val picks = Foods.proteinPicks()
        assertEquals(10, picks.size)
        for (fd in picks) assertTrue(fd.key + " protein tinggi per kalori", fd.protein * 4.0 / fd.kcal >= 0.3)
    }

    @Test fun longestAliasWins() {
        assertEquals("nasi_goreng", Foods.lookup("nasi goreng")!!.key)
        assertEquals("nasi_padang", Foods.lookup("nasi padang")!!.key)
        assertEquals("nasi_putih", Foods.lookup("nasi")!!.key)
        assertEquals("ayam_goreng", Foods.lookup("ayam goreng")!!.key)
        assertNotNull(Foods.lookup("dada ayam"))
        assertEquals(null, Foods.lookup("batu kerikil"))
    }

    @Test fun wordBoundaries() {
        // "teh" tidak boleh cocok di dalam kata lain
        assertEquals(null, Foods.lookup("tehnologi"))
        assertEquals(null, Foods.lookup("ikanku"))
    }

    // ------------------------------------------------------------------ pengurai menu

    @Test fun breakfastWithQuantities() {
        val p = meal("sarapan nasi goreng 1 piring dan telur ceplok 2 butir")
        assertEquals(MealType.BREAKFAST, p.type)
        assertEquals(2, p.items.size)
        assertEquals("nasi_goreng", p.items[0].name); assertEquals(250.0 * 1.8, p.items[0].kcal, 0.5)
        assertEquals("telur_goreng", p.items[1].name); assertEquals(2.0, p.items[1].qty, 0.0)
        assertEquals(2 * 46.0 * 1.96, p.items[1].kcal, 0.5)
        assertTrue(p.unknown.isEmpty())
    }

    @Test fun quantityBeforeOrAfterName() {
        assertEquals(2.0, meal("makan 2 telur rebus").items.single().qty, 0.0)
        assertEquals(2.0, meal("makan telur rebus 2 butir").items.single().qty, 0.0)
        assertEquals(0.5, meal("makan setengah piring nasi").items.single().qty, 0.0)
    }

    @Test fun gramsAndMl() {
        val p = meal("makan dada ayam 150 gram").items.single()
        assertEquals("g", p.unit); assertEquals(150.0, p.qty, 0.0)
        assertEquals(165.0 * 1.5, p.kcal, 0.5); assertEquals(31.0 * 1.5, p.protein, 0.5)
    }

    @Test fun caloriesGivenDirectly() {
        val p = meal("makan martabak 400 kalori").items.single()
        assertEquals(400.0, p.kcal, 0.0)
    }

    @Test fun unknownFoodsAreReportedNotInvented() {
        val p = meal("makan nasi dan rendang kambing spesial")
        assertTrue(p.items.any { it.name == "nasi_putih" })
        assertTrue("makanan asing harus dilaporkan: ${p.unknown}", p.unknown.isNotEmpty() || p.items.any { it.name == "rendang" })
        val none = meal("makan sesuatu yang enak sekali")
        assertTrue(none.items.isEmpty())
    }

    @Test fun absurdQuantitiesRejected() {
        val p = meal("makan nasi 500 piring")
        assertTrue(p.items.isEmpty())
    }

    @Test fun mealTypeAndDay() {
        assertEquals(MealType.LUNCH, meal("makan siang ayam goreng").type)
        assertEquals(MealType.DINNER, meal("makan malam ikan bakar").type)
        assertEquals(MealType.SNACK, meal("ngemil pisang").type)
        assertEquals(-1, meal("kemarin makan nasi goreng").dayOffset)
        assertEquals(MealType.BREAKFAST, MealParser.typeByHour(7)); assertEquals(MealType.LUNCH, MealParser.typeByHour(12))
        assertEquals(MealType.DINNER, MealParser.typeByHour(19)); assertEquals(MealType.SNACK, MealParser.typeByHour(23))
    }

    @Test fun splitWords() {
        val p = meal("makan siang ayam goreng, nasi 1 piring, es teh")
        assertEquals(listOf("ayam_goreng", "nasi_putih", "teh_manis"), p.items.map { it.name })
    }

    @Test fun englishMeals() {
        val p = meal("breakfast 2 eggs and toast")
        assertEquals(MealType.BREAKFAST, p.type)
        assertEquals(2, p.items.size)
    }

    @Test fun neverThrows() {
        for (s in listOf("", " ", "makan", "makan " + "nasi ".repeat(500), "0 nasi", "nasi -5", "makan 99999999999 nasi", "ε")) meal(s)
    }

    @Test fun namesInBothLanguages() {
        for (fd in Foods.all) { assertTrue(fd.name(AppLang.ID).isNotBlank()); assertTrue(fd.name(AppLang.EN).isNotBlank()) }
    }
}

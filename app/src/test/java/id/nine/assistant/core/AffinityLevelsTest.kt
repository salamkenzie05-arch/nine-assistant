package id.nine.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tes tangga level kedekatan: 8 level bernama sesuai permintaan, XP dan hari naik teratur. */
class AffinityLevelsTest {

    @Test
    fun delapanLevelBernama() {
        assertEquals(
            listOf("Stranger", "Aware", "Acquainted", "Interested", "Familiar", "Connected", "Loyal", "Devoted"),
            Affinity.LEVELS.map { it.nameEn }
        )
        assertEquals(Affinity.LEVELS.map { it.nameEn }, Affinity.LEVELS.map { it.nameId })
        assertEquals((1..8).toList(), Affinity.LEVELS.map { it.number })
    }

    @Test
    fun xpDanHariNaikTeraturDanSejumlahLevel() {
        assertEquals(Affinity.LEVELS.size, AffinityRules.MIN_DAYS.size)
        for (i in 1 until Affinity.LEVELS.size) {
            assertTrue(Affinity.LEVELS[i].minXp > Affinity.LEVELS[i - 1].minXp)
            assertTrue(AffinityRules.MIN_DAYS[i] > AffinityRules.MIN_DAYS[i - 1])
        }
        assertEquals(0, Affinity.LEVELS.first().minXp)
        assertEquals(AffinityRules.FULL_DAYS, AffinityRules.MIN_DAYS.last())
    }

    @Test
    fun levelMenurutXpDanBatasAtas() {
        assertEquals("Stranger", Affinity.levelFor(0).nameEn)
        assertEquals("Aware", Affinity.levelFor(60).nameEn)
        assertEquals("Aware", Affinity.levelFor(169).nameEn)
        assertEquals("Acquainted", Affinity.levelFor(170).nameEn)
        assertEquals("Devoted", Affinity.levelFor(999_999).nameEn)
        assertEquals(null, Affinity.nextAfter(Affinity.LEVELS.last()))
        assertEquals("Aware", Affinity.nextAfter(Affinity.LEVELS.first())?.nameEn)
    }
}

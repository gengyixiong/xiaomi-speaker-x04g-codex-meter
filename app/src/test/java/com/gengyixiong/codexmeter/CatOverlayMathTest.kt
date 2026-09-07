package com.gengyixiong.codexmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CatOverlayMathTest {
    @Test fun edgeRotationsPointInward() {
        assertEquals(0f, CatEdge.BOTTOM.rotation)
        assertEquals(180f, CatEdge.TOP.rotation)
        assertEquals(90f, CatEdge.LEFT.rotation)
        assertEquals(-90f, CatEdge.RIGHT.rotation)
    }

    @Test fun spawnCountUsesRequestedThresholds() {
        assertEquals(1, CatOverlayMath.spawnCount(69))
        assertEquals(2, CatOverlayMath.spawnCount(70))
        assertEquals(2, CatOverlayMath.spawnCount(94))
        assertEquals(3, CatOverlayMath.spawnCount(95))
    }

    @Test fun boundsReserveSpacingAcrossEdges() {
        val bottom = CatOverlayMath.maximumBounds(CatEdge.BOTTOM, 40f, 800, 480)
        val leftNearBottom = CatOverlayMath.maximumBounds(CatEdge.LEFT, 440f, 800, 480)
        val top = CatOverlayMath.maximumBounds(CatEdge.TOP, 400f, 800, 480)
        assertTrue(bottom.overlaps(leftNearBottom, 10f))
        assertFalse(bottom.overlaps(top, 10f))
    }

    @Test fun lanesKeepCentersAwayFromCorners() {
        assertEquals(74..726, CatOverlayMath.laneRange(800))
        assertEquals(74..406, CatOverlayMath.laneRange(480))
        assertEquals(CatBounds(366f, 420f, 434f, 488f), CatOverlayMath.maximumBounds(CatEdge.BOTTOM, 400f, 800, 480))
    }

    @Test fun selectedCatsAreUniqueAndDoNotStartWithPrevious() {
        CatType.values().forEach { previous ->
            (0..100).forEach { seed ->
                (1..3).forEach { count ->
                    val selected = CatOverlayMath.selectTypes(count, previous, Random(seed))
                    assertEquals(count, selected.size)
                    assertEquals(selected.size, selected.toSet().size)
                    assertFalse(selected.first() == previous)
                    if (count == 3) assertEquals(CatType.values().toSet(), selected.toSet())
                }
            }
        }
    }

    @Test fun firstSelectionMayUseAnyColorWhenThereIsNoPreviousCat() {
        (0..100).forEach { seed ->
            val selected = CatOverlayMath.selectTypes(3, null, Random(seed))
            assertEquals(CatType.values().toSet(), selected.toSet())
        }
    }

    @Test fun blinkPlansAreRelaxedAndIndependent() {
        (0..100).forEach { seed ->
            val plan = CatOverlayMath.blinkPlan(Random(seed))
            assertTrue(plan.windows.size in 1..3)
            assertTrue(plan.visibleMs in 3_000..7_000)
            plan.windows.forEachIndexed { index, window ->
                assertTrue(window.durationMs in 100..160)
                assertTrue(window.startMs >= 650)
                assertTrue(window.startMs + window.durationMs <= plan.visibleMs)
                if (index > 0) assertTrue(window.startMs >= plan.windows[index - 1].startMs + plan.windows[index - 1].durationMs + 500)
            }
        }
    }
}

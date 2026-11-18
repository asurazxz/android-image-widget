package dev.localgallery.domain

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ShuffleReconciliationTest {
    @Test fun refreshPrunesDeletedAndAddsNewWithoutAdvancingOrStartingCycle() {
        val before = ShuffleSnapshot("shown", listOf("pending", "deleted"), setOf("shown", "old"), 7)
        val result = ShuffleEngine.reconcile(setOf("shown", "pending", "new"), before, Random(1))
        assertEquals("shown", result.current)
        assertEquals(7L, result.cycle)
        assertEquals(setOf("shown"), result.seen)
        assertEquals(setOf("pending", "new"), result.remaining.toSet())
        assertEquals(2, result.remaining.size)
    }
    @Test fun unchangedPoolKeepsExactOrderAndExhaustedCycle() {
        val before = ShuffleSnapshot("a", listOf("c", "b"), setOf("a"), 2)
        assertEquals(before, ShuffleEngine.reconcile(setOf("a", "b", "c"), before, Random(8)))
        val exhausted = ShuffleSnapshot("c", emptyList(), setOf("a", "b", "c"), 2)
        assertEquals(exhausted, ShuffleEngine.reconcile(setOf("a", "b", "c"), exhausted))
    }
    @Test fun emptyPoolClearsSelectionAndQueuesButRetainsCycle() {
        assertEquals(ShuffleSnapshot(cycle = 9), ShuffleEngine.reconcile(emptySet(),
            ShuffleSnapshot("a", listOf("b"), setOf("a"), 9)))
    }
}

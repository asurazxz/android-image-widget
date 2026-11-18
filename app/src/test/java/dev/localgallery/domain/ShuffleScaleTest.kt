package dev.localgallery.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Membership and no-repeat invariants across galleries larger than the usual unit fixtures. */
class ShuffleScaleTest {
    @Test
    fun shuffledCycleIsACompletePermutationAtGalleryScales() {
        listOf(1, 2, 100, 5_000).forEach { size ->
            val pool = (0 until size).map { "photo-$it" }.toSet()
            val random = Random(size)
            var state = ShuffleSnapshot()
            val selected = LinkedHashSet<String>()

            repeat(size) { index ->
                val previousCycle = state.cycle
                state = ShuffleEngine.advance(pool, state, random)

                assertTrue("Gallery size $size, position $index selected an unknown key", state.current in pool)
                assertEquals("Gallery size $size must not repeat inside a cycle", index + 1, state.seen.size)
                assertEquals("Gallery size $size must retain every not-yet-selected key", size - index - 1, state.remaining.size)
                assertTrue("Gallery size $size must not repeat a photo before exhaustion", selected.add(state.current!!))
                assertEquals(previousCycle, state.cycle)
            }

            val previous = state.current
            state = ShuffleEngine.advance(pool, state, random)
            assertEquals("Gallery size $size starts a new cycle after exhaustion", 1L, state.cycle)
            assertNotNull(state.current)
            assertEquals(1, state.seen.size)
            assertEquals(size - 1, state.remaining.size)
            if (size > 1) assertFalse("Cycle boundary should avoid immediate repeat", previous == state.current)
        }
    }

    @Test
    fun largePoolReconciliationPreservesPendingKeysAndAddsNewKeysOnce() {
        val originalPool = (0 until 5_000).map { "photo-$it" }.toSet()
        var state = ShuffleSnapshot()
        val random = Random(54)
        repeat(350) { state = ShuffleEngine.advance(originalPool, state, random) }

        val deleted = state.remaining.take(200).toSet()
        val added = (0 until 250).map { "new-$it" }.toSet()
        val updatedPool = originalPool - deleted + added
        val reconciled = ShuffleEngine.reconcile(updatedPool, state, Random(91))
        val pending = reconciled.remaining

        assertEquals("Current selection survives an unrelated gallery update", state.current, reconciled.current)
        assertEquals("Reconciliation does not consume a selection", state.cycle, reconciled.cycle)
        assertEquals("Previously shown surviving media remains shown", state.seen - deleted, reconciled.seen)
        assertEquals("No duplicate pending keys", pending.size, pending.toSet().size)
        assertTrue("Removed keys are pruned", pending.none { it in deleted })
        assertTrue("New keys join the active cycle exactly once", added.all { it in pending })
        assertTrue("Every pending key is eligible and unseen", pending.all { it in updatedPool && it !in reconciled.seen })
        assertEquals(updatedPool.size - reconciled.seen.size, pending.size)
    }
}

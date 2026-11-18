package dev.localgallery.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleEngineTest {
    @Test
    fun seededShuffleDoesNotRepeatWithinCycle() {
        val pool = (1..8).map { "photo-$it" }.toSet()
        var state = ShuffleSnapshot()
        val random = Random(1234)

        repeat(100) {
            val previousCycle = state.cycle
            val previousSeen = state.seen
            state = ShuffleEngine.advance(pool, state, random)
            assertTrue(state.current in pool)
            if (state.cycle == previousCycle) {
                assertFalse("${state.current} repeated in cycle ${state.cycle}", state.current in previousSeen)
            }
        }
    }

    @Test
    fun crossingCycleBoundaryAvoidsImmediateRepeatWhenPoolHasMultipleItems() {
        val pool = setOf("a", "b", "c")
        var state = ShuffleSnapshot()
        val random = Random(99)
        var previous: String? = null

        repeat(90) {
            state = ShuffleEngine.advance(pool, state, random)
            assertTrue(state.current in pool)
            if (previous != null) assertFalse(previous == state.current)
            previous = state.current
        }
    }

    @Test
    fun newlyAddedMediaIsScheduledBeforeExistingCycleRepeats() {
        val oldPool = setOf("a", "b", "c", "d")
        val random = Random(77)
        var state = ShuffleSnapshot()
        repeat(2) { state = ShuffleEngine.advance(oldPool, state, random) }
        val priorSeen = state.seen
        val enlargedPool = oldPool + "new-photo"

        repeat(oldPool.size) {
            state = ShuffleEngine.advance(enlargedPool, state, random)
            if (state.current == "new-photo") return@repeat
        }

        assertTrue("new media should join the active cycle", "new-photo" in state.seen || "new-photo" in state.remaining)
        assertTrue(priorSeen.all { it in enlargedPool })
    }

    @Test
    fun deletedPendingMediaIsPruned() {
        val pool = setOf("a", "b", "c", "d", "e")
        var state = ShuffleSnapshot()
        val random = Random(4)
        state = ShuffleEngine.advance(pool, state, random)
        val removed = state.remaining.first()

        state = ShuffleEngine.advance(pool - removed, state, random)

        assertFalse(removed in state.remaining)
        assertTrue(state.current in pool - removed)
    }

    @Test
    fun emptyPoolClearsCurrentSafely() {
        val result = ShuffleEngine.advance(emptySet(), ShuffleSnapshot(current = "gone", remaining = listOf("gone"), seen = setOf("gone")), Random(1))

        assertNull(result.current)
        assertTrue(result.remaining.isEmpty())
        assertTrue(result.seen.isEmpty())
    }

    @Test
    fun onePhotoPoolKeepsReturningThatPhoto() {
        val random = Random(3)
        var state = ShuffleSnapshot()

        repeat(5) {
            state = ShuffleEngine.advance(setOf("only"), state, random)
            assertEquals("only", state.current)
        }
    }

    @Test
    fun restoredSnapshotContinuesPendingOrderIndependentOfRandomInstance() {
        val pool = (1..7).map { "photo-$it" }.toSet()
        val initial = ShuffleEngine.advance(pool, ShuffleSnapshot(), Random(781))
        val restoredCopy = initial.copy(remaining = initial.remaining.toList(), seen = initial.seen.toSet())
        val nextUsingOriginal = ShuffleEngine.advance(pool, initial, Random(5))
        val nextUsingRestored = ShuffleEngine.advance(pool, restoredCopy, Random(987654))

        assertEquals(nextUsingOriginal.current, nextUsingRestored.current)
        assertEquals(nextUsingOriginal.remaining, nextUsingRestored.remaining)
        assertEquals(nextUsingOriginal.seen, nextUsingRestored.seen)
        assertEquals(nextUsingOriginal.cycle, nextUsingRestored.cycle)
    }
}

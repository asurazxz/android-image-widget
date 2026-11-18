package dev.localgallery.domain

import kotlin.random.Random

/** Persistable state for one random-playback sequence. */
data class ShuffleSnapshot(
    val current: String? = null,
    val remaining: List<String> = emptyList(),
    val seen: Set<String> = emptySet(),
    val cycle: Long = 0,
)

/** Pure state transition for selecting the next item from a changing media pool. */
object ShuffleEngine {
    /** Refresh membership without consuming a photo or starting another cycle. */
    fun reconcile(
        pool: Set<String>,
        state: ShuffleSnapshot,
        random: Random = Random.Default,
    ): ShuffleSnapshot {
        val seen = state.seen.intersect(pool)
        val remaining = state.remaining.filter { it in pool && it !in seen }.distinct().toMutableList()
        val pending = remaining.toSet()
        pool.filter { it !in seen && it !in pending }.shuffled(random).forEach {
            remaining.add(random.nextInt(remaining.size + 1), it)
        }
        return state.copy(current = state.current?.takeIf { it in pool }, remaining = remaining, seen = seen)
    }

    fun advance(
        pool: Set<String>,
        state: ShuffleSnapshot,
        random: Random = Random.Default,
    ): ShuffleSnapshot {
        if (pool.isEmpty()) {
            return state.copy(current = null, remaining = emptyList(), seen = emptySet())
        }

        val available = pool.toSet()
        val cleanSeen = state.seen.intersect(available).toMutableSet()
        val cleanRemaining = state.remaining
            .asSequence()
            .filter { it in available && it !in cleanSeen }
            .distinct()
            .toMutableList()

        // New items join this cycle immediately, in a random slot among the pending items.
        val pending = cleanRemaining.toMutableSet()
        val newlyAvailable = available.filter { it !in cleanSeen && it !in pending }.shuffled(random)
        newlyAvailable.forEach { item ->
            cleanRemaining.add(random.nextInt(cleanRemaining.size + 1), item)
        }

        var nextCycle = state.cycle
        if (cleanRemaining.isEmpty()) {
            val unseen = available.filter { it !in cleanSeen }
            val cycleItems: List<String>
            if (unseen.isEmpty()) {
                // Every item was shown: begin a fresh cycle.
                nextCycle += 1
                cleanSeen.clear()
                cycleItems = available.shuffled(random).toMutableList()
            } else {
                cycleItems = unseen.shuffled(random).toMutableList()
            }

            // Avoid the previous cycle's last item becoming this cycle's first when possible.
            if (cycleItems.size > 1 && cycleItems.first() == state.current) {
                val replacementIndex = cycleItems.indexOfFirst { it != state.current }
                if (replacementIndex > 0) {
                    val first = cycleItems[0]
                    cycleItems[0] = cycleItems[replacementIndex]
                    cycleItems[replacementIndex] = first
                }
            }
            cleanRemaining.addAll(cycleItems)
        }

        val selected = cleanRemaining.removeAt(0)
        cleanSeen.add(selected)
        return ShuffleSnapshot(
            current = selected,
            remaining = cleanRemaining.toList(),
            seen = cleanSeen.toSet(),
            cycle = nextCycle,
        )
    }
}

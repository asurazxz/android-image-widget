package dev.localgallery.rotation

import dev.localgallery.domain.RotationPreference

/** Pure timing rules shared by the scheduler and automatic-rotation worker. */
object RotationPolicy {
    fun isValidInterval(minutes: Long): Boolean = RotationPreference.fromMinutes(minutes) != null

    fun nextDueAfterRun(now: Long, minutes: Long): Long {
        require(minutes > 0L && isValidInterval(minutes)) { "Automatic interval must be valid and positive" }
        val intervalMillis = minutes * MILLIS_PER_MINUTE
        return if (now > Long.MAX_VALUE - intervalMillis) Long.MAX_VALUE else now + intervalMillis
    }

    fun isDue(nextDueAt: Long?, now: Long): Boolean = nextDueAt != null && nextDueAt <= now

    private const val MILLIS_PER_MINUTE = 60_000L
}

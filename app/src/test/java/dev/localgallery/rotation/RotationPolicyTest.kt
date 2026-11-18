package dev.localgallery.rotation

import dev.localgallery.domain.RotationPreference
import dev.localgallery.data.WidgetRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationPolicyTest {
    @Test fun newWidgetDefaultsToDailyRotation() {
        assertEquals(1_440L, WidgetRecord(1).rotationMinutes)
    }
    @Test fun acceptsManualPresetsAndWholeHourIntervalsThroughOneWeek() {
        listOf(0L, 15L, 30L, 60L, 1_440L, 10_080L).forEach {
            assertTrue("Expected valid interval $it", RotationPolicy.isValidInterval(it))
        }
    }

    @Test fun rejectsIntervalsOutsideAllowedSet() {
        listOf(-1L, 1L, 14L, 16L, 59L, 61L, 10_081L, Long.MAX_VALUE).forEach {
            assertFalse("Expected invalid interval $it", RotationPolicy.isValidInterval(it))
        }
    }

    @Test fun preferencePresetsMapToPolicyIntervals() {
        listOf(
            RotationPreference.Manual, RotationPreference.FifteenMinutes,
            RotationPreference.ThirtyMinutes, RotationPreference.Daily,
            RotationPreference.CustomHours(1), RotationPreference.CustomHours(168),
        ).forEach { assertTrue(RotationPolicy.isValidInterval(it.minutes)) }
    }

    @Test fun computesDueTimeAndSaturatesOnOverflow() {
        assertEquals(1_000L + 15L * 60_000L, RotationPolicy.nextDueAfterRun(1_000L, 15L))
        assertEquals(Long.MAX_VALUE, RotationPolicy.nextDueAfterRun(Long.MAX_VALUE - 1, 15L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun dueTimeRequiresValidPositiveInterval() {
        RotationPolicy.nextDueAfterRun(1_000L, 0L)
    }

    @Test fun dueCheckHandlesMissingEarlyExactAndDelayedTimes() {
        assertFalse(RotationPolicy.isDue(null, 1_000L))
        assertFalse(RotationPolicy.isDue(1_001L, 1_000L))
        assertTrue(RotationPolicy.isDue(1_000L, 1_000L))
        assertTrue(RotationPolicy.isDue(999L, 1_000L))
    }
}

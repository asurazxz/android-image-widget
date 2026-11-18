package dev.localgallery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationPreferenceTest {
    @Test fun parsesEveryPresetAndCustomHourBoundary() {
        assertEquals(RotationPreference.Manual, RotationPreference.fromMinutes(0))
        assertEquals(RotationPreference.FifteenMinutes, RotationPreference.fromMinutes(15))
        assertEquals(RotationPreference.ThirtyMinutes, RotationPreference.fromMinutes(30))
        assertEquals(RotationPreference.Daily, RotationPreference.fromMinutes(1_440))
        assertEquals(RotationPreference.CustomHours(1), RotationPreference.fromMinutes(60))
        assertEquals(RotationPreference.CustomHours(168), RotationPreference.fromMinutes(10_080))
        assertEquals(60L, RotationPreference.CustomHours(1).minutes)
        assertEquals(10_080L, RotationPreference.CustomHours(168).minutes)
    }

    @Test fun rejectsInvalidPersistedIntervalsAndCustomInput() {
        listOf(-1L, 1L, 59L, 61L, 10_081L, Long.MAX_VALUE).forEach {
            assertNull("Invalid interval $it", RotationPreference.fromMinutes(it))
        }
        listOf("", "0", "169", "1.5", "-1", "999999999999999999999").forEach {
            assertNull("Invalid custom hour input '$it'", RotationPreference.parseCustomHours(it))
        }
        assertEquals(1, RotationPreference.parseCustomHours("1"))
        assertEquals(168, RotationPreference.parseCustomHours("168"))
    }
}

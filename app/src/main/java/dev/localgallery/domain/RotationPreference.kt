package dev.localgallery.domain

/** UI representation of the persisted M4/M5 rotation interval (minutes). */
sealed interface RotationPreference {
    data object Manual : RotationPreference
    data object FifteenMinutes : RotationPreference
    data object ThirtyMinutes : RotationPreference
    data object Daily : RotationPreference
    data class CustomHours(val hours: Int) : RotationPreference

    val minutes: Long
        get() = when (this) {
            Manual -> 0L
            FifteenMinutes -> 15L
            ThirtyMinutes -> 30L
            Daily -> 1_440L
            is CustomHours -> hours * 60L
        }

    companion object {
        fun fromMinutes(minutes: Long): RotationPreference? = when (minutes) {
            0L -> Manual
            15L -> FifteenMinutes
            30L -> ThirtyMinutes
            1_440L -> Daily
            else -> if (minutes in 60L..10_080L && minutes % 60L == 0L)
                CustomHours((minutes / 60).toInt()) else null
        }

        fun parseCustomHours(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..168 }
    }
}

package dev.localgallery.widget

import dev.localgallery.data.DEFAULT_FIT_BACKGROUND_COLOR

internal enum class FitBackgroundColors(val argb: Int, val label: String) {
    BLACK(0xFF000000.toInt(), "Black"),
    CHARCOAL(DEFAULT_FIT_BACKGROUND_COLOR, "Charcoal"),
    WHITE(0xFFFFFFFF.toInt(), "White"),
    CREAM(0xFFF5E8CF.toInt(), "Cream"),
    NAVY(0xFF17253A.toInt(), "Navy"),
    FOREST(0xFF19362B.toInt(), "Forest");

    companion object {
        fun fromArgb(argb: Int): FitBackgroundColors = entries.firstOrNull { it.argb == argb } ?: CHARCOAL
    }
}

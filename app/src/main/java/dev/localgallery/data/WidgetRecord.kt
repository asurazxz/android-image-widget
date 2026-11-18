package dev.localgallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray

@Entity(tableName = "widgets")
data class WidgetRecord(
    @PrimaryKey val appWidgetId: Int,
    val entireGallery: Boolean = true,
    val selectedAlbums: List<String> = emptyList(),
    val displayMode: String = "CROP",
    val rotationMinutes: Long = 1_440,
    val preferredViewer: String? = null,
    val currentUri: String? = null,
    val currentMime: String? = null,
    val remaining: List<String> = emptyList(),
    val seen: List<String> = emptyList(),
    val cycle: Long = 0,
    val revision: Long = 0,
    val nextDueAt: Long? = null,
    val lastAutomaticAt: Long? = null,
)

class WidgetConverters {
    @TypeConverter
    fun stringListToJson(value: List<String>): String = JSONArray().apply {
        value.forEach { put(it) }
    }.toString()

    @TypeConverter
    fun jsonToStringList(value: String): List<String> {
        val json = JSONArray(value)
        return List(json.length()) { index -> json.getString(index) }
    }
}

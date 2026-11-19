package dev.localgallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetDao {
    @Query("SELECT * FROM widgets WHERE appWidgetId = :id LIMIT 1")
    suspend fun get(id: Int): WidgetRecord?

    @Query("SELECT revision FROM widgets WHERE appWidgetId = :id")
    fun observeRevision(id: Int): Flow<Long?>

    @Query("SELECT * FROM widgets ORDER BY appWidgetId")
    suspend fun all(): List<WidgetRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: WidgetRecord)

    @Query("DELETE FROM widgets WHERE appWidgetId = :id")
    suspend fun delete(id: Int)

    @Query("SELECT * FROM widgets ORDER BY appWidgetId")
    fun observeAll(): Flow<List<WidgetRecord>>
}

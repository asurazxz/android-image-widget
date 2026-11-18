package dev.localgallery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [WidgetRecord::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(WidgetConverters::class)
abstract class WidgetDatabase : RoomDatabase() {
    abstract fun widgets(): WidgetDao

    companion object {
        private const val DATABASE_NAME = "local-gallery.db"

        @Volatile
        private var instance: WidgetDatabase? = null

        fun get(context: Context): WidgetDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WidgetDatabase::class.java,
                DATABASE_NAME,
            ).build().also { instance = it }
        }
    }
}

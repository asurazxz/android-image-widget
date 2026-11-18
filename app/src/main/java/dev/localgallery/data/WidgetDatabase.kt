package dev.localgallery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WidgetRecord::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(WidgetConverters::class)
abstract class WidgetDatabase : RoomDatabase() {
    abstract fun widgets(): WidgetDao

    companion object {
        private const val DATABASE_NAME = "local-gallery.db"
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE widgets ADD COLUMN fitBackgroundColor INTEGER NOT NULL DEFAULT $DEFAULT_FIT_BACKGROUND_COLOR")
            }
        }

        @Volatile
        private var instance: WidgetDatabase? = null

        fun get(context: Context): WidgetDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WidgetDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

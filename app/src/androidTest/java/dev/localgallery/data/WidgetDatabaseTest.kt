package dev.localgallery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetDatabaseTest {
    @Test
    fun migratingVersionOnePreservesTheWidgetAndUsesTheOriginalFitColor() = runBlocking {
        val context = appContext()
        val name = "widget-v1-migration-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old: SQLiteDatabase ->
                old.execSQL("""
                    CREATE TABLE widgets (appWidgetId INTEGER NOT NULL PRIMARY KEY,
                    entireGallery INTEGER NOT NULL, selectedAlbums TEXT NOT NULL, displayMode TEXT NOT NULL,
                    rotationMinutes INTEGER NOT NULL, preferredViewer TEXT, currentUri TEXT, currentMime TEXT,
                    remaining TEXT NOT NULL, seen TEXT NOT NULL, cycle INTEGER NOT NULL, revision INTEGER NOT NULL,
                    nextDueAt INTEGER, lastAutomaticAt INTEGER)
                """.trimIndent())
                old.execSQL("""
                    INSERT INTO widgets VALUES (41, 0, '["primary:42"]', 'FIT', 30, 'example.viewer',
                    'content://photo/1', 'image/jpeg', '["content://photo/2"]', '["content://photo/1"]', 3, 12, 1900000000000, 1899999970000)
                """.trimIndent())
                old.version = 1
            }
            val database = open(context, name)
            try {
                assertEquals(2, database.openHelper.writableDatabase.version)
                assertEquals(
                    WidgetRecord(41, entireGallery = false, selectedAlbums = listOf("primary:42"), displayMode = "FIT",
                        rotationMinutes = 30, preferredViewer = "example.viewer", currentUri = "content://photo/1",
                        currentMime = "image/jpeg", remaining = listOf("content://photo/2"), seen = listOf("content://photo/1"),
                        cycle = 3, revision = 12, nextDueAt = 1_900_000_000_000, lastAutomaticAt = 1_899_999_970_000),
                    database.widgets().get(41),
                )
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun widgetConfigurationAndShuffleStateSurviveCloseAndReopen() = runBlocking {
        val context = appContext()
        val name = "widget-persistence-test.db"
        context.deleteDatabase(name)

        val expected = WidgetRecord(
            appWidgetId = 41,
            entireGallery = false,
            selectedAlbums = listOf("primary:42", "sdcard:42"),
            displayMode = "FIT",
            rotationMinutes = 30,
            preferredViewer = "example.viewer",
            currentUri = "content://media/primary/images/media/7",
            currentMime = "image/jpeg",
            remaining = listOf("content://media/primary/images/media/8"),
            seen = listOf("content://media/primary/images/media/7"),
            cycle = 3,
            revision = 12,
            nextDueAt = 1_900_000_000_000,
            lastAutomaticAt = 1_899_999_970_000,
            fitBackgroundColor = 0xFFF5E8CF.toInt(),
        )

        try {
            val firstOpen = open(context, name)
            try {
                val database = firstOpen
                database.widgets().put(expected)
            } finally {
                firstOpen.close()
            }

            val secondOpen = open(context, name)
            try {
                val database = secondOpen
                assertEquals(expected, database.widgets().get(expected.appWidgetId))
            } finally {
                secondOpen.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletingOneWidgetPreservesOtherWidgetRows() = runBlocking {
        val context = appContext()
        val name = "widget-delete-test.db"
        context.deleteDatabase(name)

        try {
            val database = open(context, name)
            try {
                val dao = database.widgets()
                dao.put(WidgetRecord(appWidgetId = 1, currentUri = "content://photo/1"))
                dao.put(WidgetRecord(appWidgetId = 2, currentUri = "content://photo/2"))
                dao.put(WidgetRecord(appWidgetId = 3, currentUri = "content://photo/3"))

                assertNotNull(dao.get(1))
                dao.delete(2)

                assertEquals(setOf(1, 3), dao.all().map { it.appWidgetId }.toSet())
                assertEquals("content://photo/1", dao.get(1)?.currentUri)
                assertEquals("content://photo/3", dao.get(3)?.currentUri)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(context: Context, name: String): WidgetDatabase =
        Room.databaseBuilder(context, WidgetDatabase::class.java, name)
            .addMigrations(WidgetDatabase.MIGRATION_1_2).build()
}

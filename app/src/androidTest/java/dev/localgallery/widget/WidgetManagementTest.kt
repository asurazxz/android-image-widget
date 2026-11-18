package dev.localgallery.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.MediaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** M4 controller regressions using real MediaStore rows and framework-bound widget IDs. */
class WidgetManagementTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test
    fun threeBoundWidgetsKeepSourcesPreferencesQueuesAndCachesIndependent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val controller = WidgetController(context)
        val dao = WidgetDatabase.get(context).widgets()
        val ids = mutableListOf<Int>()
        val fixtures = mutableListOf<Uri>()
        var deletedByFramework: Int? = null
        try {
            val albumPaths = (0..2).map { "Pictures/WidgetManagement-${System.currentTimeMillis()}-$it" }
            albumPaths.forEachIndexed { album, path ->
                repeat(3) { index -> fixtures += addPhoto(context, path, "album-$album-$index") }
            }
            val allImages = MediaRepository(context).images()
            val keysByPath = albumPaths.map { path ->
                // MediaStore bucket IDs are the stable source identifiers; select each fixture's bucket.
                val target = allImages.first { image -> image.uri in fixtures &&
                    image.uri.lastPathSegment?.let { segment ->
                        val row = context.contentResolver.query(image.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                        row?.use { it.moveToFirst(); it.getString(0).trimEnd('/') == path } ?: false
                    } == true }
                "${target.volume}:${target.bucketId}"
            }
            assertEquals(3, keysByPath.toSet().size)

            repeat(3) {
                val id = host.allocateAppWidgetId()
                val bound = manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, PhotoWidgetReceiver::class.java))
                assertTrue("Test host must bind widget $id", bound)
                ids += id
            }
            val initial = ids.mapIndexed { index, id ->
                val configured = controller.configure(WidgetRecord(
                    appWidgetId = id, entireGallery = false, selectedAlbums = listOf(keysByPath[index]),
                    displayMode = if (index == 1) "FIT" else "CROP",
                    preferredViewer = "test.viewer.$index", rotationMinutes = (index + 1) * 60L,
                ))
                assertTrue("Widget $id should configure from its own album", configured)
                requireNotNull(dao.get(id))
            }
            initial.forEachIndexed { index, record ->
                assertEquals(listOf(keysByPath[index]), record.selectedAlbums)
                assertTrue(fixturesInAlbum(context, record.currentUri!!, keysByPath[index]))
                assertEquals(2, record.remaining.size)
                assertEquals(if (index == 1) "FIT" else "CROP", record.displayMode)
                assertEquals("test.viewer.$index", record.preferredViewer)
                assertEquals((index + 1) * 60L, record.rotationMinutes)
                assertEquals("Each widget should own exactly one preview", 1, previewFiles(context, record.appWidgetId).size)
            }
            assertEquals(3, ids.map { dao.get(it)!!.currentUri }.toSet().size)

            // Advance one record to establish non-empty, widget-local shuffle history.
            val otherCachesBeforeEdit = ids.drop(1).associateWith { id ->
                previewFiles(context, id).map { it.name to it.length() }
            }
            controller.advance(ids[0])
            val beforeEdit = requireNotNull(dao.get(ids[0]))
            val untouchedBefore = ids.drop(1).map { dao.get(it) }
            assertTrue(controller.configure(beforeEdit.copy(displayMode = "FIT", preferredViewer = "edited.viewer")))
            val afterHealthyEdit = requireNotNull(dao.get(ids[0]))
            assertEquals("An unrelated edit preserves a healthy current photo", beforeEdit.currentUri, afterHealthyEdit.currentUri)
            assertEquals(beforeEdit.remaining, afterHealthyEdit.remaining)
            assertEquals(beforeEdit.seen, afterHealthyEdit.seen)
            assertEquals(beforeEdit.cycle, afterHealthyEdit.cycle)
            assertEquals(untouchedBefore, ids.drop(1).map { dao.get(it) })
            assertEquals(otherCachesBeforeEdit, ids.drop(1).associateWith { id ->
                previewFiles(context, id).map { it.name to it.length() }
            })

            val deletedCurrent = Uri.parse(afterHealthyEdit.currentUri!!)
            assertTrue("Fixture current image should be removable",
                context.contentResolver.delete(deletedCurrent, null, null) > 0)
            fixtures.remove(deletedCurrent)
            assertTrue(controller.configure(afterHealthyEdit.copy(displayMode = "CROP")))
            val afterEdit = requireNotNull(dao.get(ids[0]))
            val pendingBeforeEdit = afterHealthyEdit.remaining.single()
            assertEquals("An unrelated edit should continue at the one pending photo", pendingBeforeEdit, afterEdit.currentUri)
            assertTrue("Deleted current should be pruned while surviving history stays seen",
                afterEdit.seen.toSet() == (afterHealthyEdit.seen.toSet() - deletedCurrent.toString() + pendingBeforeEdit))
            assertTrue(afterEdit.remaining.isEmpty())
            assertEquals(afterHealthyEdit.cycle, afterEdit.cycle)
            assertEquals(untouchedBefore, ids.drop(1).map { dao.get(it) })
            assertEquals(otherCachesBeforeEdit, ids.drop(1).associateWith { id ->
                previewFiles(context, id).map { it.name to it.length() }
            })

            val invalid = afterEdit.copy(entireGallery = false, selectedAlbums = emptyList())
            assertFalse("An empty source must not save", controller.configure(invalid))
            assertEquals(afterEdit, dao.get(ids[0]))
            assertFalse("A missing album must not save", controller.configure(
                afterEdit.copy(selectedAlbums = listOf("primary:missing-test-album")),
            ))
            assertEquals(afterEdit, dao.get(ids[0]))
            assertFalse("An invalid rotation interval must not save", controller.configure(
                afterEdit.copy(rotationMinutes = 17),
            ))
            assertEquals(afterEdit, dao.get(ids[0]))
            assertTrue("Manual rotation is a valid preference", controller.configure(afterEdit.copy(rotationMinutes = 0)))
            val manualRotation = requireNotNull(dao.get(ids[0]))
            assertEquals(0L, manualRotation.rotationMinutes)
            assertEquals(afterEdit.currentUri, manualRotation.currentUri)
            assertEquals(afterEdit.remaining, manualRotation.remaining)

            // Changing only widget 0's source resets only its shuffle to album 1.
            val beforeSourceChange = ids.map { dao.get(it) }
            assertTrue(controller.configure(manualRotation.copy(selectedAlbums = listOf(keysByPath[1]))))
            val changed = requireNotNull(dao.get(ids[0]))
            assertTrue(fixturesInAlbum(context, changed.currentUri!!, keysByPath[1]))
            assertTrue(changed.remaining.all { fixturesInAlbum(context, it, keysByPath[1]) })
            assertEquals("A changed source starts a fresh cycle", 0L, changed.cycle)
            assertEquals(listOf(changed.currentUri), changed.seen)
            assertEquals(beforeSourceChange[1], dao.get(ids[1]))
            assertEquals(beforeSourceChange[2], dao.get(ids[2]))

            // Additions are admitted to the active cycle; deleting the displayed media selects a survivor.
            val added = addPhoto(context, albumPaths[1], "album-1-new")
            fixtures += added
            val addedKey = MediaRepository(context).images().first { it.uri == added }.key
            val beforeAdditionRefresh = requireNotNull(dao.get(ids[0]))
            controller.refresh(ids[0])
            val afterAddition = requireNotNull(dao.get(ids[0]))
            assertEquals(beforeAdditionRefresh.currentUri, afterAddition.currentUri)
            assertEquals(beforeAdditionRefresh.seen, afterAddition.seen)
            assertEquals(beforeAdditionRefresh.cycle, afterAddition.cycle)
            assertTrue("New media should join the active shuffle cycle",
                addedKey in (afterAddition.remaining + afterAddition.seen + listOfNotNull(afterAddition.currentUri)))
            val removed = Uri.parse(afterAddition.currentUri!!)
            context.contentResolver.delete(removed, null, null)
            fixtures.remove(removed)
            controller.refresh(ids[0])
            val afterRemoval = requireNotNull(dao.get(ids[0]))
            assertNotEquals("Deleted media cannot remain selected", removed.toString(), afterRemoval.currentUri)
            assertTrue(fixturesInAlbum(context, afterRemoval.currentUri!!, keysByPath[1]))
            assertFalse(removed.toString() in afterRemoval.remaining)
            assertFalse(removed.toString() in afterRemoval.seen)

            // A source that becomes empty after selection must reconcile to a placeholder and clear its cache.
            val beforeMissingSource = requireNotNull(dao.get(ids[0]))
            assertTrue(controller.configure(beforeMissingSource.copy(selectedAlbums = listOf(keysByPath[0]))))
            val currentMedia = MediaRepository(context).images()
            val albumZeroUris = fixtures.filter { uri -> currentMedia.any {
                it.uri == uri && "${it.volume}:${it.bucketId}" == keysByPath[0]
            } }
            albumZeroUris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            fixtures.removeAll(albumZeroUris.toSet())
            controller.refresh(ids[0])
            val emptySource = requireNotNull(dao.get(ids[0]))
            assertNull("No eligible media should clear the current image", emptySource.currentUri)
            assertTrue(emptySource.remaining.isEmpty())
            assertTrue(emptySource.seen.isEmpty())
            assertTrue(previewFiles(context, ids[0]).isEmpty())

            val staleConfigureId = Int.MAX_VALUE - 18
            assertFalse("Stale/unbound IDs must not configure", controller.configure(
                WidgetRecord(staleConfigureId, selectedAlbums = listOf(keysByPath[1])),
            ))
            assertNull(dao.get(staleConfigureId))
            assertEquals(emptySource, dao.get(ids[0]))

            // Exercise the actual Glance deletion hook through framework widget removal.
            deletedByFramework = ids[2]
            host.deleteAppWidgetId(deletedByFramework!!)
            awaitCondition { dao.get(deletedByFramework!!) == null && previewFiles(context, deletedByFramework!!).isEmpty() }
            ids.remove(deletedByFramework)

            // A stale persisted ID is defensively discarded by a controller action.
            val stale = Int.MAX_VALUE - 17
            dao.put(WidgetRecord(stale, currentUri = "content://stale/photo"))
            context.cacheDir.resolve("widget-previews").mkdirs()
            context.cacheDir.resolve("widget-previews/$stale-preview.png").writeBytes(byteArrayOf(1))
            controller.advance(stale)
            assertNull("Stale/unbound IDs must not retain database state", dao.get(stale))
            assertTrue(previewFiles(context, stale).isEmpty())
            assertNotNull(dao.get(ids[0]))
            assertNotNull(dao.get(ids[1]))
        } finally {
            ids.forEach { id ->
                runCatching { controller.delete(id) }
                runCatching { host.deleteAppWidgetId(id) }
            }
            if (deletedByFramework != null) runCatching { controller.delete(deletedByFramework!!) }
            fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun addPhoto(context: android.content.Context, path: String, name: String): Uri {
        val resolver = context.contentResolver
        // Use the same volume-specific collection that MediaRepository queries so fixture Uris
        // compare directly with its canonical media keys on Android 10+.
        val primaryImages = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(primaryImages, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(0xFF426754.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun fixturesInAlbum(context: android.content.Context, key: String, expectedAlbum: String): Boolean {
        val media = runBlocking { MediaRepository(context).images().firstOrNull { it.key == key } } ?: return false
        return "${media.volume}:${media.bucketId}" == expectedAlbum
    }

    private fun previewFiles(context: android.content.Context, id: Int) =
        context.cacheDir.resolve("widget-previews").listFiles().orEmpty().filter { it.name.startsWith("$id-") }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(100)
    }

    private companion object { const val HOST_ID = 8654 }
}

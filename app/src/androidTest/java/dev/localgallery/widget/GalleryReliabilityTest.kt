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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Reliability checks against MediaStore rows whose indexed content cannot be decoded. */
class GalleryReliabilityTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test
    fun refreshSkipsCorruptCurrentImageAndSelectsDecodableAlbumSurvivor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        val fixtures = mutableListOf<Uri>()
        val controller = WidgetController(context)
        val dao = WidgetDatabase.get(context).widgets()
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, PhotoWidgetReceiver::class.java)))
            val path = "Pictures/GalleryReliability-${System.currentTimeMillis()}"
            val corrupt = addPhoto(context, path, "will-become-corrupt").also { fixtures += it }
            val original = MediaRepository(context).images().single { it.uri == corrupt }
            val album = "${original.volume}:${original.bucketId}"
            assertTrue(controller.configure(WidgetRecord(id, entireGallery = false, selectedAlbums = listOf(album))))
            assertEquals(original.key, requireNotNull(dao.get(id)).currentUri)
            val valid = addPhoto(context, path, "valid").also { fixtures += it }
            val survivor = MediaRepository(context).images().single { it.uri == valid }
            assertEquals(album, "${survivor.volume}:${survivor.bucketId}")
            // Corrupt the existing selection in MediaStore, then invalidate its cached preview.
            // A concurrent Glance refresh can recover first, using the same controller path.
            context.contentResolver.openOutputStream(corrupt, "rwt")!!.use { it.write("not a PNG image".toByteArray()) }
            WidgetImageRenderer(context).clear(id)
            controller.refresh(id)

            val recovered = requireNotNull(dao.get(id))
            assertEquals("Corrupt item should fall back to the surviving decodable photo", survivor.key, recovered.currentUri)
            assertTrue("A decodable image should have a cached widget preview", previewFiles(context, id).isNotEmpty())
            // Indexed items can re-enter the queue so a repaired file can be retried. Until it is
            // decodable again it must never be selected as the visible frame.
            repeat(2) {
                controller.advance(id)
                assertEquals(survivor.key, requireNotNull(dao.get(id)).currentUri)
            }
        } finally {
            runCatching { controller.delete(id) }
            runCatching { host.deleteAppWidgetId(id) }
            fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    @Test
    fun movingCurrentPhotoReconcilesAlbumWidgetButPreservesWholeGalleryWidget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

        val host = AppWidgetHost(context, REINDEX_HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val ids = mutableListOf<Int>()
        val fixtures = mutableListOf<Uri>()
        val controller = WidgetController(context)
        val dao = WidgetDatabase.get(context).widgets()
        try {
            repeat(2) {
                val id = host.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, PhotoWidgetReceiver::class.java)))
                ids += id
            }
            val stamp = System.currentTimeMillis()
            val sourcePath = "Pictures/GalleryReindex-$stamp-source"
            val destinationPath = "Pictures/GalleryReindex-$stamp-destination"
            val moved = addPhoto(context, sourcePath, "moving").also { fixtures += it }
            val survivor = addPhoto(context, sourcePath, "source-survivor").also { fixtures += it }
            addPhoto(context, "Pictures/GalleryReindex-$stamp-other", "other").also { fixtures += it }

            val initialImages = MediaRepository(context).images()
            val movedImage = initialImages.single { it.uri == moved }
            val survivorImage = initialImages.single { it.uri == survivor }
            val sourceAlbum = "${movedImage.volume}:${movedImage.bucketId}"
            assertEquals(sourceAlbum, "${survivorImage.volume}:${survivorImage.bucketId}")

            val albumId = ids[0]
            val wholeGalleryId = ids[1]
            assertTrue(controller.configure(WidgetRecord(
                albumId, entireGallery = false, selectedAlbums = listOf(sourceAlbum),
            )))
            assertTrue(controller.configure(WidgetRecord(wholeGalleryId, entireGallery = true)))

            // Select the same photo through the serialized widget entry point. Direct DAO edits
            // can race the framework's initial Glance render after a successful configuration.
            suspend fun selectMovedPhoto(id: Int, poolSize: Int): WidgetRecord {
                repeat(poolSize) {
                    val record = requireNotNull(dao.get(id))
                    if (record.currentUri == movedImage.key) return record
                    controller.advance(id)
                }
                return requireNotNull(dao.get(id)).also { assertEquals(movedImage.key, it.currentUri) }
            }
            selectMovedPhoto(albumId, 2)
            val wholeBeforeMove = selectMovedPhoto(wholeGalleryId, initialImages.size)

            val updated = context.contentResolver.update(moved, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$destinationPath/")
            }, null, null)
            assertEquals("Moving an app-owned fixture should update exactly one MediaStore row", 1, updated)

            val movedAfter = MediaRepository(context).images().single { it.uri == moved }
            val destinationAlbum = "${movedAfter.volume}:${movedAfter.bucketId}"
            assertNotEquals("The real MediaStore move should change bucket membership", sourceAlbum, destinationAlbum)
            assertEquals("A move preserves this MediaStore row's original key", movedImage.key, movedAfter.key)

            controller.refresh(albumId)
            val albumAfter = requireNotNull(dao.get(albumId))
            assertEquals("Selected album falls back to its remaining source photo", survivorImage.key, albumAfter.currentUri)
            assertFalse("Moved image must not remain pending in selected album", movedImage.key in albumAfter.remaining)
            assertFalse("Moved image must not remain seen in selected album", movedImage.key in albumAfter.seen)
            assertTrue("Fallback must remain in the configured source bucket",
                MediaRepository(context).images().single { it.key == albumAfter.currentUri }.let {
                    "${it.volume}:${it.bucketId}" == sourceAlbum
                })

            controller.refresh(wholeGalleryId)
            val wholeAfter = requireNotNull(dao.get(wholeGalleryId))
            assertEquals("Whole-gallery widget keeps the moved photo selected", movedImage.key, wholeAfter.currentUri)
            assertEquals("Moving one item does not alter the other widget's queue or timing", wholeBeforeMove, wholeAfter)
        } finally {
            ids.forEach { id ->
                runCatching { controller.delete(id) }
                runCatching { host.deleteAppWidgetId(id) }
            }
            fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    private fun addPhoto(context: android.content.Context, path: String, name: String): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFF426754.toInt())
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                } finally { bitmap.recycle() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun previewFiles(context: android.content.Context, id: Int) =
        context.cacheDir.resolve("widget-previews").listFiles().orEmpty().filter { it.name.startsWith("$id-") }

    private companion object { const val HOST_ID = 8658; const val REINDEX_HOST_ID = 8659 }
}

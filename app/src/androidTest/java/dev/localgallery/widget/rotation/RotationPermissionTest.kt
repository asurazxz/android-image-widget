package dev.localgallery.widget.rotation

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.MediaRepository
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.widget.PhotoWidgetReceiver
import dev.localgallery.widget.WidgetController
import dev.localgallery.widget.loadWidgetFrame
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Permission transitions are set outside instrumentation; this test only observes and reconciles them. */
class RotationPermissionTest {
    @Test fun permissionLossCancelsAndFullRestorationStartsANewInterval() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("m5PermissionPhase")
        assumeTrue("Pass -e m5PermissionPhase seed, blocked, or restore", phase in setOf("seed", "blocked", "restore"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val file = context.filesDir.resolve(STATE_FILE)
        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val controller = WidgetController(context)
        val dao = WidgetDatabase.get(context).widgets()
        val work = WorkManager.getInstance(context)
        val access = PhotoPermissionManager(context).status()

        if (phase == "seed") {
            assumeTrue("Seed must run after external FULL image permission is granted", access == PhotoAccess.FULL)
            val fixtures = mutableListOf<Uri>()
            var keep = false
            var id: Int? = null
            try {
                inst.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
                    .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                val path = "Pictures/RotationPermission-${System.currentTimeMillis()}"
                repeat(2) { fixtures += addPhoto(context, path, "permission-$it") }
                val images = MediaRepository(context).images()
                val first = images.first { it.uri in fixtures }
                val idValue = host.allocateAppWidgetId()
                id = idValue
                assertTrue(manager.bindAppWidgetIdIfAllowed(idValue, ComponentName(context, PhotoWidgetReceiver::class.java)))
                assertTrue(controller.configure(WidgetRecord(idValue, entireGallery = false,
                    selectedAlbums = listOf("${first.volume}:${first.bucketId}"), rotationMinutes = 15L)))
                val record = requireNotNull(dao.get(idValue))
                val active = work.getWorkInfosForUniqueWork("photo-rotation:$idValue").get(10, TimeUnit.SECONDS)
                    .firstOrNull { !it.state.isFinished }
                assertNotNull("FULL permission should have periodic work", active)
                file.writeText(JSONObject().put("id", idValue).put("record", record.toString())
                    .put("workId", active!!.id.toString()).put("fixtures", fixtures.joinToString("\n")).toString())
                keep = true
            } finally {
                if (!keep) {
                    id?.let { runCatching { controller.delete(it) }; runCatching { host.deleteAppWidgetId(it) } }
                    fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    file.delete()
                }
            }
            return@runBlocking
        }

        val seed = JSONObject(file.readText())
        val id = seed.getInt("id")
        if (phase == "restore") assumeTrue("Restore phase requires externally granted FULL image permission", access == PhotoAccess.FULL)
        if (phase == "blocked") assumeTrue("Set permission to DENIED or PARTIAL externally before blocked phase", access != PhotoAccess.FULL)
        try {
            assertEquals(ComponentName(context, PhotoWidgetReceiver::class.java), manager.getAppWidgetInfo(id)?.provider)
            if (phase == "blocked") {
                assertTrue("Seeded auto schedule should still be active before observing blocked access",
                    work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
                val frame = loadWidgetFrame(context, id)
                assertNull("Widget frame loading must fail closed when access is not FULL", frame.bitmap)
                assertTrue("Widget frame loading clears its preview cache", previewFiles(context, id).isEmpty())
                assertNull("Widget frame loading clears the persisted due time", dao.get(id)?.nextDueAt)
                assertTrue("Widget frame loading cancels periodic work",
                    work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS).none { !it.state.isFinished })

                // Cover the separate app-resume observation route after the widget-frame path.
                controller.reconcileAll()
                assertTrue("Permission loss cancels periodic work",
                    work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS).none { !it.state.isFinished })
                assertTrue("Permission observation clears widget preview cache", previewFiles(context, id).isEmpty())
                return@runBlocking
            }

            controller.reconcileAll()
            val restored = requireNotNull(dao.get(id))
            assertNotNull(restored.currentUri)
            assertNotNull("Restoration initializes a new due time", restored.nextDueAt)
            assertTrue("Restored due time is approximately one interval ahead",
                restored.nextDueAt!! in (System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(14))..
                    (System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(16)))
            val active = work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS)
                .firstOrNull { !it.state.isFinished }
            assertNotNull("FULL restoration recreates periodic work", active)
            assertNotEquals("Restoration must create a fresh unique periodic request", seed.getString("workId"), active!!.id.toString())
        } finally {
            if (phase == "restore") {
                runCatching { controller.delete(id) }; runCatching { host.deleteAppWidgetId(id) }
                val fixtureRows = seed.getString("fixtures").split('\n').filter { it.isNotBlank() }
                fixtureRows.forEach { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
                file.delete()
            }
        }
    }

    private fun addPhoto(context: android.content.Context, path: String, name: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(0xFF465B70.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }

    private fun previewFiles(context: android.content.Context, id: Int) =
        context.cacheDir.resolve("widget-previews").listFiles().orEmpty().filter { it.name.startsWith("$id-") }

    private companion object { const val HOST_ID = 8658; const val STATE_FILE = "m5-rotation-permission.json" }
}

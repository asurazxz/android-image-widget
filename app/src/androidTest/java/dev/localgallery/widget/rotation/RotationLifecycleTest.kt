package dev.localgallery.widget.rotation

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
import androidx.work.WorkManager
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.MediaRepository
import dev.localgallery.widget.PhotoWidgetReceiver
import dev.localgallery.widget.WidgetController
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Two-phase M5 lifecycle check; seed state is intentionally retained for external reboot/force-stop/doze. */
class RotationLifecycleTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test fun periodicSchedulesAndWidgetStateSurviveExternalLifecycle() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("m5LifecyclePhase")
        assumeTrue("Pass -e m5LifecyclePhase seed or verify", phase == "seed" || phase == "verify")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val stateFile = context.filesDir.resolve(STATE_FILE)
        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val work = WorkManager.getInstance(context)
        val dao = WidgetDatabase.get(context).widgets()
        val controller = WidgetController(context)

        if (phase == "seed") {
            val ids = mutableListOf<Int>()
            val fixtures = mutableListOf<Uri>()
            var keep = false
            try {
                inst.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
                    .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                val seedTime = System.currentTimeMillis()
                val paths = (0 until 4).map { "Pictures/RotationLifecycle-$seedTime-$it" }
                paths.forEachIndexed { index, path -> repeat(3) { fixtures += addPhoto(context, path, "lifecycle-$index-$it") } }
                val images = MediaRepository(context).images()
                val sources = paths.map { path ->
                    val uri = fixtures.first { relativePath(context, it) == path }
                    val image = images.first { it.uri == uri }
                    "${image.volume}:${image.bucketId}"
                }
                assertEquals("Lifecycle widgets use disjoint albums", 4, sources.toSet().size)
                val intervals = listOf(15L, 30L, 60L, 0L)
                repeat(4) { index ->
                    val id = host.allocateAppWidgetId()
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, PhotoWidgetReceiver::class.java)))
                    ids += id
                    assertTrue(controller.configure(WidgetRecord(id, entireGallery = false,
                        selectedAlbums = listOf(sources[index]), rotationMinutes = intervals[index])))
                }
                val expected = JSONArray()
                ids.forEach { id ->
                    val record = requireNotNull(dao.get(id))
                    val infos = work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS)
                    val active = infos.firstOrNull { !it.state.isFinished }
                    expected.put(JSONObject().put("id", id).put("record", record.toString())
                        .put("workId", active?.id?.toString()).put("rotation", record.rotationMinutes))
                }
                stateFile.writeText(JSONObject().put("widgets", expected)
                    .put("fixtures", JSONArray().apply { fixtures.forEach { put(it.toString()) } }).toString())
                keep = true
            } finally {
                if (!keep) {
                    ids.forEach { runCatching { controller.delete(it) }; runCatching { host.deleteAppWidgetId(it) } }
                    fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    stateFile.delete()
                }
            }
            return@runBlocking
        }

        val snapshot = JSONObject(stateFile.readText())
        val expected = snapshot.getJSONArray("widgets")
        try {
            assertEquals("Seed should contain auto 15m, 30m, 60m and manual-only widgets", 4, expected.length())
            for (index in 0 until expected.length()) {
                val item = expected.getJSONObject(index)
                val id = item.getInt("id")
                assertEquals(ComponentName(context, PhotoWidgetReceiver::class.java), manager.getAppWidgetInfo(id)?.provider)
                assertEquals("Widget record should survive external lifecycle", item.getString("record"), dao.get(id).toString())
                val active = work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS)
                    .firstOrNull { !it.state.isFinished }
                if (item.getLong("rotation") == 0L) assertNull("Manual-only has no active periodic work", active)
                else {
                    assertNotNull("Periodic work should be restored for widget $id", active)
                    assertEquals(item.getString("workId"), active!!.id.toString())
                }
            }
        } finally {
            for (index in 0 until expected.length()) {
                val id = expected.getJSONObject(index).getInt("id")
                runCatching { controller.delete(id) }; runCatching { host.deleteAppWidgetId(id) }
            }
            val fixtures = snapshot.getJSONArray("fixtures")
            for (index in 0 until fixtures.length()) runCatching {
                context.contentResolver.delete(Uri.parse(fixtures.getString(index)), null, null)
            }
            stateFile.delete()
        }
    }

    private fun addPhoto(context: android.content.Context, path: String, name: String): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png"); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(0xFF705840.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }

    private fun relativePath(context: android.content.Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.trimEnd('/') else null
        }

    private companion object { const val HOST_ID = 8657; const val STATE_FILE = "m5-rotation-lifecycle.json" }
}

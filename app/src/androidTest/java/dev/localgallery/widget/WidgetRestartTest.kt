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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Two-phase acceptance check; seed and verify are launched in distinct app processes. */
class WidgetRestartTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test
    fun threeWidgetRecordsAndBindingsSurviveExternalProcessRestart() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("m4RestartPhase")
        assumeTrue("This acceptance test runs only with m4RestartPhase=seed or verify", phase == "seed" || phase == "verify")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stateFile = context.filesDir.resolve(STATE_FILE)
        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val controller = WidgetController(context)
        val dao = WidgetDatabase.get(context).widgets()

        if (phase == "seed") {
            val ids = mutableListOf<Int>()
            val fixtures = mutableListOf<Uri>()
            var keepSeed = false
            try {
                instrumentation.uiAutomation.executeShellCommand(
                    "appwidget grantbind --package ${context.packageName} --user 0",
                ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                val paths = (0..2).map { "Pictures/WidgetRestart-${System.currentTimeMillis()}-$it" }
                paths.forEachIndexed { album, path ->
                    repeat(3) { index -> fixtures += addPhoto(context, path, "restart-$album-$index") }
                }
                val images = MediaRepository(context).images()
                val albumKeys = paths.map { path ->
                    val image = images.first { candidate -> candidate.uri in fixtures && relativePath(context, candidate.uri) == path }
                    "${image.volume}:${image.bucketId}"
                }
                assertEquals(3, albumKeys.toSet().size)
                repeat(3) {
                    val id = host.allocateAppWidgetId()
                    assertTrue("Test host must bind widget $id", manager.bindAppWidgetIdIfAllowed(
                        id, ComponentName(context, PhotoWidgetReceiver::class.java),
                    ))
                    ids += id
                }
                val expected = JSONArray()
                ids.forEachIndexed { index, id ->
                    val record = WidgetRecord(
                        appWidgetId = id,
                        entireGallery = false,
                        selectedAlbums = listOf(albumKeys[index]),
                        displayMode = if (index == 1) "FIT" else "CROP",
                        preferredViewer = "restart.viewer.$index",
                        rotationMinutes = (index + 1) * 120L,
                    )
                    assertTrue("Widget $id should configure", controller.configure(record))
                    val saved = requireNotNull(dao.get(id))
                    assertNotNull(saved.currentUri)
                    assertEquals(2, saved.remaining.size)
                    expected.put(JSONObject().put("record", saved.toString()).put("id", id))
                }
                stateFile.writeText(JSONObject()
                    .put("widgets", expected)
                    .put("fixtures", JSONArray().apply { fixtures.forEach { put(it.toString()) } })
                    .toString())
                keepSeed = true
            } finally {
                if (!keepSeed) {
                    ids.forEach { id -> runCatching { controller.delete(id) }; runCatching { host.deleteAppWidgetId(id) } }
                    fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    stateFile.delete()
                }
            }
            return@runBlocking
        }

        val snapshot = JSONObject(stateFile.readText())
        val expectedWidgets = snapshot.getJSONArray("widgets")
        val ids = (0 until expectedWidgets.length()).map { expectedWidgets.getJSONObject(it).getInt("id") }
        try {
            assertEquals("Three widget states should have been seeded", 3, expectedWidgets.length())
            ids.forEachIndexed { index, id ->
                assertEquals("Widget $id must remain bound after process restart",
                    ComponentName(context, PhotoWidgetReceiver::class.java), manager.getAppWidgetInfo(id)?.provider)
                val actual = dao.get(id)
                assertNotNull("Widget $id Room record should survive restart", actual)
                assertEquals("Persisted widget $id must match its seed configuration, current image and queue",
                    expectedWidgets.getJSONObject(index).getString("record"), actual.toString())
            }
        } finally {
            ids.forEach { id -> runCatching { controller.delete(id) }; runCatching { host.deleteAppWidgetId(id) } }
            val fixtures = snapshot.getJSONArray("fixtures")
            for (index in 0 until fixtures.length()) {
                runCatching { context.contentResolver.delete(Uri.parse(fixtures.getString(index)), null, null) }
            }
            stateFile.delete()
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
                try { bitmap.eraseColor(0xFF685443.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun relativePath(context: android.content.Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.trimEnd('/') else null
        }

    private companion object {
        const val HOST_ID = 8655
        const val STATE_FILE = "m4-widget-restart.json"
    }
}

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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.async
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.rotation.RotationWorker
import dev.localgallery.widget.PhotoWidgetReceiver
import dev.localgallery.widget.WidgetController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** M5 runtime acceptance using synthetic MediaStore fixtures and isolated host-bound IDs. */
class RotationIntegrationTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test fun independentSchedulesDueGuardsAndWorkerDispatch() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        inst.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val host = AppWidgetHost(context, HOST_ID)
        val manager = AppWidgetManager.getInstance(context)
        val work = WorkManager.getInstance(context)
        val dao = WidgetDatabase.get(context).widgets()
        val fixtures = mutableListOf<Uri>()
        val ids = mutableListOf<Int>()
        val now = System.currentTimeMillis()
        val controller = WidgetController(context) { now }
        try {
            val paths = (0 until 5).map { "Pictures/RotationIntegration-$now-$it" }
            paths.forEachIndexed { index, path -> repeat(3) { fixtures += addPhoto(context, path, "rotation-$index-$it") } }
            val images = dev.localgallery.media.MediaRepository(context).images()
            val sources = paths.map { path ->
                val uri = fixtures.first { fixture -> relativePath(context, fixture) == path }
                val image = images.first { it.uri == uri }
                "${image.volume}:${image.bucketId}"
            }
            assertEquals("Each runtime widget uses an independent album", 5, sources.toSet().size)
            repeat(5) {
                val id = host.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, PhotoWidgetReceiver::class.java)))
                ids += id
            }
            val intervals = listOf(15L, 30L, 120L, 0L, 60L)
            ids.forEachIndexed { index, id ->
                assertTrue(controller.configure(WidgetRecord(id, entireGallery = false,
                    selectedAlbums = listOf(sources[index]), rotationMinutes = intervals[index])))
                val saved = requireNotNull(dao.get(id))
                if (intervals[index] > 0) assertEquals(now + TimeUnit.MINUTES.toMillis(intervals[index]), saved.nextDueAt)
                else assertNull(saved.nextDueAt)
                val infos = work.getWorkInfosForUniqueWork("photo-rotation:$id").get(10, TimeUnit.SECONDS)
                if (intervals[index] == 0L) assertTrue("Manual only must have no rotation work", infos.none { !it.state.isFinished })
                else {
                    val active = infos.firstOrNull { !it.state.isFinished }
                    assertNotNull("Widget $id must have periodic rotation work", active)
                    assertTrue("Request should be tagged for rotation", "photo-rotation" in active!!.tags)
                    assertTrue("Request should be scoped to its widget", "photo-rotation-widget:$id" in active.tags)
                    assertTrue("Request should record its interval", "photo-rotation-interval:${intervals[index]}" in active.tags)
                    assertEquals(TimeUnit.MINUTES.toMillis(intervals[index]), active.periodicityInfo?.repeatIntervalMillis)
                    assertTrue("Rotation must not require a network", active.constraints.requiredNetworkType == androidx.work.NetworkType.NOT_REQUIRED)
                }
            }
            val beforeEarly = requireNotNull(dao.get(ids[0]))
            controller.advance(ids[0], automatic = true)
            assertEquals("Early automatic invocation must not advance", beforeEarly, dao.get(ids[0]))

            // At the exact due time, simultaneous worker invocations serialize to one advancement.
            val dueController = WidgetController(context) { now + TimeUnit.MINUTES.toMillis(15) }
            val atDue = requireNotNull(dao.get(ids[0]))
            listOf(1, 2).map { async { dueController.advance(ids[0], automatic = true) } }.forEach { it.await() }
            val afterDue = requireNotNull(dao.get(ids[0]))
            assertNotEquals(atDue.currentUri, afterDue.currentUri)
            assertEquals(atDue.cycle, afterDue.cycle)
            assertEquals("Concurrent auto runs should advance only once", atDue.seen.size + 1, afterDue.seen.size)

            // A late run spanning several intervals advances once, and a manual shuffle preserves due/work identity.
            val delayedController = WidgetController(context) { now + TimeUnit.HOURS.toMillis(8) }
            val beforeDelayed = requireNotNull(dao.get(ids[1]))
            delayedController.advance(ids[1], automatic = true)
            val delayed = requireNotNull(dao.get(ids[1]))
            assertEquals("Late run advances at most one image", beforeDelayed.seen.size + 1, delayed.seen.size)
            assertEquals(now + TimeUnit.HOURS.toMillis(8) + TimeUnit.MINUTES.toMillis(30), delayed.nextDueAt)

            // A manual shuffle racing a due worker both serialize and retain the auto due time.
            val raceBefore = requireNotNull(dao.get(ids[2]))
            val raceNow = now + TimeUnit.MINUTES.toMillis(120)
            val raceController = WidgetController(context) { raceNow }
            listOf(
                async { raceController.advance(ids[2], automatic = true) },
                async { raceController.advance(ids[2], automatic = false) },
            ).forEach { it.await() }
            val raceAfter = requireNotNull(dao.get(ids[2]))
            assertEquals("Racing auto and manual actions each commit one serialized advance", raceBefore.seen.size + 2, raceAfter.seen.size)
            assertEquals("Manual action preserves the automatic due time", raceNow + TimeUnit.MINUTES.toMillis(120), raceAfter.nextDueAt)

            val beforeManual = requireNotNull(dao.get(ids[2]))
            val workBeforeManual = work.getWorkInfosForUniqueWork("photo-rotation:${ids[2]}").get().first()
            val workIdBefore = workBeforeManual.id
            val nextScheduleBeforeManual = workBeforeManual.nextScheduleTimeMillis
            controller.advance(ids[2], automatic = false)
            val afterManual = requireNotNull(dao.get(ids[2]))
            assertEquals(beforeManual.nextDueAt, afterManual.nextDueAt)
            val workAfterManual = work.getWorkInfosForUniqueWork("photo-rotation:${ids[2]}").get().first()
            assertEquals(workIdBefore, workAfterManual.id)
            assertEquals(workBeforeManual.generation, workAfterManual.generation)
            assertEquals(nextScheduleBeforeManual, workAfterManual.nextScheduleTimeMillis)

            // Interval edit reschedules in place and resets due time without selecting another photo.
            val beforeEdit = requireNotNull(dao.get(ids[2]))
            val oldWorkId = workIdBefore
            val editNow = now + TimeUnit.MINUTES.toMillis(5)
            val editController = WidgetController(context) { editNow }
            assertTrue(editController.configure(beforeEdit.copy(rotationMinutes = 60L)))
            val edited = requireNotNull(dao.get(ids[2]))
            assertEquals(beforeEdit.currentUri, edited.currentUri)
            assertEquals(editNow + TimeUnit.MINUTES.toMillis(60), edited.nextDueAt)
            val updatedWork = work.getWorkInfosForUniqueWork("photo-rotation:${ids[2]}").get().first()
            assertEquals(oldWorkId, updatedWork.id)
            assertTrue("Updated periodic schedule cannot run before its reset Room due time",
                updatedWork.nextScheduleTimeMillis >= edited.nextDueAt!!)

            // Dispatch each real periodic worker without waiting for its configured interval.
            // Test-only scheduling overrides never enter production code.
            suspend fun dispatchPeriodic(widgetId: Int, intervalMinutes: Long, overrideNextSchedule: Boolean = false) {
                val dispatchBefore = requireNotNull(dao.get(widgetId))
                val peerBeforeDispatch = ids.filterNot { it == widgetId }.associateWith { dao.get(it) }
                val realNow = System.currentTimeMillis()
                dao.put(dispatchBefore.copy(nextDueAt = realNow - 1))
                val uniqueName = "photo-rotation:$widgetId"
                val originalPeriodic = work.getWorkInfosForUniqueWork(uniqueName).get().first { !it.state.isFinished }
                val builder = PeriodicWorkRequestBuilder<RotationWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setInitialDelay(0, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(RotationWorker.EXTRA_WIDGET_ID to widgetId))
                    .addTag("photo-rotation").addTag("photo-rotation-widget:$widgetId")
                    .addTag("photo-rotation-interval:$intervalMinutes")
                if (overrideNextSchedule) builder.setNextScheduleTimeOverride(realNow)
                work.enqueueUniquePeriodicWork(uniqueName, androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    builder.build()).result.get(10, TimeUnit.SECONDS)

                val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
                var dispatchAfter = requireNotNull(dao.get(widgetId))
                var dispatchInfo: WorkInfo? = null
                while (System.currentTimeMillis() < deadline) {
                    dispatchAfter = requireNotNull(dao.get(widgetId))
                    dispatchInfo = requireNotNull(work.getWorkInfoById(originalPeriodic.id).get(5, TimeUnit.SECONDS))
                    if (dispatchAfter.revision == dispatchBefore.revision + 1 &&
                        dispatchInfo.state == WorkInfo.State.ENQUEUED &&
                        dispatchInfo.nextScheduleTimeMillis > System.currentTimeMillis()) break
                    Thread.sleep(200)
                }
                val periodicAfterDispatch = work.getWorkInfosForUniqueWork(uniqueName).get().first()
                assertEquals("Periodic UPDATE should preserve widget $widgetId work identity", originalPeriodic.id, periodicAfterDispatch.id)
                assertEquals("Widget $widgetId receives one worker advancement", dispatchBefore.revision + 1, dispatchAfter.revision)
                assertNotEquals("Worker $widgetId changes only its own current image", dispatchBefore.currentUri, dispatchAfter.currentUri)
                assertNotNull("Worker $widgetId records automatic execution", dispatchAfter.lastAutomaticAt)
                assertEquals("Worker $widgetId sets due from execution plus its interval",
                    dispatchAfter.lastAutomaticAt!! + TimeUnit.MINUTES.toMillis(intervalMinutes), dispatchAfter.nextDueAt)
                assertEquals("Worker $widgetId returns to an enqueued periodic state", WorkInfo.State.ENQUEUED, dispatchInfo?.state)
                assertTrue("Widget $widgetId next periodic schedule is in the future",
                    dispatchInfo!!.nextScheduleTimeMillis > System.currentTimeMillis())
                assertEquals("Worker $widgetId leaves peer widget records unchanged", peerBeforeDispatch,
                    ids.filterNot { it == widgetId }.associateWith { dao.get(it) })
            }
            dispatchPeriodic(ids[0], 15L)
            dispatchPeriodic(ids[1], 30L)
            dispatchPeriodic(ids[2], 60L, overrideNextSchedule = true)

            controller.delete(ids[0])
            host.deleteAppWidgetId(ids[0])
            assertTrue("Deleting a still-auto widget cancels periodic work",
                work.getWorkInfosForUniqueWork("photo-rotation:${ids[0]}").get().none { !it.state.isFinished })

            assertTrue(controller.configure(requireNotNull(dao.get(ids[2])).copy(rotationMinutes = 0L)))
            assertNull(dao.get(ids[2])?.nextDueAt)
            assertTrue("Switching to manual cancels periodic work",
                work.getWorkInfosForUniqueWork("photo-rotation:${ids[2]}").get().none { !it.state.isFinished })

            val staleId = Int.MAX_VALUE - 35
            dev.localgallery.rotation.RotationScheduler(context).reconcile(staleId, 15L, System.currentTimeMillis() + 60_000)
            controller.reconcileAll()
            assertTrue("Orphan widget tags are reconciled", work.getWorkInfosForUniqueWork("photo-rotation:$staleId").get().none { !it.state.isFinished })

            // An empty eligible pool cannot spin; restore a fixture and refresh makes it usable again.
            val emptyId = ids[4]
            val emptyPath = paths[4]
            fixtures.filter { relativePath(context, it) == emptyPath }.forEach { context.contentResolver.delete(it, null, null) }
            fixtures.removeAll { relativePath(context, it) == emptyPath }
            controller.refresh(emptyId)
            val empty = requireNotNull(dao.get(emptyId))
            assertNull(empty.currentUri)
            val emptyDueController = WidgetController(context) { now + TimeUnit.DAYS.toMillis(2) }
            emptyDueController.advance(emptyId, automatic = true)
            val deferred = requireNotNull(dao.get(emptyId))
            assertEquals(empty.currentUri, deferred.currentUri)
            assertEquals(empty.seen, deferred.seen)
            assertEquals(empty.lastAutomaticAt, deferred.lastAutomaticAt)
            assertEquals(now + TimeUnit.DAYS.toMillis(2) + TimeUnit.MINUTES.toMillis(60), deferred.nextDueAt)
            emptyDueController.advance(emptyId, automatic = true)
            assertEquals("Repeated invocation before the deferred due time is a no-op", deferred, dao.get(emptyId))
            val restored = addPhoto(context, emptyPath, "rotation-restored")
            fixtures += restored
            controller.refresh(emptyId)
            assertNotNull(dao.get(emptyId)?.currentUri)
        } finally {
            ids.forEach { id -> runCatching { controller.delete(id) }; runCatching { host.deleteAppWidgetId(id) } }
            fixtures.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
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
                try { bitmap.eraseColor(0xFF496457.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
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

    private companion object { const val HOST_ID = 8656 }
}

package dev.localgallery.rotation

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dev.localgallery.widget.PhotoWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Owns the unique periodic WorkManager request for each widget. */
class RotationScheduler(context: Context) {
    private val context = context.applicationContext
    private val workManager = WorkManager.getInstance(this.context)

    suspend fun reconcile(
        id: Int,
        minutes: Long,
        nextDueAt: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        val uniqueName = uniqueName(id)
        if (id <= 0 || !RotationPolicy.isValidInterval(minutes) || minutes == 0L || nextDueAt == null) {
            workManager.cancelUniqueWork(uniqueName).await()
            return
        }

        val intervalTag = intervalTag(minutes)
        val existing = workManager.getWorkInfosForUniqueWork(uniqueName).getBounded()
        if (existing.any { !it.state.isFinished && intervalTag in it.tags }) return

        val initialDelay = if (nextDueAt <= now) 0L else {
            if (now < 0L && nextDueAt > Long.MAX_VALUE + now) Long.MAX_VALUE else nextDueAt - now
        }
        val builder = PeriodicWorkRequestBuilder<RotationWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putInt(RotationWorker.EXTRA_WIDGET_ID, id).build())
            .addTag(WORK_TAG)
            .addTag(widgetTag(id))
            .addTag(intervalTag)
        // UPDATE retains the original enqueue time. Align the changed interval's next
        // eligibility to the new guard; WorkManager clears this override after that run.
        if (existing.any { !it.state.isFinished }) builder.setNextScheduleTimeOverride(nextDueAt)
        val request = builder.build()
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request).await()
    }

    suspend fun cancel(id: Int) {
        workManager.cancelUniqueWork(uniqueName(id)).await()
    }

    /** Remove stale work only for widget-scoped names represented by our own tags. */
    suspend fun cancelStale(activeIds: Set<Int>) {
        val taggedWork = workManager.getWorkInfosByTag(WORK_TAG).getBounded()
        val currentlyBoundIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, PhotoWidgetReceiver::class.java),
        ).toSet()
        val staleIds = taggedWork.asSequence()
            .filterNot { it.state.isFinished }
            .flatMap { it.tags.asSequence() }
            .mapNotNull(::widgetIdFromTag)
            .filterNot { it in activeIds || it in currentlyBoundIds }
            .toSet()
        staleIds.forEach { cancel(it) }
    }

    private fun uniqueName(id: Int) = "$UNIQUE_PREFIX$id"

    private fun widgetTag(id: Int) = "$WIDGET_TAG_PREFIX$id"

    private fun intervalTag(minutes: Long) = "$INTERVAL_TAG_PREFIX$minutes"

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.getBounded(): T =
        withContext(Dispatchers.IO) { get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

    private fun widgetIdFromTag(tag: String): Int? = tag.removePrefix(WIDGET_TAG_PREFIX)
        .takeIf { tag.startsWith(WIDGET_TAG_PREFIX) }
        ?.toIntOrNull()

    companion object {
        const val WORK_TAG = "photo-rotation"
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val UNIQUE_PREFIX = "photo-rotation:"
        private const val WIDGET_TAG_PREFIX = "photo-rotation-widget:"
        private const val INTERVAL_TAG_PREFIX = "photo-rotation-interval:"
    }
}

package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.withTransaction
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.domain.ShuffleEngine
import dev.localgallery.domain.ShuffleSnapshot
import dev.localgallery.media.MediaImage
import dev.localgallery.media.MediaRepository
import dev.localgallery.media.MediaSources
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.rotation.RotationPolicy
import dev.localgallery.rotation.RotationScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Shared by actions, configuration and workers. All operations for one ID serialize. */
class WidgetController(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val context = context.applicationContext
    private val database = WidgetDatabase.get(context)
    private val dao = database.widgets()
    private val renderer = WidgetImageRenderer(context)
    private val permission = PhotoPermissionManager(context)
    private val scheduler = RotationScheduler(context)

    suspend fun configure(request: WidgetRecord): Boolean = withContext(Dispatchers.IO) {
      val saved = lock(request.appWidgetId).withLock {
        if (!exists(request.appWidgetId)) { remove(request.appWidgetId); return@withLock false }
        if (permission.status() != PhotoAccess.FULL) { clearPhoto(request.appWidgetId); return@withLock false }
        if ((!request.entireGallery && request.selectedAlbums.isEmpty()) ||
            request.displayMode !in setOf("CROP", "FIT") ||
            request.fitBackgroundColor ushr 24 != 0xFF ||
            !RotationPolicy.isValidInterval(request.rotationMinutes)) return@withLock false
        val previous = dao.get(request.appWidgetId)
        val sourceChanged = previous == null || previous.entireGallery != request.entireGallery ||
            previous.selectedAlbums.toSet() != request.selectedAlbums.toSet()
        val base = if (previous == null) request else previous.copy(
            entireGallery = request.entireGallery, selectedAlbums = request.selectedAlbums,
            displayMode = request.displayMode, preferredViewer = request.preferredViewer,
            rotationMinutes = request.rotationMinutes, fitBackgroundColor = request.fitBackgroundColor,
        )
        val candidates = pool(base)
        if (candidates.isEmpty()) return@withLock false
        val selected = if (sourceChanged) {
            select(base.copy(currentUri = null, remaining = emptyList(), seen = emptyList(), cycle = 0), candidates)
        } else if (base.currentUri !in candidates) {
            select(base, candidates)
        } else reconcile(base, candidates.keys)
        if (permission.status() != PhotoAccess.FULL) { clearPhoto(request.appWidgetId); return@withLock false }
        if (selected.currentUri == null) return@withLock false
        if (!exists(request.appWidgetId)) { remove(request.appWidgetId); return@withLock false }
        persistAndSchedule(selected, resetDue = previous == null || previous.rotationMinutes != selected.rotationMinutes)
        true
      }
      if (saved) render(request.appWidgetId)
      saved
    }

    suspend fun advance(id: Int, automatic: Boolean = false) = withContext(Dispatchers.IO) {
      var changed = false
      lock(id).withLock {
        val previous = dao.get(id) ?: run { remove(id); return@withLock }
        if (!exists(id)) { remove(id); return@withLock }
        if (permission.status() != PhotoAccess.FULL) { clearPhoto(id); return@withLock }
        if (automatic) {
            if (previous.rotationMinutes == 0L || !RotationPolicy.isValidInterval(previous.rotationMinutes)) {
                persistAndSchedule(previous)
                return@withLock
            }
            // A restored M4 row or permission-restored widget first gets a full interval.
            if (previous.nextDueAt == null) { persistAndSchedule(previous); return@withLock }
            if (!RotationPolicy.isDue(previous.nextDueAt, now())) return@withLock
        }
        val selected = try { select(previous, pool(previous)) }
            catch (_: SecurityException) { clearPhoto(id); return@withLock }
        if (permission.status() != PhotoAccess.FULL) { clearPhoto(id); return@withLock }
        if (!exists(id)) { remove(id); return@withLock }
        val finishedAt = now()
        val timed = if (automatic) selected.copy(
            nextDueAt = RotationPolicy.nextDueAfterRun(finishedAt, previous.rotationMinutes),
            lastAutomaticAt = if (selected.currentUri != null) finishedAt else previous.lastAutomaticAt,
        ) else selected
        persistAndSchedule(timed)
        changed = true
      }
      if (changed) render(id)
    }

    suspend fun refresh(id: Int) = withContext(Dispatchers.IO) {
        reconcileForRender(id)
        render(id)
    }

    suspend fun reconcileForRender(id: Int) = withContext(Dispatchers.IO) { lock(id).withLock {
        val previous = dao.get(id) ?: run { remove(id); return@withLock }
        if (!exists(id)) { remove(id); return@withLock }
        if (permission.status() != PhotoAccess.FULL) { clearPhoto(id, renderBeforeCancel = false); return@withLock }
        val available = try { pool(previous) } catch (_: SecurityException) { clearPhoto(id, renderBeforeCancel = false); return@withLock }
        val bitmap = if (previous.currentUri in available) {
            renderer.prepare(id, previous.currentUri!!, previous.revision, fit = previous.displayMode == "FIT")
        } else null
        if (bitmap == null) {
            val selected = select(previous, available)
            if (!exists(id)) { remove(id); return@withLock }
            if (permission.status() == PhotoAccess.FULL) persistAndSchedule(selected)
            else { clearPhoto(id, renderBeforeCancel = false); return@withLock }
        } else {
            bitmap.recycle()
            val reconciled = reconcile(previous, available.keys)
            if (!exists(id)) { remove(id); return@withLock }
            if (permission.status() != PhotoAccess.FULL) { clearPhoto(id, renderBeforeCancel = false); return@withLock }
            persistAndSchedule(reconciled)
        }
    } }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) { lock(id).withLock { remove(id) } }

    /** App resume is a permission observation even when no widget action was tapped. */
    suspend fun reconcileAll() = withContext(Dispatchers.IO) {
        val active = AppWidgetManager.getInstance(context).getAppWidgetIds(
            android.content.ComponentName(context, PhotoWidgetReceiver::class.java)).toList()
        val stored = dao.all().map { it.appWidgetId }
        (active + stored).distinct().forEach { refresh(it) }
        scheduler.cancelStale(active.toSet())
    }

    private suspend fun pool(record: WidgetRecord): Map<String, MediaImage> {
        val images = MediaRepository(context).images()
        val eligible = MediaSources.eligible(images.associate { it.key to "${it.volume}:${it.bucketId}" },
            record.entireGallery, record.selectedAlbums.toSet())
        return images.filter { it.key in eligible }.associateBy { it.key }
    }

    private fun reconcile(record: WidgetRecord, available: Set<String>): WidgetRecord {
        val snapshot = ShuffleEngine.reconcile(available,
            ShuffleSnapshot(record.currentUri, record.remaining, record.seen.toSet(), record.cycle))
        return record.copy(remaining = snapshot.remaining, seen = snapshot.seen.toList())
    }

    private suspend fun select(record: WidgetRecord, candidates: Map<String, MediaImage>): WidgetRecord {
        val available = candidates.keys.toMutableSet()
        var snapshot = ShuffleSnapshot(record.currentUri, record.remaining, record.seen.toSet(), record.cycle)
        repeat(minOf(available.size, 8)) {
            snapshot = ShuffleEngine.advance(available, snapshot)
            val media = candidates[snapshot.current] ?: return@repeat
            val bitmap = renderer.prepare(record.appWidgetId, media.key, record.revision + 1,
                fit = record.displayMode == "FIT")
            if (bitmap != null) {
                bitmap.recycle()
                return record.copy(currentUri = media.key, currentMime = media.mimeType,
                    remaining = snapshot.remaining, seen = snapshot.seen.toList(), cycle = snapshot.cycle,
                    revision = record.revision + 1)
            }
            available.remove(media.key)
        }
        renderer.clear(record.appWidgetId)
        return record.copy(currentUri = null, currentMime = null, remaining = emptyList(), seen = emptyList(),
            revision = record.revision + 1)
    }

    /** Timing writes and work changes happen under the same widget lock as selection. */
    private suspend fun persistAndSchedule(record: WidgetRecord, resetDue: Boolean = false) {
        val at = now()
        val timed = when {
            record.rotationMinutes == 0L || !RotationPolicy.isValidInterval(record.rotationMinutes) -> record.copy(nextDueAt = null)
            resetDue || record.nextDueAt == null -> record.copy(nextDueAt = RotationPolicy.nextDueAfterRun(at, record.rotationMinutes))
            else -> record
        }
        if (dao.get(timed.appWidgetId) != timed) database.withTransaction { dao.put(timed) }
        scheduler.reconcile(timed.appWidgetId, timed.rotationMinutes, timed.nextDueAt, at)
    }

    private suspend fun clearPhoto(id: Int, renderBeforeCancel: Boolean = true) {
        renderer.clear(id)
        dao.get(id)?.takeIf { it.nextDueAt != null }?.let { previous ->
            database.withTransaction { dao.put(previous.copy(nextDueAt = null)) }
        }
        // A running periodic worker may cancel itself here. Publish the recovery frame first.
        if (renderBeforeCancel) render(id)
        scheduler.cancel(id)
    }
    private suspend fun remove(id: Int) { dao.delete(id); renderer.clear(id); scheduler.cancel(id) }
    private suspend fun render(id: Int) {
        if (exists(id)) {
            try {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(id)
                updateAppWidgetState(context, glanceId) { state ->
                    state[WidgetRenderGenerationKey] = (state[WidgetRenderGenerationKey] ?: 0L) + 1L
                }
                PhotoWidget().update(context, glanceId)
            }
            catch (_: IllegalArgumentException) { /* Host removed the widget during this operation. */ }
        }
    }
    private fun exists(id: Int) = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ==
        android.content.ComponentName(context, PhotoWidgetReceiver::class.java)
    private companion object {
        val locks = ConcurrentHashMap<Int, Mutex>()
        fun lock(id: Int) = locks.getOrPut(id) { Mutex() }
    }
}

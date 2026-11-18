package dev.localgallery.rotation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.localgallery.widget.WidgetController
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeoutException

/** One widget, one bounded automatic selection; WorkManager owns inexact execution. */
class RotationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt(EXTRA_WIDGET_ID, -1)
        if (id <= 0) return Result.success()
        return try {
            WidgetController(applicationContext).advance(id, automatic = true)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            Result.retry()
        } catch (_: android.database.sqlite.SQLiteException) {
            Result.retry()
        } catch (_: TimeoutException) {
            Result.retry()
        }
    }

    companion object {
        const val EXTRA_WIDGET_ID = "appWidgetId"
    }
}

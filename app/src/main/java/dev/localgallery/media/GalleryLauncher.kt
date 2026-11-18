package dev.localgallery.media

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.localgallery.data.WidgetRecord
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.widget.R
import java.io.FileNotFoundException

data class ViewerOption(
    val packageName: String,
    val label: String,
)

class GalleryLauncher(private val context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val packageManager = appContext.packageManager
    private val photoPermissions = PhotoPermissionManager(appContext)

    fun viewers(): List<ViewerOption> {
        val probe = Intent(Intent.ACTION_VIEW).setType(IMAGE_MIME_TYPE)
        return packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { resolveInfo ->
                ViewerOption(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<ViewerOption> { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.packageName })
            .toList()
    }

    fun intentFor(record: WidgetRecord, preferred: Boolean = true): Intent {
        val uri = record.currentUri?.let(Uri::parse)
            ?: throw IllegalArgumentException("The widget has no current photo")
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Photo URI must be a MediaStore content URI" }
        val mimeType = record.currentMime?.takeIf { it.isNotBlank() } ?: IMAGE_MIME_TYPE

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(contentResolver, appContext.getString(R.string.widget_name), uri)
            if (preferred) record.preferredViewer?.takeIf { it.isNotBlank() }?.let(::setPackage)
        }
    }

    fun open(record: WidgetRecord): Boolean {
        if (photoPermissions.status() != PhotoAccess.FULL) return false
        val uri = record.currentUri?.let(Uri::parse) ?: return false
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false

        try {
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return false
            descriptor.use { /* Opening the descriptor verifies that the original remains readable. */ }
        } catch (_: FileNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }

        if (photoPermissions.status() != PhotoAccess.FULL) return false

        val hasPreferredViewer = !record.preferredViewer.isNullOrBlank()
        if (hasPreferredViewer && launchIfResolved(intentFor(record, preferred = true))) return true
        return launchIfResolved(intentFor(record, preferred = false))
    }

    private fun launchIfResolved(intent: Intent): Boolean = try {
        if (context is Activity) {
            intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private companion object {
        const val IMAGE_MIME_TYPE = "image/*"
    }
}

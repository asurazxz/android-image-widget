package dev.localgallery.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class PhotoAccess {
    FULL,
    PARTIAL,
    DENIED,
}

object PermissionPolicy {
    fun evaluate(
        api: Int,
        legacyGranted: Boolean,
        imagesGranted: Boolean,
        selectedGranted: Boolean,
    ): PhotoAccess = when {
        api <= Build.VERSION_CODES.S_V2 -> if (legacyGranted) PhotoAccess.FULL else PhotoAccess.DENIED
        api == Build.VERSION_CODES.TIRAMISU -> if (imagesGranted) PhotoAccess.FULL else PhotoAccess.DENIED
        imagesGranted -> PhotoAccess.FULL
        selectedGranted -> PhotoAccess.PARTIAL
        else -> PhotoAccess.DENIED
    }
}

class PhotoPermissionManager(context: Context) {
    private val appContext = context.applicationContext

    fun status(): PhotoAccess {
        val legacyGranted = isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val imagesGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            isGranted(Manifest.permission.READ_MEDIA_IMAGES)
        val selectedGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        return PermissionPolicy.evaluate(
            api = Build.VERSION.SDK_INT,
            legacyGranted = legacyGranted,
            imagesGranted = imagesGranted,
            selectedGranted = selectedGranted,
        )
    }

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

package dev.localgallery.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localgallery.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScaffoldSmokeTest {
    @Test
    fun installedAppHasNoInternetExactAlarmOrForegroundServiceCapability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        val forbidden = setOf(
            "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
            "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
            "android.permission.QUERY_ALL_PACKAGES", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )
        assertTrue("Offline widget must not request unused capabilities", permissions.intersect(forbidden).isEmpty())
        assertFalse("Ordinary background rotation needs no foreground service",
            info.services.orEmpty().any { it.name == "androidx.work.impl.foreground.SystemForegroundService" })
    }

    @Test
    fun activityLaunchesAndPhotoWidgetProviderIsRegistered() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity.window) }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ComponentName(context, PhotoWidgetReceiver::class.java)
        val registered = context.getSystemService(android.appwidget.AppWidgetManager::class.java)
            .getInstalledProvidersForPackage(context.packageName, null)
            .firstOrNull { it.provider == provider }

        assertNotNull("Photo widget receiver should be registered with AppWidgetManager", registered)
        assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, registered!!.resizeMode)
    }
}

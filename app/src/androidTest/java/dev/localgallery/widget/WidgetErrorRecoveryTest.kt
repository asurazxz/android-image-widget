package dev.localgallery.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.localgallery.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetErrorRecoveryTest {
    @Test fun compositionErrorFallbackOpensConfigurationForBoundWidget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

        val host = AppWidgetHost(context, HOST_ID)
        val widgetIds = listOf(host.allocateAppWidgetId(), host.allocateAppWidgetId())
        val manager = AppWidgetManager.getInstance(context)
        widgetIds.forEach { widgetId ->
            assertTrue("The test host must bind widget $widgetId", manager.bindAppWidgetIdIfAllowed(
                widgetId,
                ComponentName(context, PhotoWidgetReceiver::class.java),
            ))
        }

        var scenario: ActivityScenario<MainActivity>? = null
        var hostStarted = false
        val hostViews = mutableMapOf<Int, AppWidgetHostView>()
        val launchedConfigurations = mutableListOf<android.app.Activity>()
        var monitor = instrumentation.addMonitor(PhotoWidgetConfigurationActivity::class.java.name, null, false)
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                host.startListening()
                hostStarted = true
                val stack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                widgetIds.forEach { widgetId ->
                    val info = manager.getAppWidgetInfo(widgetId)
                    assertNotNull("Bound widget should have provider info", info)
                    val view = host.createView(activity, widgetId, info!!)
                    hostViews[widgetId] = view
                    stack.addView(view, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, 240),
                    ))
                }
                activity.setContentView(stack, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }

            val glanceIds = widgetIds.associateWith { widgetId ->
                runBlocking { GlanceAppWidgetManager(context).getGlanceIdBy(widgetId) }
            }
            widgetIds.forEach { widgetId -> runBlocking { PhotoWidget().update(context, glanceIds.getValue(widgetId)) } }
            widgetIds.forEach { widgetId -> waitUntilSettled(instrumentation, hostViews.getValue(widgetId)) }
            widgetIds.forEach { widgetId ->
                PhotoWidget().onCompositionError(context, glanceIds.getValue(widgetId), widgetId,
                    IllegalStateException("test composition failure"))
            }
            val recoveryText = widgetIds.associateWith { widgetId ->
                awaitActionableFallback(instrumentation, hostViews.getValue(widgetId))
            }
            val firstId = widgetIds.first()
            val firstRecovery = recoveryText.getValue(firstId)
            instrumentation.runOnMainSync { firstRecovery.performClick() }
            val firstConfiguration = awaitConfiguration(monitor, firstId)
            launchedConfigurations += firstConfiguration
            instrumentation.runOnMainSync { firstConfiguration.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            monitor = instrumentation.addMonitor(PhotoWidgetConfigurationActivity::class.java.name, null, false)

            val secondId = widgetIds.last()
            // Keep the original clickable view: configuration resume may re-render this widget.
            val secondRecovery = recoveryText.getValue(secondId)
            instrumentation.runOnMainSync { secondRecovery.performClick() }
            val secondConfiguration = awaitConfiguration(monitor, secondId)
            launchedConfigurations += secondConfiguration
            instrumentation.runOnMainSync { secondConfiguration.finish() }
        } finally {
            launchedConfigurations.forEach { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
            if (hostStarted) runCatching { host.stopListening() }
            scenario?.close()
            widgetIds.forEach(host::deleteAppWidgetId)
        }
    }

    private fun awaitConfiguration(
        monitor: android.app.Instrumentation.ActivityMonitor,
        widgetId: Int,
    ): android.app.Activity {
        val activity = monitor.waitForActivityWithTimeout(5_000L)
            ?: throw AssertionError("Fallback click should launch widget configuration")
        assertEquals(widgetId, activity.intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ))
        assertEquals("localgallery://widget-recovery/$widgetId", activity.intent.dataString)
        return activity
    }

    private fun waitUntilSettled(instrumentation: android.app.Instrumentation, hostView: AppWidgetHostView) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var settled = false
            instrumentation.runOnMainSync {
                val loading = hostView.findViewById<TextView>(R.id.widget_loading_root)
                settled = hostContainsText(hostView, "Set up your photos") ||
                    (loading?.text?.contains("temporarily unavailable", ignoreCase = true) == true && loading?.isClickable == true)
            }
            if (settled) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Initial Glance rendering did not settle")
    }

    private fun awaitActionableFallback(
        instrumentation: android.app.Instrumentation,
        hostView: AppWidgetHostView,
    ): TextView {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var recoveryText: TextView? = null
        while (SystemClock.uptimeMillis() < deadline && recoveryText == null) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                recoveryText = hostView.findViewById<TextView>(R.id.widget_loading_root)
                    ?.takeIf {
                        it.isClickable && it.text?.contains("temporarily unavailable", ignoreCase = true) == true
                    }
            }
            if (recoveryText == null) SystemClock.sleep(100L)
        }
        return recoveryText ?: throw AssertionError("Host should display the actionable composition-error fallback")
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun hostContainsText(view: View, expected: String): Boolean {
        if (view is TextView && view.text?.toString()?.contains(expected, ignoreCase = true) == true) return true
        return view is ViewGroup && (0 until view.childCount).any { hostContainsText(view.getChildAt(it), expected) }
    }

    private companion object {
        const val HOST_ID = 8_709
    }
}

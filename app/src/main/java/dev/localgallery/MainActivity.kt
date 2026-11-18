package dev.localgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.localgallery.ui.GalleryHome
import dev.localgallery.ui.GalleryActivityTheme
import dev.localgallery.ui.GalleryAppearanceSettings
import dev.localgallery.widget.publishPhotoWidgetPickerPreview
import dev.localgallery.widget.WidgetController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var refreshEpoch by mutableIntStateOf(0)
    private var reconciliation: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearances = GalleryAppearanceSettings.observe(applicationContext)
        setContent {
            val appearance by appearances.collectAsState(initial = null)
            GalleryActivityTheme(appearance ?: GalleryAppearanceSettings.latest) { GalleryHome(refreshEpoch, appearance) }
        }
        lifecycleScope.launch { publishPhotoWidgetPickerPreview(applicationContext) }
    }
    override fun onResume() {
        super.onResume()
        refreshEpoch++
        reconciliation?.cancel()
        reconciliation = lifecycleScope.launch { WidgetController(applicationContext).reconcileAll() }
    }
}

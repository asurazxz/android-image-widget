package dev.localgallery.media

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.permissions.PhotoAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaRepositoryInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(*runtimePermissions())

    @Test
    fun fullAccessListsAnAppOwnedPublishedImageWithItsVolumeQualifiedIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = "local-gallery-test-${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalGalleryTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val inserted = resolver.insert(collection, values)
        assertNotNull("MediaStore should accept an app-owned test image", inserted)
        val pending = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pending-$displayName")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalGalleryTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })
        assertNotNull("MediaStore should accept an app-owned pending image", pending)
        try {
            resolver.openOutputStream(inserted!!, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(inserted, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)

            val listedImages = MediaRepository(context).images()
            val found = listedImages.firstOrNull { image ->
                image.uri.lastPathSegment == inserted.lastPathSegment
            }

            assertNotNull("The published image should appear in the image collection", found)
            assertEquals(MediaStore.getVolumeName(inserted), found!!.volume)
            assertEquals(inserted.lastPathSegment, found.uri.lastPathSegment)
            assertEquals(found.uri.toString(), found.key)
            assertTrue(
                "Pending rows must be filtered",
                listedImages.none { it.uri.lastPathSegment == pending!!.lastPathSegment },
            )
        } finally {
            resolver.delete(inserted!!, null, null)
            resolver.delete(pending!!, null, null)
        }
    }

    @Test
    fun repositoryRejectsQueriesWithoutFullPhotoAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(PhotoAccess.DENIED, PhotoAccess.PARTIAL).forEach { access ->
            try {
                runBlocking { MediaRepository(context) { access }.images() }
                throw AssertionError("Expected the repository to reject access without full permission")
            } catch (_: SecurityException) {
                // Expected: querying without full access is a closed gate.
            }
        }
    }

    private companion object {
        fun runtimePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

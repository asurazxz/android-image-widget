package dev.localgallery.media

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Exercises a real MediaStore query and album grouping with a gallery-sized fixture set. */
@RunWith(AndroidJUnit4::class)
class MediaScaleInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*runtimePermissions())

    @Test
    fun repositoryListsOneThousandOneOwnedPhotosWithUniqueKeysAndAlbumCount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val albumPath = "Pictures/LocalGalleryScale-${System.currentTimeMillis()}"
        val fixtures = mutableListOf<android.net.Uri>()
        val pngBytes = createTinyPng()

        try {
            repeat(FIXTURE_COUNT) { index ->
                val uri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "photo-${index.toString().padStart(4, '0')}.png")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$albumPath/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ) ?: error("MediaStore refused test image $index")
                // Register each row immediately so cleanup also covers partial writes/publication failures.
                fixtures += uri
                resolver.openOutputStream(uri, "w")!!.use { it.write(pngBytes) }
                val published = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                check(published == 1) { "MediaStore did not publish test image $index" }
            }

            val repository = MediaRepository(context)
            val images = repository.images()
            val fixtureSet = fixtures.toSet()
            val listedFixtures = images.filter { it.uri in fixtureSet }
            assertEquals("Every published fixture should be queryable", FIXTURE_COUNT, listedFixtures.size)
            assertEquals("Fixture URIs should be unique", FIXTURE_COUNT, listedFixtures.map { it.uri }.toSet().size)
            assertEquals("Volume-qualified media keys should be unique", images.size, images.map { it.key }.toSet().size)
            assertTrue("MediaStore IDs should be unique", listedFixtures.map { it.uri.lastPathSegment }.toSet().size == FIXTURE_COUNT)

            val fixtureAlbumKeys = listedFixtures.map { AlbumGrouping.key(it.volume, it.bucketId) }.toSet()
            assertEquals("All fixture rows should share one indexed album", 1, fixtureAlbumKeys.size)
            val album = repository.albums().single { it.key == fixtureAlbumKeys.single() }
            assertEquals("Album count should include every fixture exactly once", FIXTURE_COUNT, album.count)
            assertTrue("Album cover should be one of the fixture photos", album.cover.uri in fixtureSet)
        } finally {
            fixtures.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        }
    }

    private fun createTinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val FIXTURE_COUNT = 1_001

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

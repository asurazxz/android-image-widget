package dev.localgallery.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSourcesTest {
    @Test
    fun entireGalleryIncludesEveryIndexedImage() {
        val imageAlbumKeys = mapOf(
            "primary:1" to "primary:camera",
            "primary:2" to "primary:downloads",
            "sdcard:1" to "sdcard:camera",
        )

        assertEquals(
            imageAlbumKeys.keys,
            MediaSources.eligible(imageAlbumKeys, entireGallery = true, selectedAlbums = emptySet()),
        )
    }

    @Test
    fun selectedAlbumModeReturnsUnionOfOnlySelectedAlbumImages() {
        val imageAlbumKeys = mapOf(
            "primary:1" to "primary:camera",
            "primary:2" to "primary:camera",
            "primary:3" to "primary:downloads",
            "sdcard:1" to "sdcard:camera",
        )

        assertEquals(
            setOf("primary:1", "primary:2", "sdcard:1"),
            MediaSources.eligible(
                imageAlbumKeys,
                entireGallery = false,
                selectedAlbums = setOf("primary:camera", "sdcard:camera"),
            ),
        )
    }

    @Test
    fun selectedModeWithNoAlbumsDoesNotBroadenToEntireGallery() {
        val imageAlbumKeys = mapOf("primary:1" to "primary:camera")

        assertEquals(
            emptySet<String>(),
            MediaSources.eligible(imageAlbumKeys, entireGallery = false, selectedAlbums = emptySet()),
        )
    }

    @Test
    fun selectionMatchesVolumeQualifiedKeysRatherThanAlbumNames() {
        val imageAlbumKeys = mapOf(
            "primary:1" to "primary:42",
            "sdcard:1" to "sdcard:42",
        )

        assertEquals(
            setOf("primary:1"),
            MediaSources.eligible(
                imageAlbumKeys,
                entireGallery = false,
                selectedAlbums = setOf("primary:42"),
            ),
        )
    }
}

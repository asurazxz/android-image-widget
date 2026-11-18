package dev.localgallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGroupingTest {
    @Test
    fun duplicateAlbumNamesOnDifferentVolumesRemainSeparate() {
        val groups = AlbumGrouping.group(
            listOf(
                AlbumImageRef(mediaKey = "primary:1", volume = "primary", bucketId = "42", bucketName = "Camera"),
                AlbumImageRef(mediaKey = "primary:2", volume = "primary", bucketId = "42", bucketName = "Camera"),
                AlbumImageRef(mediaKey = "sdcard:1", volume = "sdcard", bucketId = "42", bucketName = "Camera"),
            ),
        )

        assertEquals(2, groups.size)
        assertEquals(setOf("primary:42", "sdcard:42"), groups.map { it.key }.toSet())
        assertEquals(listOf(2, 1), groups.map { it.count })
        assertEquals(setOf("primary:1", "sdcard:1"), groups.map { it.coverKey }.toSet())
    }

    @Test
    fun groupingUsesBucketIdentityRatherThanDisplayName() {
        val groups = AlbumGrouping.group(
            listOf(
                AlbumImageRef(mediaKey = "p:1", volume = "primary", bucketId = "10", bucketName = "Trips"),
                AlbumImageRef(mediaKey = "p:2", volume = "primary", bucketId = "11", bucketName = "Trips"),
            ),
        )

        assertEquals(2, groups.size)
        assertEquals(setOf("primary:10", "primary:11"), groups.map { it.key }.toSet())
        assertTrue(groups.all { it.count == 1 })
    }
}

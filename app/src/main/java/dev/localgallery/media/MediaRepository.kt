package dev.localgallery.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaImage(
    val key: String,
    val uri: Uri,
    val volume: String,
    val bucketId: String,
    val bucketName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

data class MediaAlbum(
    val key: String,
    val name: String,
    val count: Int,
    val cover: MediaImage,
)

internal data class AlbumImageRef(
    val mediaKey: String,
    val volume: String,
    val bucketId: String,
    val bucketName: String,
)

internal data class MediaAlbumGroup(
    val key: String,
    val name: String,
    val count: Int,
    val coverKey: String,
)

internal object AlbumGrouping {
    fun group(images: List<AlbumImageRef>): List<MediaAlbumGroup> = images
        .asSequence()
        .filter { it.volume.isNotBlank() && it.bucketId.isNotBlank() }
        .groupBy { "${it.volume}:${it.bucketId}" }
        .map { (key, members) ->
            val first = members.first()
            MediaAlbumGroup(
                key = key,
                name = first.bucketName.ifBlank { "Unnamed album" },
                count = members.size,
                coverKey = first.mediaKey,
            )
        }
        .sortedWith(compareBy<MediaAlbumGroup> { it.name.lowercase() }.thenBy { it.key })

    fun key(volume: String, bucketId: String): String = "$volume:$bucketId"
}

object MediaSources {
    fun eligible(
        imageAlbumKeys: Map<String, String>,
        entireGallery: Boolean,
        selectedAlbums: Set<String>,
    ): Set<String> {
        if (entireGallery) return imageAlbumKeys.keys.toSet()
        if (selectedAlbums.isEmpty()) return emptySet()

        return imageAlbumKeys.asSequence()
            .filter { (_, albumKey) -> albumKey in selectedAlbums }
            .map { (mediaKey, _) -> mediaKey }
            .toCollection(LinkedHashSet())
    }
}

class MediaRepository internal constructor(
    context: Context,
    private val accessStatus: () -> PhotoAccess,
) {
    constructor(context: Context) : this(
        context,
        PhotoPermissionManager(context.applicationContext)::status,
    )

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun images(): List<MediaImage> = withContext(Dispatchers.IO) {
        requireFullAccess()
        val output = ArrayList<MediaImage>()
        for (volume in MediaStore.getExternalVolumeNames(appContext)) {
            if (accessStatus() != PhotoAccess.FULL) {
                throw SecurityException("Full photo access is required to list images")
            }
            val collection = MediaStore.Images.Media.getContentUri(volume)
            try {
                queryVolume(collection, volume, output)
            } catch (exception: SecurityException) {
                if (accessStatus() != PhotoAccess.FULL) {
                    throw SecurityException("Photo access changed while querying media", exception)
                }
                // A volume can disappear or become unavailable while this query runs.
            } catch (_: IllegalArgumentException) {
                if (accessStatus() != PhotoAccess.FULL) {
                    throw SecurityException("Photo access changed while querying media")
                }
                // A volume can be detached between enumeration and query.
            }
        }
        requireFullAccess()
        output.distinctBy { it.key }
    }

    suspend fun albums(): List<MediaAlbum> = withContext(Dispatchers.IO) {
        val images = images()
        val byKey = images.associateBy { it.key }
        AlbumGrouping.group(images.map { image ->
            AlbumImageRef(
                mediaKey = image.key,
                volume = image.volume,
                bucketId = image.bucketId,
                bucketName = image.bucketName,
            )
        }).mapNotNull { group ->
            val cover = byKey[group.coverKey] ?: return@mapNotNull null
            MediaAlbum(group.key, group.name, group.count, cover)
        }
    }

    private fun queryVolume(collection: Uri, requestedVolume: String, output: MutableList<MediaImage>) {
        val projection = arrayOf(
            MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.MIME_TYPE,
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
            MediaColumns.VOLUME_NAME,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaColumns.IS_PENDING} = 0 AND ${MediaColumns.IS_TRASHED} = 0"
        } else {
            "${MediaColumns.IS_PENDING} = 0"
        }

        resolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.HEIGHT)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            val volumeColumn = cursor.getColumnIndex(MediaColumns.VOLUME_NAME)

            while (cursor.moveToNext()) {
                val volume = volumeColumn.takeIf { it >= 0 }?.let(cursor::getString)
                    ?.takeIf { it.isNotBlank() } ?: requestedVolume
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                output += MediaImage(
                    key = uri.toString(),
                    uri = uri,
                    volume = volume,
                    bucketId = cursor.getString(bucketIdColumn).orEmpty(),
                    bucketName = cursor.getString(bucketNameColumn).orEmpty(),
                    mimeType = cursor.getString(mimeColumn).orEmpty(),
                    width = cursor.getInt(widthColumn).coerceAtLeast(0),
                    height = cursor.getInt(heightColumn).coerceAtLeast(0),
                )
            }
        }
    }

    private fun requireFullAccess() {
        if (accessStatus() != PhotoAccess.FULL) {
            throw SecurityException("Full photo access is required to access the local gallery")
        }
    }
}

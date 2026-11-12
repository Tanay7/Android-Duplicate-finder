package io.github.tanay7.photocleaner.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import io.github.tanay7.photocleaner.core.FolderInfo
import io.github.tanay7.photocleaner.core.MediaKind

/** A photo or video as the phone's media index (MediaStore) describes it. */
class MediaItem(
    val kind: MediaKind,
    val id: Long,
    /** "external_primary" for the phone's own storage, a volume id such as "1a2b-3c4d" for an SD card. */
    val volume: String,
    val name: String,
    /** Folder within the volume, such as "DCIM/Camera" (no slashes at the ends; "" for the top). */
    val folder: String,
    val size: Long,
    /** Seconds since 1970. */
    val modified: Long,
    val added: Long,
    /** Milliseconds since 1970, 0 when unknown. */
    val takenMs: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val mime: String,
    val durationMs: Long,
    val favorite: Boolean,
) {
    val uri: Uri get() = ContentUris.withAppendedId(collection(kind, volume), id)
    /** Stable for the life of the file: the cache key. */
    val key: String get() = "$volume/${if (kind == MediaKind.PHOTO) "i" else "v"}/$id"
    val folderKey: String get() = FolderInfo.keyOf(volume, folder)
    val path: String get() = if (folder.isEmpty()) name else "$folder/$name"
    val shownWidth: Int get() = if (orientation % 180 == 90) height else width
    val shownHeight: Int get() = if (orientation % 180 == 90) width else height

    companion object {
        fun collection(kind: MediaKind, volume: String): Uri = when (kind) {
            MediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(volume)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        }
    }
}

object MediaIndex {
    private val COMMON = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.ORIENTATION,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.IS_FAVORITE,
    )

    /**
     * Every photo and video the app may see, on every storage volume. Items in the trash or still being
     * written are left out by MediaStore itself.
     */
    fun query(ctx: Context, photos: Boolean = true, videos: Boolean = true): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        if (photos) read(ctx, MediaKind.PHOTO, out)
        if (videos) read(ctx, MediaKind.VIDEO, out)
        return out
    }

    private fun read(ctx: Context, kind: MediaKind, out: MutableList<MediaItem>) {
        val projection = if (kind == MediaKind.VIDEO) COMMON + MediaStore.Video.VideoColumns.DURATION else COMMON
        val uri = MediaItem.collection(kind, MediaStore.VOLUME_EXTERNAL)
        ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val col = projection.associateWith { c.getColumnIndexOrThrow(it) }
            fun str(name: String) = c.getString(col.getValue(name)) ?: ""
            fun long(name: String) = if (c.isNull(col.getValue(name))) 0L else c.getLong(col.getValue(name))
            while (c.moveToNext()) {
                val size = long(MediaStore.MediaColumns.SIZE)
                if (size <= 0) continue
                out.add(
                    MediaItem(
                        kind = kind,
                        id = long(MediaStore.MediaColumns._ID),
                        volume = str(MediaStore.MediaColumns.VOLUME_NAME).ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY },
                        name = str(MediaStore.MediaColumns.DISPLAY_NAME),
                        folder = str(MediaStore.MediaColumns.RELATIVE_PATH).trim('/'),
                        size = size,
                        modified = long(MediaStore.MediaColumns.DATE_MODIFIED),
                        added = long(MediaStore.MediaColumns.DATE_ADDED),
                        takenMs = long(MediaStore.MediaColumns.DATE_TAKEN),
                        width = long(MediaStore.MediaColumns.WIDTH).toInt(),
                        height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
                        orientation = long(MediaStore.MediaColumns.ORIENTATION).toInt(),
                        mime = str(MediaStore.MediaColumns.MIME_TYPE),
                        durationMs = if (kind == MediaKind.VIDEO) long(MediaStore.Video.VideoColumns.DURATION) else 0L,
                        favorite = long(MediaStore.MediaColumns.IS_FAVORITE) != 0L,
                    ),
                )
            }
        }
    }

    /** One entry per folder that directly holds photos or videos, with its counts and size. */
    fun folders(items: List<MediaItem>): List<FolderInfo> {
        class Acc(val volume: String, val path: String) {
            var photos = 0
            var videos = 0
            var bytes = 0L
        }
        val map = LinkedHashMap<String, Acc>()
        for (m in items) {
            val a = map.getOrPut(m.folderKey) { Acc(m.volume, m.folder) }
            if (m.kind == MediaKind.PHOTO) a.photos++ else a.videos++
            a.bytes += m.size
        }
        return map.values.map { FolderInfo(it.volume, it.path, it.photos, it.videos, it.bytes) }
    }

    /** "Phone" for the built-in storage, the system's own name (such as "SanDisk SD card") for the others. */
    fun volumeLabels(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return out
        for (v in sm.storageVolumes) {
            val name = v.mediaStoreVolumeName ?: continue
            out[name] = if (v.isPrimary) "Phone" else v.getDescription(ctx) ?: "SD card"
        }
        return out
    }
}

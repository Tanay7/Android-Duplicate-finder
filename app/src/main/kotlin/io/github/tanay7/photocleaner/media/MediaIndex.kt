package io.github.tanay7.photocleaner.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import io.github.tanay7.photocleaner.core.FolderInfo
import io.github.tanay7.photocleaner.core.MediaKind
import java.io.IOException

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
    fun query(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        read(ctx, MediaKind.PHOTO, out)
        read(ctx, MediaKind.VIDEO, out)
        return out
    }

    private fun read(ctx: Context, kind: MediaKind, out: MutableList<MediaItem>) {
        val projection = if (kind == MediaKind.VIDEO) COMMON + MediaStore.Video.VideoColumns.DURATION else COMMON
        val uri = MediaItem.collection(kind, MediaStore.VOLUME_EXTERNAL)
        ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val col = projection.associateWith { c.getColumnIndexOrThrow(it) }
            // Thousands of files share a few folders, volumes and types: one string for each, not one per file.
            val shared = HashMap<String, String>()
            val folders = HashMap<String, String>()
            fun str(name: String) = c.getString(col.getValue(name)) ?: ""
            fun one(name: String) = str(name).let { shared.getOrPut(it) { it } }
            fun long(name: String) = if (c.isNull(col.getValue(name))) 0L else c.getLong(col.getValue(name))
            while (c.moveToNext()) {
                // A file Android hasn't finished indexing can still say 0 bytes: it is listed all the
                // same (the scan reads the real bytes), so a folder never looks empty while it isn't.
                val size = long(MediaStore.MediaColumns.SIZE)
                out.add(
                    MediaItem(
                        kind = kind,
                        id = long(MediaStore.MediaColumns._ID),
                        volume = one(MediaStore.MediaColumns.VOLUME_NAME).ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY },
                        name = str(MediaStore.MediaColumns.DISPLAY_NAME),
                        folder = str(MediaStore.MediaColumns.RELATIVE_PATH).let { raw -> folders.getOrPut(raw) { raw.trim('/') } },
                        size = size,
                        modified = long(MediaStore.MediaColumns.DATE_MODIFIED),
                        added = long(MediaStore.MediaColumns.DATE_ADDED),
                        takenMs = long(MediaStore.MediaColumns.DATE_TAKEN),
                        width = long(MediaStore.MediaColumns.WIDTH).toInt(),
                        height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
                        orientation = long(MediaStore.MediaColumns.ORIENTATION).toInt(),
                        mime = one(MediaStore.MediaColumns.MIME_TYPE),
                        durationMs = if (kind == MediaKind.VIDEO) long(MediaStore.Video.VideoColumns.DURATION) else 0L,
                        favorite = long(MediaStore.MediaColumns.IS_FAVORITE) != 0L,
                    ),
                )
            }
        }
    }

    /**
     * The keys of those [items] still on the phone (not deleted, not in the trash). Checked before
     * moving files and after Android answers, so the app never acts on, or reports, a file that has
     * gone meanwhile. Files on a storage that isn't in the phone (an SD card taken out) count as
     * gone. If a storage that is in the phone can't be asked, this throws rather than call its files
     * gone: whoever asked then knows the answer is missing, not that the files are.
     */
    fun existing(ctx: Context, items: Collection<MediaItem>): Set<String> {
        val mounted = MediaStore.getExternalVolumeNames(ctx)
        val out = HashSet<String>()
        for ((where, list) in items.groupBy { it.kind to it.volume }) {
            if (where.second !in mounted) continue
            val uri = MediaItem.collection(where.first, where.second)
            for (chunk in list.chunked(400)) {
                val byId = chunk.associateBy { it.id }
                val sel = "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { it.id.toString() }})"
                val c = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), sel, null, null)
                    ?: throw IOException("The media index didn't answer for ${where.second}")
                c.use { while (it.moveToNext()) byId[it.getLong(0)]?.let { m -> out.add(m.key) } }
            }
        }
        return out
    }

    /**
     * One entry per folder that directly holds photos or videos, with its counts and size: what the
     * folder list shows. Reads three columns and builds nothing per file, so it stays quick for a
     * hundred thousand photos.
     */
    fun folders(ctx: Context): List<FolderInfo> {
        class Acc(val volume: String, val path: String) {
            var photos = 0
            var videos = 0
            var photoBytes = 0L
            var videoBytes = 0L
        }
        val map = LinkedHashMap<String, Acc>()
        val projection = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE)
        for (kind in MediaKind.entries) {
            ctx.contentResolver.query(MediaItem.collection(kind, MediaStore.VOLUME_EXTERNAL), projection, null, null, null)?.use { c ->
                var lastVolume = ""
                var lastPath = ""
                var last: Acc? = null
                while (c.moveToNext()) {
                    val volume = (c.getString(0) ?: "").ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY }
                    val path = c.getString(1) ?: ""
                    // Rows of one folder mostly come one after the other: no look-up for those.
                    val a = if (last != null && path == lastPath && volume == lastVolume) last else {
                        val folder = path.trim('/')
                        map.getOrPut(FolderInfo.keyOf(volume, folder)) { Acc(volume, folder) }.also {
                            last = it
                            lastVolume = volume
                            lastPath = path
                        }
                    }
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    if (kind == MediaKind.PHOTO) {
                        a.photos++
                        a.photoBytes += size
                    } else {
                        a.videos++
                        a.videoBytes += size
                    }
                }
            }
        }
        return map.values.map { FolderInfo(it.volume, it.path, it.photos, it.videos, it.photoBytes, it.videoBytes) }
    }

    /** The storage volumes that are in the phone right now ("external_primary", an SD card's id). */
    fun volumes(ctx: Context): Set<String> = try {
        MediaStore.getExternalVolumeNames(ctx)
    } catch (_: Exception) {
        setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
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

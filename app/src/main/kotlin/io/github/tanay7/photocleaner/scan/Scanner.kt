package io.github.tanay7.photocleaner.scan

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.tanay7.photocleaner.core.CopyNames
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.Item
import io.github.tanay7.photocleaner.core.MatchOptions
import io.github.tanay7.photocleaner.core.Matcher
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.media.Analyzer
import io.github.tanay7.photocleaner.media.FeatureCache
import io.github.tanay7.photocleaner.media.FeatureRow
import io.github.tanay7.photocleaner.media.MediaIndex
import io.github.tanay7.photocleaner.media.MediaItem
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One scan of the chosen folders:
 *  1. list their photos and videos from the media index;
 *  2. read the photos that the cache doesn't know yet (fingerprint, and pixels if look-alikes, bursts
 *     or poor shots are wanted), and fingerprint the videos that share a size with another one;
 *  3. group them with the [Matcher].
 */
class Scanner(
    private val ctx: Context,
    private val req: ScanRequest,
    private val cancel: AtomicBoolean,
    private val progress: (Stage, Int, Int) -> Unit,
) {
    private val o = req.options

    fun run(): ScanResult {
        val t0 = SystemClock.elapsedRealtime()
        progress(Stage.LISTING, 0, 0)
        val everything = MediaIndex.query(ctx)
        val items = everything.filter {
            it.folderKey in req.folders && (if (it.kind == MediaKind.PHOTO) o.photos else o.videos)
        }
        check()
        val cache = FeatureCache(ctx)
        try {
            val known = cache.load()
            if (req.fullAccess) cache.prune(everything.mapTo(HashSet()) { it.key }, known.keys)
            val rows = arrayOfNulls<FeatureRow>(items.size)
            val wantFeatures = o.similar || o.bursts || o.poor

            // Files that share their size with another one may be identical; the others can't be.
            val sizeCount = HashMap<String, Int>()
            for (m in items) sizeCount.merge("${m.kind}:${m.size}", 1, Int::plus)
            fun mayHaveTwin(m: MediaItem) = (sizeCount["${m.kind}:${m.size}"] ?: 0) >= 2

            val photoTodo = ArrayList<Int>()
            val videoTodo = ArrayList<Int>()
            for ((i, m) in items.withIndex()) {
                val row = known[m.key]?.takeIf { it.matches(m) }
                if (m.kind == MediaKind.PHOTO) {
                    val enough = row != null && row.sha != null && (!wantFeatures || row.features != null)
                    if (enough) rows[i] = row else if (wantFeatures || mayHaveTwin(m)) photoTodo.add(i) else rows[i] = row
                } else {
                    if (row?.sha != null) rows[i] = row else if (mayHaveTwin(m)) videoTodo.add(i)
                }
            }

            readPhotos(items, photoTodo, rows, wantFeatures, cache)
            hashVideos(items, videoTodo, rows, cache)
            check()

            progress(Stage.COMPARING, 0, 0)
            val coreItems = items.mapIndexed { i, m ->
                val r = rows[i]
                Item(
                    kind = m.kind,
                    path = m.path,
                    size = m.size,
                    added = m.added,
                    favorite = m.favorite,
                    original = CopyNames.isOriginalFolder(m.folder),
                    sha = r?.sha,
                    width = r?.width?.takeIf { it > 0 } ?: m.shownWidth,
                    height = r?.height?.takeIf { it > 0 } ?: m.shownHeight,
                    takenAt = r?.takenAt,
                    camera = r?.camera,
                    features = r?.features,
                )
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val groups = Matcher(
                coreItems,
                MatchOptions(similar = o.similar, bursts = o.bursts, poor = o.poor, maxBits = o.maxBits, threads = threads),
            ).run()
            val unreadable = rows.count { it?.error != null && it.sha == null }
            val result = ScanResult(
                items, rows.toList(), groups, o, req.folders.size, unreadable,
                (SystemClock.elapsedRealtime() - t0) / 1000.0,
            )
            log(result)
            return result
        } finally {
            cache.close()
        }
    }

    private fun readPhotos(items: List<MediaItem>, todo: List<Int>, rows: Array<FeatureRow?>, wantFeatures: Boolean, cache: FeatureCache) {
        if (todo.isEmpty()) return
        val cr = ctx.contentResolver
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(threads)
        val done = ExecutorCompletionService<Pair<Int, FeatureRow>>(pool)
        try {
            for (i in todo) {
                done.submit(Callable {
                    if (cancel.get()) throw CancellationException()
                    // A file that needs no pixels (exact copies only) is only fingerprinted.
                    val needPixels = wantFeatures && items[i].kind == MediaKind.PHOTO
                    i to Analyzer.photo(cr, items[i], needPixels)
                })
            }
            val pending = ArrayList<FeatureRow>()
            var last = 0L
            try {
                for (n in 1..todo.size) {
                    val (i, row) = try {
                        done.take().get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                    rows[i] = row
                    pending.add(row)
                    if (pending.size >= 200) {
                        cache.save(pending)
                        pending.clear()
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 150 || n == todo.size) {
                        progress(Stage.READING, n, todo.size)
                        last = now
                    }
                    check()
                }
            } finally {
                // Kept even when the scan is stopped: the next one starts from here.
                cache.save(pending)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun hashVideos(items: List<MediaItem>, todo: List<Int>, rows: Array<FeatureRow?>, cache: FeatureCache) {
        if (todo.isEmpty()) return
        val cr = ctx.contentResolver
        val pending = ArrayList<FeatureRow>()
        try {
            for ((n, i) in todo.withIndex()) {
                check()
                progress(Stage.VIDEOS, n, todo.size)
                val m = items[i]
                val row = try {
                    FeatureRow(m.key, m.size, m.modified, Analyzer.sha256(cr, m), m.shownWidth, m.shownHeight, null, null, null, null)
                } catch (e: Exception) {
                    FeatureRow(m.key, m.size, m.modified, null, m.shownWidth, m.shownHeight, null, null, null, "Can't read: ${e.message}")
                }
                rows[i] = row
                pending.add(row)
            }
            progress(Stage.VIDEOS, todo.size, todo.size)
        } finally {
            cache.save(pending)
        }
    }

    private fun check() {
        if (cancel.get()) throw CancellationException()
    }

    /** One line per scan, plus every group when asked (the on-device test reads these). */
    private fun log(r: ScanResult) {
        val byCat = r.groups.groupingBy { r.category(it) }.eachCount()
        val suggested = r.groups.sumOf { g -> g.members.count { it.suggestTrash } }
        Log.i(
            TAG,
            "RESULT items=${r.items.size} photos=${r.photoCount} videos=${r.videoCount} groups=${r.groups.size} " +
                "identical=${byCat[Category.IDENTICAL] ?: 0} lookalike=${byCat[Category.LOOKALIKE] ?: 0} " +
                "bursts=${byCat[Category.BURSTS] ?: 0} videogroups=${byCat[Category.VIDEOS] ?: 0} poor=${byCat[Category.POOR] ?: 0}" +
                " suggested=$suggested unreadable=${r.unreadable} seconds=${"%.1f".format(r.seconds)}",
        )
        if (req.verbose) {
            for (g in r.groups) Log.i(TAG, "GROUP ${describe(r, g)}")
        }
    }

    private fun describe(r: ScanResult, g: Group): String =
        "${r.category(g).name.lowercase()} " + g.members.joinToString(" ") { m ->
            val tag = when {
                m.keeper -> "KEEP"
                m.suggestTrash -> "TRASH"
                else -> "ASK"
            }
            "$tag:${r.items[m.item].path}"
        }

    companion object {
        const val TAG = "PhotoCleaner"
    }
}

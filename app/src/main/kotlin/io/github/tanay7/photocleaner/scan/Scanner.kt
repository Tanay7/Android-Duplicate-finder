package io.github.tanay7.photocleaner.scan

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.core.CopyNames
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.Item
import io.github.tanay7.photocleaner.core.MatchOptions
import io.github.tanay7.photocleaner.core.Matcher
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Parallelism
import io.github.tanay7.photocleaner.media.Analyzer
import io.github.tanay7.photocleaner.media.FeatureCache
import io.github.tanay7.photocleaner.media.FeatureRow
import io.github.tanay7.photocleaner.media.MediaIndex
import io.github.tanay7.photocleaner.media.MediaItem
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * One scan of the chosen folders:
 *  1. list their photos and videos from the media index;
 *  2. read the photos the cache doesn't know yet (fingerprint, and pixels if look-alikes, bursts or
 *     poor shots are wanted) and fingerprint the videos that share their size with another one, on
 *     every core;
 *  3. group them with the [Matcher], again on every core.
 */
class Scanner(
    private val ctx: Context,
    private val req: ScanRequest,
    private val cancel: AtomicBoolean,
    private val progress: (Stage, Int, Int, Int) -> Unit,
) {
    private val o = req.options
    private val cr = ctx.contentResolver
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var threadsUsed = 1

    fun run(): ScanResult {
        val t0 = SystemClock.elapsedRealtime()
        progress(Stage.LISTING, 0, 0, 1)
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

            onEveryCore(Stage.READING, photoTodo, items, rows, cache) { i, w -> Analyzer.photo(cr, items[i], wantFeatures, w) }
            onEveryCore(Stage.VIDEOS, videoTodo, items, rows, cache) { i, w -> Analyzer.video(cr, items[i], w) }
            check()

            progress(Stage.COMPARING, 0, 0, cores)
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
            val groups = Matcher(
                coreItems,
                MatchOptions(similar = o.similar, bursts = o.bursts, poor = o.poor, maxBits = o.maxBits, threads = cores),
            ).run()
            check()
            val unreadable = rows.count { it?.error != null && it.sha == null }
            val result = ScanResult(
                items, rows.toList(), groups, o, req.folders.size, unreadable,
                (SystemClock.elapsedRealtime() - t0) / 1000.0, threadsUsed, Prefs.overrides(ctx),
            )
            log(result)
            return result
        } finally {
            cache.close()
        }
    }

    /** One thread per core, as far as free memory allows (Lean Reader's rule). */
    private fun threadCount(jobs: Int): Int {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return minOf(jobs, Parallelism.threads(cores, mi.availMem - mi.threshold, freeHeap)).coerceAtLeast(1)
    }

    private class Done(val k: Int, val row: FeatureRow?)

    /**
     * Runs [work] for every index of [todo] on one thread per core. Files are handed out one at a
     * time from a shared counter, so on a phone with fast and slow cores (big.LITTLE) every core keeps
     * working until the last file, the fast ones taking more. Each thread has its own buffers.
     *
     * This thread meanwhile keeps the cache, reports progress and watches for trouble:
     *  - a file that ran out of memory, couldn't be read or couldn't be decoded is tried again at the
     *    end, on its own, with the other threads done (a decoder can fail only because many ran at once);
     *  - a file that hangs (a failing SD card can block a read forever) is given up after
     *    [Parallelism.stuckAfterMs], so the scan always ends;
     *  - when the phone gets hot, workers rest until it cools down, so the scan slows instead of
     *    making Android throttle the whole phone.
     */
    private fun onEveryCore(
        stage: Stage, todo: List<Int>, items: List<MediaItem>, rows: Array<FeatureRow?>, cache: FeatureCache,
        work: (Int, Analyzer.Worker) -> FeatureRow,
    ) {
        val n = todo.size
        if (n == 0) return
        val threads = threadCount(n)
        threadsUsed = maxOf(threadsUsed, threads)
        val next = AtomicInteger()
        val allowed = AtomicInteger(threads)
        val results = LinkedBlockingQueue<Done>()
        val current = AtomicIntegerArray(threads).apply { for (w in 0 until threads) set(w, -1) }
        val since = AtomicLongArray(threads)
        val stop = AtomicBoolean()
        for (w in 0 until threads) {
            Thread({
                // Normal priority, a notch below the screen's: low enough to keep the phone smooth, high
                // enough not to be confined to the slow cores the way background threads can be.
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
                val worker = Analyzer.Worker()
                while (!cancel.get() && !stop.get()) {
                    if (w >= allowed.get()) {
                        SystemClock.sleep(300)          // the phone is hot: this one rests
                        continue
                    }
                    val k = next.getAndIncrement()
                    if (k >= n) break
                    since.set(w, SystemClock.elapsedRealtime())
                    current.set(w, k)
                    // Nothing may escape a worker: an uncaught error on any thread ends the whole app.
                    val row = try {
                        work(todo[k], worker)
                    } catch (_: OutOfMemoryError) {
                        null
                    } catch (e: Throwable) {
                        Analyzer.failed(items[todo[k]], "Failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    current.set(w, -1)
                    results.put(Done(k, row))
                }
            }, "scan-$w").apply {
                isDaemon = true
                start()
            }
        }

        val retry = ArrayList<Int>()
        val given = BooleanArray(n)
        var received = 0
        var givenUp = 0
        val pending = ArrayList<FeatureRow>()
        var lastProgress = 0L
        var lastSave = SystemClock.elapsedRealtime()
        var lastHeat = 0L
        var heat = 0
        val power = ctx.getSystemService(PowerManager::class.java)
        try {
            while (received + givenUp < n) {
                check()
                val d = results.poll(250, TimeUnit.MILLISECONDS)
                if (d != null && !given[d.k]) {
                    received++
                    val row = d.row
                    if (row == null || retryable(row)) {
                        retry.add(d.k)
                    } else {
                        rows[todo[d.k]] = row
                        pending.add(row)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                var stuck = 0
                for (w in 0 until threads) {
                    val k = current.get(w)
                    if (k < 0) continue
                    if (!given[k] && now - since.get(w) > Parallelism.stuckAfterMs(items[todo[k]].size)) {
                        given[k] = true
                        givenUp++
                        rows[todo[k]] = Analyzer.failed(items[todo[k]], "Timed out: the file couldn't be read")
                        Log.w(TAG, "Gave up on ${items[todo[k]].path}")
                    }
                    if (given[k]) stuck++
                }
                if (now - lastHeat > 2000) {
                    heat = power?.currentThermalStatus ?: 0
                    lastHeat = now
                }
                // A thread stuck on a dead file doesn't count: another one takes its place.
                allowed.set(minOf(threads, Parallelism.allowed(threads, heat) + stuck))
                if (pending.size >= 200 || (pending.isNotEmpty() && now - lastSave > 3000)) {
                    cache.save(pending)
                    pending.clear()
                    lastSave = now
                }
                if (now - lastProgress > 150 || received + givenUp == n) {
                    progress(stage, received + givenUp, n, allowed.get())
                    lastProgress = now
                }
            }
        } finally {
            stop.set(true)
            // Kept even when the scan is stopped: the next one starts from here.
            cache.save(pending)
            pending.clear()
        }

        // The second try, one file at a time, with the other threads finished.
        if (retry.isEmpty()) return
        Log.i(TAG, "Trying ${retry.size} files again on their own")
        val lane = Analyzer.Worker()
        try {
            for (k in retry) {
                check()
                val i = todo[k]
                val row = try {
                    work(i, lane)
                } catch (_: OutOfMemoryError) {
                    Analyzer.failed(items[i], "Too large to read")
                } catch (e: Throwable) {
                    Analyzer.failed(items[i], "Failed: ${e.message ?: e.javaClass.simpleName}")
                }
                rows[i] = row
                pending.add(row)
            }
        } finally {
            cache.save(pending)
        }
    }

    /** Worth a second try on its own: it couldn't be read or decoded (the cause may have been the crowd). */
    private fun retryable(row: FeatureRow) =
        row.error != null && (row.sha == null || row.error.startsWith(Analyzer.CANT_DECODE))

    private fun check() {
        if (cancel.get()) throw CancellationException()
    }

    /** One line per scan, plus every group when asked (the on-device test reads these). */
    private fun log(r: ScanResult) {
        val byCat = r.groups.groupingBy { r.category(it) }.eachCount()
        val suggested = r.groups.sumOf { g -> g.members.count { it.suggestTrash } }
        val undecodable = r.rows.count { it?.error?.startsWith(Analyzer.CANT_DECODE) == true }
        Log.i(
            TAG,
            "RESULT items=${r.items.size} photos=${r.photoCount} videos=${r.videoCount} groups=${r.groups.size} " +
                "identical=${byCat[Category.IDENTICAL] ?: 0} lookalike=${byCat[Category.LOOKALIKE] ?: 0} " +
                "bursts=${byCat[Category.BURSTS] ?: 0} videogroups=${byCat[Category.VIDEOS] ?: 0} poor=${byCat[Category.POOR] ?: 0}" +
                " suggested=$suggested unreadable=${r.unreadable} undecodable=$undecodable" +
                " threads=${r.threads} cores=$cores seconds=${"%.1f".format(r.seconds)}",
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

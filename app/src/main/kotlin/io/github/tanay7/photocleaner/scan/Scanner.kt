package io.github.tanay7.photocleaner.scan

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.core.Budget
import io.github.tanay7.photocleaner.core.CopyNames
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.Item
import io.github.tanay7.photocleaner.core.Journal
import io.github.tanay7.photocleaner.core.MatchOptions
import io.github.tanay7.photocleaner.core.Matcher
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Parallelism
import io.github.tanay7.photocleaner.media.Analyzer
import io.github.tanay7.photocleaner.media.FeatureCache
import io.github.tanay7.photocleaner.media.FeatureRow
import io.github.tanay7.photocleaner.media.MediaIndex
import io.github.tanay7.photocleaner.media.MediaItem
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

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
    private val progress: (Stage, Int, Int, Int) -> Unit,
) {
    private val o = req.options
    private val cancel = req.cancel
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var threadsUsed = 1

    // One scan's working state.
    private lateinit var items: List<MediaItem>
    private lateinit var rows: Array<FeatureRow?>
    private lateinit var cache: FeatureCache
    private lateinit var reading: Analyzer.Reading
    private var journal: Journal? = null
    private var suspects: Set<String> = emptySet()
    private var quarantined = 0
    /**
     * Every reading thread of the scan has a journal slot of its own, never used again: a thread
     * that hangs on a file may come back much later, and must not end the note of another one.
     */
    private val journalSlots = AtomicInteger()

    fun run(): ScanResult {
        val t0 = SystemClock.elapsedRealtime()
        progress(Stage.LISTING, 0, 0, 1)
        val everything = MediaIndex.query(ctx)
        items = everything.filter {
            it.folderKey in req.folders && (if (it.kind == MediaKind.PHOTO) o.photos else o.videos)
        }
        check()
        val original = ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            Analyzer.givesOriginals(ctx.contentResolver, items)
        reading = Analyzer.Reading(ctx.contentResolver, original, Budget(decodeBudget()))
        cache = FeatureCache(ctx)
        val journalFile = File(ctx.filesDir, "in-flight")
        try {
            val known = cache.load()
            val liveKeys = everything.mapTo(HashSet()) { it.key }
            // With access to every photo, what the media index no longer lists is gone, on the
            // volumes that are in the phone (an SD card that is out keeps what is known about it).
            val volumes = if (req.fullAccess) MediaIndex.volumes(ctx) else emptySet()
            if (req.fullAccess) {
                cache.prune(liveKeys, known.keys, volumes)
                Prefs.pruneKeeps(ctx, liveKeys, volumes)
            }
            afterACrash(journalFile, everything, liveKeys, volumes, known, original)
            journal = Journal.open(journalFile)

            rows = arrayOfNulls(items.size)
            val wantFeatures = o.similar || o.bursts || o.poor

            // Files that share their size with another one may be identical; the others can't be.
            val sizeCount = HashMap<String, Int>()
            for (m in items) sizeCount.merge("${m.kind}:${m.size}", 1, Int::plus)
            fun mayHaveTwin(m: MediaItem) = (sizeCount["${m.kind}:${m.size}"] ?: 0) >= 2

            val photoTodo = ArrayList<Int>()
            val photoAlone = ArrayList<Int>()
            val videoTodo = ArrayList<Int>()
            val videoAlone = ArrayList<Int>()
            val cleared = HashSet<String>()
            for ((i, m) in items.withIndex()) {
                val row = known[m.key]?.takeIf { it.matches(m, original) }
                if (row?.error == Analyzer.QUARANTINED) {
                    rows[i] = row
                    quarantined++
                    continue
                }
                val photo = m.kind == MediaKind.PHOTO
                // A photo that couldn't be decoded isn't tried again until it changes.
                val done = row?.sha != null &&
                    (!photo || !wantFeatures || row.features != null || row.error?.startsWith(Analyzer.CANT_DECODE) == true)
                if (done || !((photo && wantFeatures) || mayHaveTwin(m))) {
                    rows[i] = row
                    if (done && m.key in suspects) cleared.add(m.key)      // read since, without trouble
                } else if (m.key in suspects) {
                    (if (photo) photoAlone else videoAlone).add(i)
                } else {
                    (if (photo) photoTodo else videoTodo).add(i)
                }
            }

            if (cleared.isNotEmpty()) {
                suspects = suspects - cleared
                Prefs.setSuspects(ctx, suspects)
            }

            readAll(Stage.READING, photoTodo, photoAlone) { i, w -> Analyzer.photo(reading, items[i], wantFeatures, w) }
            readAll(Stage.VIDEOS, videoTodo, videoAlone) { i, w -> Analyzer.video(reading, items[i], w) }
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
                    raw = CopyNames.isRaw(m.name) || m.mime.contains("raw") || m.mime.endsWith("dng"),
                    sha = r?.sha,
                    width = r?.width?.takeIf { it > 0 } ?: m.shownWidth,
                    height = r?.height?.takeIf { it > 0 } ?: m.shownHeight,
                    takenAt = r?.takenAt,
                    camera = r?.camera,
                    features = r?.features,
                )
            }
            var lastTold = 0L
            val groups = Matcher(
                coreItems,
                MatchOptions(
                    similar = o.similar, bursts = o.bursts, poor = o.poor, maxBits = o.maxBits, threads = cores,
                    cancelled = { cancel.get() },
                    progress = { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTold > 200 || done == total) {
                            lastTold = now
                            progress(Stage.COMPARING, done, total, cores)
                        }
                    },
                ),
            ).run()
            check()
            val unreadable = rows.count { it?.error != null && it.sha == null }
            val result = ScanResult(
                items, rows.toList(), groups, o, req.folders.size, unreadable,
                (SystemClock.elapsedRealtime() - t0) / 1000.0, threadsUsed, Prefs.keeps(ctx), Prefs.ticks(ctx),
            )
            log(result, original)
            return result
        } finally {
            journal?.close()
            cache.close()
        }
    }

    /** Half of what the system can still give, for decoders to share; at least enough for one ordinary photo each. */
    private fun decodeBudget(): Long {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        return maxOf((mi.availMem - mi.threshold) / 2, 256L shl 20)
    }

    /** One thread per core, as far as free memory allows (Lean Reader's rule). */
    private fun threadCount(jobs: Int): Int {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return minOf(jobs, Parallelism.threads(cores, mi.availMem - mi.threshold, freeHeap)).coerceAtLeast(1)
    }

    // -- a file that makes the app die ---------------------------------------------------------------

    /**
     * Looks at what the last scan left in the [Journal]. Files that were being read alongside others
     * become suspects: this scan reads them one at a time. A file that was being read on its own when
     * the app died of a crash is the culprit: it is left out from now on (until it changes).
     */
    private fun afterACrash(
        file: File, everything: List<MediaItem>, liveKeys: Set<String>, volumes: Set<String>, known: HashMap<String, FeatureRow>, original: Boolean,
    ) {
        val left = Journal.read(file)
        var now = Prefs.suspects(ctx)
        if (!left.isEmpty) {
            val culprits = if (diedOfACrash(file)) left.alone else emptySet()
            if (culprits.isNotEmpty()) {
                val bad = everything.filter { it.key in culprits }
                val marks = bad.map { Analyzer.quarantined(it, original) }
                cache.save(marks)
                for (r in marks) known[r.key] = r
                Log.w(TAG, "Leaving out ${bad.size} file(s) that stopped the app: ${bad.map { it.path }}")
            }
            now = now + left.together + left.alone - culprits
        }
        now = now.filterTo(HashSet()) { it in liveKeys || it.substringBefore('/') !in volumes }
        suspects = now
        Prefs.setSuspects(ctx, now)
    }

    /** True if the app's last death was a crash (in a decoder, or out of memory), not the user or the system closing it. */
    private fun diedOfACrash(journal: File): Boolean = try {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val last = am?.getHistoricalProcessExitReasons(ctx.packageName, 0, 1)?.firstOrNull()
        Log.i(TAG, "The last scan didn't end in order; the app's last exit: reason ${last?.reason}, status ${last?.status}")
        last != null && last.timestamp >= journal.lastModified() - 5_000 && last.reason in CRASHES
    } catch (_: Exception) {
        false
    }

    // -- reading on every core -----------------------------------------------------------------------

    /**
     * Reads [todo] on every core, then on a single thread the files that failed the first time (out
     * of memory, unreadable, undecodable: a decoder can fail only because many ran at once) and the
     * crash suspects in [alone].
     */
    private fun readAll(stage: Stage, todo: List<Int>, alone: List<Int>, work: (Int, Analyzer.Worker) -> FeatureRow) {
        val total = todo.size + alone.size
        if (total == 0) return
        val again = pass(stage, todo, false, 0, total, work)
        check()
        pass(stage, again + alone, true, todo.size - again.size, total, work)
    }

    private class Done(val k: Int, val row: FeatureRow?)

    /**
     * Runs [work] for every file of [todo] and returns those to try again on their own.
     *
     * Files are handed out one at a time from a shared counter, so on a phone with fast and slow
     * cores (big.LITTLE) every core keeps working until the last file, the fast ones taking more.
     * Each thread has its own buffers.
     *
     * This thread meanwhile keeps the cache, reports progress and watches for trouble:
     *  - a file that hangs (a failing SD card can block a read forever) is given up after
     *    [Parallelism.stuckAfterMs] and a spare thread takes the place of the one it holds; when every
     *    thread is held, the storage has stopped answering and the rest is given up too, so a scan
     *    always ends;
     *  - when the phone gets hot, threads rest until it cools down, so the scan slows instead of
     *    making Android throttle the whole phone;
     *  - nothing thrown on a reading thread escapes it (an uncaught error on any thread ends the app).
     *
     * With [alone], one thread reads one file at a time and failures are final.
     */
    private fun pass(stage: Stage, todo: List<Int>, alone: Boolean, before: Int, total: Int, work: (Int, Analyzer.Worker) -> FeatureRow): List<Int> {
        val n = todo.size
        if (n == 0) return emptyList()
        val threads = if (alone) 1 else threadCount(n)
        threadsUsed = maxOf(threadsUsed, threads)
        val slots = threads + SPARES
        val next = AtomicInteger()
        val working = AtomicInteger()
        val allowed = AtomicInteger(threads)
        val alive = AtomicInteger()
        val started = AtomicInteger()
        val results = LinkedBlockingQueue<Done>()
        val current = AtomicIntegerArray(slots).apply { for (w in 0 until slots) set(w, -1) }
        val since = AtomicLongArray(slots)
        val workers = AtomicReferenceArray<Analyzer.Worker?>(slots)
        val noted = AtomicIntegerArray(slots)
        val stop = AtomicBoolean()

        fun spawn(): Boolean {
            val w = started.getAndIncrement()
            if (w >= slots) return false
            val note = journalSlots.getAndIncrement()
            noted.set(w, note)
            alive.incrementAndGet()
            return try {
                Thread({
                    try {
                        // Normal priority, a notch below the screen's: low enough to keep the phone smooth,
                        // high enough not to be confined to the slow cores the way background threads can be.
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
                        val worker = Analyzer.Worker(stop) { since.set(w, SystemClock.elapsedRealtime()) }
                        workers.set(w, worker)
                        while (!stop.get()) {
                            // A place among the threads allowed to work: fewer while the phone is hot.
                            val busy = working.get()
                            if (busy >= allowed.get()) {
                                SystemClock.sleep(200)
                                continue
                            }
                            if (!working.compareAndSet(busy, busy + 1)) continue
                            try {
                                val k = next.getAndIncrement()
                                if (k >= n) return@Thread
                                val i = todo[k]
                                since.set(w, SystemClock.elapsedRealtime())
                                current.set(w, k)
                                journal?.begin(note, items[i].key, alone)
                                if (debuggable && items[i].name == CRASH_TEST) {
                                    // Stands in for a decoder crashing, in test builds only.
                                    Log.i(TAG, "TEST dying while reading ${items[i].path}")
                                    Process.killProcess(Process.myPid())
                                    SystemClock.sleep(60_000)
                                }
                                val row = try {
                                    work(i, worker)
                                } catch (_: OutOfMemoryError) {
                                    null
                                } catch (_: CancellationException) {
                                    return@Thread
                                } catch (e: Throwable) {
                                    Analyzer.failed(items[i], reading, "Failed: ${e.message ?: e.javaClass.simpleName}")
                                } finally {
                                    journal?.end(note)
                                    current.set(w, -1)
                                }
                                results.put(Done(k, row))
                            } finally {
                                working.decrementAndGet()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "A reading thread ended early", t)
                    } finally {
                        alive.decrementAndGet()
                        results.offer(Done(-1, null))     // wakes the watcher
                    }
                }, "scan-$w").apply { isDaemon = true }.start()
                true
            } catch (t: Throwable) {
                alive.decrementAndGet()
                Log.w(TAG, "Couldn't start a reading thread", t)
                false
            }
        }
        var startedOk = 0
        repeat(threads) { if (spawn()) startedOk++ }
        if (startedOk == 0) throw IllegalStateException("No reading thread could be started")

        val again = ArrayList<Int>()
        val cleared = HashSet<String>()
        val got = BooleanArray(n)
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
                if (d != null && d.k >= 0 && !given[d.k] && !got[d.k]) {
                    got[d.k] = true
                    received++
                    val i = todo[d.k]
                    if (!alone && (d.row == null || retryable(d.row))) {
                        again.add(i)
                    } else {
                        val row = d.row ?: Analyzer.failed(items[i], reading, "Too large to read")
                        rows[i] = row
                        if (row.sha != null && items[i].size > 0) pending.add(row)
                        if (alone) {
                            // Kept at once: if a later file of this pass stops the app, these aren't read again.
                            cleared.add(items[i].key)
                            cache.save(pending)
                            pending.clear()
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                var stuck = 0
                for (w in 0 until minOf(started.get(), slots)) {
                    val k = current.get(w)
                    if (k < 0) continue
                    if (!given[k] && !got[k] && now - since.get(w) > Parallelism.stuckAfterMs(items[todo[k]].size)) {
                        given[k] = true
                        givenUp++
                        rows[todo[k]] = Analyzer.failed(items[todo[k]], reading, "Timed out: the file couldn't be read")
                        if (alone) cleared.add(items[todo[k]].key)
                        Log.w(TAG, "Gave up on ${items[todo[k]].path}")
                        // The thread may never come back. The memory its decoder holds is lost to the
                        // others (or they would wait for it forever), and the file is no longer in
                        // flight: it isn't to blame if another one stops the app later.
                        workers.get(w)?.held?.getAndSet(0)?.let { reading.budget.writeOff(it) }
                        journal?.end(noted.get(w))
                        spawn()         // a spare takes the place of the thread this file holds
                    }
                    if (given[k]) stuck++
                }
                if (received + givenUp >= n) break
                if (alive.get() - stuck <= 0 && results.isEmpty()) {
                    // No thread is left to read the rest: the storage has stopped answering.
                    var lost = 0
                    for (k in 0 until n) {
                        if (got[k] || given[k]) continue
                        rows[todo[k]] = Analyzer.failed(items[todo[k]], reading, "Not read: the storage stopped answering")
                        lost++
                    }
                    Log.w(TAG, "Gave up on $lost files: no thread left to read them")
                    break
                }
                if (now - lastHeat > 2000) {
                    heat = power?.currentThermalStatus ?: 0
                    lastHeat = now
                }
                // A thread held by a dead file doesn't count: another one works in its place.
                allowed.set(Parallelism.allowed(threads, heat) + stuck)
                if (pending.size >= 200 || (pending.isNotEmpty() && now - lastSave > 3000)) {
                    cache.save(pending)
                    pending.clear()
                    lastSave = now
                }
                if (now - lastProgress > 150) {
                    progress(stage, before + received + givenUp, total, minOf(threads, Parallelism.allowed(threads, heat)))
                    lastProgress = now
                }
            }
            progress(stage, before + received + givenUp, total, threads)
        } finally {
            stop.set(true)
            // Kept even when the scan is stopped: the next one starts from here.
            cache.save(pending)
            if (cleared.isNotEmpty()) {
                suspects = suspects - cleared
                Prefs.setSuspects(ctx, suspects)
            }
        }
        return again
    }

    /** Worth a second try on its own: it couldn't be read or decoded (the cause may have been the crowd). */
    private fun retryable(row: FeatureRow) =
        row.error != null && (row.sha == null || row.error.startsWith(Analyzer.CANT_DECODE))

    private fun check() {
        if (cancel.get()) throw CancellationException()
    }

    /** One line per scan, plus every group when asked (the on-device test reads these). */
    private fun log(r: ScanResult, original: Boolean) {
        val byCat = r.groups.groupingBy { r.category(it) }.eachCount()
        val suggested = r.groups.sumOf { g -> g.members.count { it.suggestTrash } }
        val undecodable = r.rows.count { it?.error?.startsWith(Analyzer.CANT_DECODE) == true }
        Log.i(
            TAG,
            "RESULT items=${r.items.size} photos=${r.photoCount} videos=${r.videoCount} groups=${r.groups.size} " +
                "identical=${byCat[Category.IDENTICAL] ?: 0} lookalike=${byCat[Category.LOOKALIKE] ?: 0} " +
                "bursts=${byCat[Category.BURSTS] ?: 0} videogroups=${byCat[Category.VIDEOS] ?: 0} poor=${byCat[Category.POOR] ?: 0}" +
                " suggested=$suggested unreadable=${r.unreadable} undecodable=$undecodable quarantined=$quarantined" +
                " original=${if (original) 1 else 0} threads=${r.threads} cores=$cores seconds=${"%.1f".format(Locale.ROOT, r.seconds)}",
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
        /** Threads kept in reserve to take the place of ones held by a file that hangs. */
        private const val SPARES = 4
        /** In test builds, reading a file of this name ends the app the way a crashing decoder would. */
        const val CRASH_TEST = "photocleaner-crash-test.jpg"
        private val CRASHES = setOf(
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        )
    }
}

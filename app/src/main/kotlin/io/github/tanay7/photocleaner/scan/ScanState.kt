package io.github.tanay7.photocleaner.scan

import android.os.Handler
import android.os.Looper
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.GroupType
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.media.FeatureRow
import io.github.tanay7.photocleaner.media.MediaItem
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** What to look for. [maxBits] is the look-alike cut-off: 4 strict, 8 normal, 12 loose. */
data class ScanOptions(
    val photos: Boolean = true,
    val videos: Boolean = true,
    val similar: Boolean = true,
    val bursts: Boolean = true,
    val poor: Boolean = true,
    val maxBits: Int = 8,
)

class ScanRequest(
    val folders: Set<String>,
    val options: ScanOptions,
    val fullAccess: Boolean,
    val verbose: Boolean,
    /** Bringing back the results of the last scan after Android closed the app. */
    val restoring: Boolean = false,
) {
    /** Set to stop this scan. */
    val cancel = AtomicBoolean()
    /** Android's daily limit for work in the background ran out: the scan was stopped for that. */
    @Volatile var timedOut = false
}

enum class Stage { LISTING, READING, VIDEOS, COMPARING }

/**
 * A finished scan, and what the user has done with it since: which files are ticked for the trash
 * and which have been moved there. Only touched on the main thread once published.
 */
class ScanResult(
    val items: List<MediaItem>,
    val rows: List<FeatureRow?>,
    val groups: List<Group>,
    val options: ScanOptions,
    val folders: Int,
    val unreadable: Int,
    val seconds: Double,
    /** Threads that read the files at once: one per core, as far as memory allowed. */
    val threads: Int,
    /** Files kept by hand before (by key): never ticked, whatever is suggested. */
    keeps: Set<String> = emptySet(),
    /** Files ticked by hand in these results before the app was closed (by key). */
    ticks: Set<String> = emptySet(),
) {
    val selected = HashSet<Int>()
    val trashed = HashSet<Int>()
    private val suggested = HashSet<Int>()

    init {
        for (g in groups) for (m in g.members) {
            if (m.suggestTrash) suggested.add(m.item)
            val key = items[m.item].key
            if (key !in keeps && (m.suggestTrash || key in ticks)) selected.add(m.item)
        }
    }

    /** What ticking by hand changes in the saved choices: for each file key, added (true) or taken out. */
    class Choice(val keep: Map<String, Boolean>, val tick: Map<String, Boolean>)

    /** Ticks or unticks files by hand (null: back to the suggestion) and returns what to remember. */
    fun tick(changes: Map<Int, Boolean?>): Choice {
        val keep = HashMap<String, Boolean>()
        val tick = HashMap<String, Boolean>()
        for ((item, on) in changes) {
            val suggestion = item in suggested
            val want = on ?: suggestion
            if (want) selected.add(item) else selected.remove(item)
            val key = items[item].key
            keep[key] = suggestion && !want
            tick[key] = !suggestion && want
        }
        return Choice(keep, tick)
    }

    /**
     * After files have gone (to the trash, or deleted by another app): a group with a single file
     * left has nothing to compare any more and is no longer shown, so that file must not stay ticked
     * where nobody can see it. Nor must the last copy that is left of one version of a picture (of an
     * edit, say): it was ticked because a better copy of it stayed. Returns the files it unticked.
     */
    fun settle(): List<Int> {
        val out = ArrayList<Int>()
        for (g in groups) {
            if (g.type == GroupType.POOR) continue
            val live = live(g)
            if (live.size == 1) {
                if (selected.remove(live[0].item)) out.add(live[0].item)
                continue
            }
            for ((version, left) in live.groupBy { it.version }) {
                // A version that never had a second copy was ticked by hand, on purpose.
                if (left.size == 1 && g.members.count { it.version == version } > 1 && selected.remove(left[0].item)) out.add(left[0].item)
            }
        }
        return out
    }

    /**
     * True if trashing what is ticked would leave no copy of a picture: every file that is left of
     * the group is ticked, or every copy of one of its versions (the one to keep ticked by hand).
     */
    fun everyCopyTicked(g: Group): Boolean {
        val live = live(g)
        if (live.all { it.item in selected }) return true
        return live.groupBy { it.version }.values.any { v -> v.size > 1 && v.all { it.item in selected } }
    }

    /** The members still on the phone. */
    fun live(g: Group) = g.members.filter { it.item !in trashed }

    /** Groups that still have something to decide: two or more files left (or any poor shot). */
    fun shown(g: Group): Boolean = live(g).size >= (if (g.type == GroupType.POOR) 1 else 2)

    fun category(g: Group): Category = when {
        g.type == GroupType.POOR -> Category.POOR
        g.kind == MediaKind.VIDEO -> Category.VIDEOS
        g.type == GroupType.BURST -> Category.BURSTS
        g.type == GroupType.IDENTICAL -> Category.IDENTICAL
        else -> Category.LOOKALIKE
    }

    val photoCount = items.count { it.kind == MediaKind.PHOTO }
    val videoCount = items.size - photoCount
}

enum class Category(val label: String) {
    ALL("All"), IDENTICAL("Identical"), LOOKALIKE("Look-alike"), BURSTS("Bursts"), VIDEOS("Videos"), POOR("Blurry or dark")
}

/**
 * Asks for [items] in batches of at most [size], one batch after the other, through [ask], which
 * answers true when its batch was allowed. Stops at the first batch refused; [done] gets the items of
 * the batches allowed before it. (Moving files to the trash and back: Android's question carries
 * its list of files in one message between apps, and such a message has a size limit.)
 */
fun <T> inBatches(items: List<T>, size: Int, ask: (List<T>, (Boolean) -> Unit) -> Unit, done: (List<T>) -> Unit) {
    val batches = items.chunked(size)
    val allowed = ArrayList<T>(items.size)
    fun next(k: Int) {
        if (k == batches.size) {
            done(allowed)
            return
        }
        ask(batches[k]) { ok ->
            if (ok) {
                allowed.addAll(batches[k])
                next(k + 1)
            } else {
                done(allowed)
            }
        }
    }
    next(0)
}

/** The one scan of the app, shared by the service that runs it and the screens that show it. */
object ScanState {
    sealed interface Status
    data object Idle : Status
    class Running(val stage: Stage, val done: Int, val total: Int, val threads: Int = 1) : Status
    class Done(val result: ScanResult) : Status
    class Failed(val message: String) : Status

    @Volatile var status: Status = Idle
        private set
    @Volatile var request: ScanRequest? = null
        private set
    /**
     * A scan has been asked for and hasn't wound up yet: the next one must wait. (For a moment after
     * [status] says a scan is over, its thread is still letting go.)
     */
    @Volatile var busy = false
        private set
    /** True while a screen of the app is showing: the "scan finished" notification is only needed otherwise. */
    @Volatile var uiVisible = false

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val running: Boolean get() = status is Running
    val result: ScanResult? get() = (status as? Done)?.result

    /** A scan is asked for (main thread). */
    fun begin(req: ScanRequest) {
        request = req
        busy = true
        post(Running(Stage.LISTING, 0, 0))
    }

    /** How far the scan of [req] is; ignored once another scan has taken its place. */
    fun progress(req: ScanRequest, s: Running) {
        if (request === req && busy) post(s)
    }

    /** The scan of [req] is over and has let go of everything (main thread). */
    fun end(req: ScanRequest, s: Status) {
        if (request !== req) return
        busy = false
        post(s)
    }

    fun cancel() {
        request?.cancel?.set(true)
    }

    private fun post(s: Status) {
        status = s
        main.post { for (l in listeners) l(s) }
    }

    /** Calls [l] on the main thread on every change; returns the call that stops it. */
    fun listen(l: (Status) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }
}

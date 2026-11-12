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
)

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
    /** Choices made by hand before (file key to ticked), which win over the suggestions. */
    overrides: Map<String, Boolean> = emptyMap(),
) {
    val selected = HashSet<Int>()
    val trashed = HashSet<Int>()
    private val suggested = HashSet<Int>()

    init {
        for (g in groups) for (m in g.members) {
            if (m.suggestTrash) suggested.add(m.item)
            when (overrides[items[m.item].key]) {
                true -> selected.add(m.item)
                false -> Unit
                null -> if (m.suggestTrash) selected.add(m.item)
            }
        }
    }

    /**
     * Ticks or unticks files by hand and remembers it (null: back to the suggestion); returns the
     * choices to save, by file key: null where the choice now matches the suggestion.
     */
    fun tick(changes: Map<Int, Boolean?>): Map<String, Boolean?> {
        val save = HashMap<String, Boolean?>()
        for ((item, on) in changes) {
            val want = on ?: (item in suggested)
            if (want) selected.add(item) else selected.remove(item)
            save[items[item].key] = if (on == null || want == (item in suggested)) null else want
        }
        return save
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

    val photoCount get() = items.count { it.kind == MediaKind.PHOTO }
    val videoCount get() = items.count { it.kind == MediaKind.VIDEO }
}

enum class Category(val label: String) {
    ALL("All"), IDENTICAL("Identical"), LOOKALIKE("Look-alike"), BURSTS("Bursts"), VIDEOS("Videos"), POOR("Blurry or dark")
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
    val cancel = AtomicBoolean()
    /** True while a screen of the app is showing: the "scan finished" notification is only needed otherwise. */
    @Volatile var uiVisible = false

    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val running: Boolean get() = status is Running
    val result: ScanResult? get() = (status as? Done)?.result

    fun post(s: Status) {
        status = s
        main.post { for (l in listeners) l(s) }
    }

    /** Calls [l] on the main thread on every change; returns the call that stops it. */
    fun listen(l: (Status) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }
}

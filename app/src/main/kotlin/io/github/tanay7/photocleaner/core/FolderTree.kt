package io.github.tanay7.photocleaner.core

/**
 * A folder that directly holds photos or videos. [path] has no trailing slash; "" is the storage root.
 * The sizes of its photos and of its videos are kept apart, for a scan of only one of the two.
 */
class FolderInfo(val volume: String, val path: String, val photos: Int, val videos: Int, val photoBytes: Long, val videoBytes: Long = 0) {
    val key: String get() = keyOf(volume, path)
    val bytes: Long get() = photoBytes + videoBytes

    /** The size of what a scan of this folder reads: its photos, its videos, or both. */
    fun bytes(photos: Boolean, videos: Boolean): Long = (if (photos) photoBytes else 0L) + (if (videos) videoBytes else 0L)

    companion object {
        fun keyOf(volume: String, path: String) = "$volume:${path.trim('/')}"
    }
}

/** A row of the folder tree: a storage volume, or a folder with photos or videos somewhere below it. */
class FolderNode(val volume: String, var path: String, var label: String, var parent: FolderNode?) {
    val children = ArrayList<FolderNode>()
    /** The photos and videos directly in this folder, if any. */
    var folder: FolderInfo? = null
    /** Totals for this folder and everything below it. */
    var photos = 0
    var videos = 0
    var bytes = 0L
    var depth = 0
    val key: String get() = FolderInfo.keyOf(volume, path)
    val isVolume: Boolean get() = parent == null

    /** Every folder with photos or videos in this subtree, this one included. */
    fun folders(): List<FolderInfo> {
        val out = ArrayList<FolderInfo>()
        fun walk(n: FolderNode) {
            n.folder?.let { out.add(it) }
            n.children.forEach { walk(it) }
        }
        walk(this)
        return out
    }
}

object FolderTree {
    /**
     * Builds one tree per storage volume. A chain of folders that hold nothing themselves and have a
     * single subfolder is shown as one row ("Android/media/com.whatsapp/WhatsApp/Media").
     */
    fun build(folders: List<FolderInfo>, volumeLabel: (String) -> String): List<FolderNode> {
        val roots = LinkedHashMap<String, FolderNode>()
        val nodes = HashMap<String, FolderNode>()
        for (f in folders) {
            val root = roots.getOrPut(f.volume) { FolderNode(f.volume, "", volumeLabel(f.volume), null) }
            var node = root
            var path = ""
            for (part in f.path.split('/').filter { it.isNotEmpty() }) {
                path = if (path.isEmpty()) part else "$path/$part"
                val parent = node
                node = nodes.getOrPut(FolderInfo.keyOf(f.volume, path)) {
                    FolderNode(f.volume, path, part, parent).also { parent.children.add(it) }
                }
            }
            node.folder = f
            var n: FolderNode? = node
            while (n != null) {
                n.photos += f.photos
                n.videos += f.videos
                n.bytes += f.bytes
                n = n.parent
            }
        }
        val ordered = roots.values.sortedWith(compareBy({ it.volume != "external_primary" }, { it.label.lowercase() }))
        for (r in ordered) finish(r, 0)
        return ordered
    }

    private fun finish(node: FolderNode, depth: Int) {
        node.depth = depth
        if (!node.isVolume) {
            while (node.folder == null && node.children.size == 1) {
                val only = node.children[0]
                node.label = node.label + "/" + only.label
                node.path = only.path
                node.folder = only.folder
                node.children.clear()
                node.children.addAll(only.children)
                for (c in node.children) c.parent = node
            }
        }
        node.children.sortWith(compareBy({ it.label.lowercase() }, { it.label }))
        for (c in node.children) finish(c, depth + 1)
    }

    /** The rows to show: every root, and the children of expanded rows. */
    fun visible(roots: List<FolderNode>, expanded: Set<String>): List<FolderNode> {
        val out = ArrayList<FolderNode>()
        fun walk(n: FolderNode) {
            out.add(n)
            if (n.key in expanded) n.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    /**
     * The folders to select on start. Nothing saved yet: all of them. Otherwise the saved selection,
     * plus folders that are new since it was saved if everything was selected then.
     */
    fun initialSelection(all: Collection<String>, saved: Set<String>?, known: Set<String>?, allMode: Boolean): MutableSet<String> {
        if (saved == null) return all.toHashSet()
        return all.filterTo(HashSet()) { it in saved || (allMode && (known == null || it !in known)) }
    }
}

enum class Check { NONE, SOME, ALL }

/** Which folders to search. Holds the keys of selected folders that directly hold photos or videos. */
class FolderSelection(val selected: MutableSet<String>) {
    fun state(node: FolderNode): Check {
        var on = 0
        var total = 0
        for (f in node.folders()) {
            total++
            if (f.key in selected) on++
        }
        return when {
            total == 0 || on == 0 -> Check.NONE
            on == total -> Check.ALL
            else -> Check.SOME
        }
    }

    /** Selects the whole subtree, or clears it when all of it is selected already. */
    fun toggle(node: FolderNode) {
        val keys = node.folders().map { it.key }
        if (state(node) == Check.ALL) selected.removeAll(keys.toSet()) else selected.addAll(keys)
    }

    fun setAll(roots: List<FolderNode>, on: Boolean) {
        val keys = roots.flatMap { r -> r.folders().map { it.key } }
        if (on) selected.addAll(keys) else selected.removeAll(keys.toSet())
    }
}

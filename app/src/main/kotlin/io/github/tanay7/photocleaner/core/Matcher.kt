package io.github.tanay7.photocleaner.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

enum class MediaKind { PHOTO, VIDEO }

/** One photo or video as the matcher sees it. */
class Item(
    val kind: MediaKind,
    /** Folder and name, such as "DCIM/Camera/IMG_1.jpg": breaks ties and shows copy names. */
    val path: String,
    val size: Long,
    /** When the file joined the phone's media index (seconds): the first copy is usually the original. */
    val added: Long,
    val favorite: Boolean,
    /** In a folder where originals are made: the camera, screenshots, screen recordings. */
    val original: Boolean,
    /** SHA-256 of the whole file, or null when it wasn't needed or couldn't be read. */
    val sha: String?,
    /** Full-size pixels after rotation, 0 when unknown. */
    val width: Int,
    val height: Int,
    /** Capture time from the photo's own EXIF data (seconds), or null. */
    val takenAt: Double?,
    /** Camera make and model from EXIF, or null. */
    val camera: String?,
    val features: ImageFeatures?,
) {
    val pixels: Long get() = width.toLong() * height
}

class MatchOptions(
    /** Look-alike photos: resized, re-saved, turned, mirrored or cropped copies. */
    val similar: Boolean = true,
    /** Shots taken within [burstGap] seconds of each other that look alike. */
    val bursts: Boolean = true,
    /** List blurry and badly exposed photos that aren't in any group. */
    val poor: Boolean = true,
    /** Most differing pHash bits for a look-alike (DupeFinder's --max-bits, 8 by default). */
    val maxBits: Int = 8,
    val burstGap: Double = 3.0,
    val blurCutoff: Float = 45f,
    val darkCutoff: Float = 40f,
    val clipCutoff: Float = 25f,
    val threads: Int = 1,
) {
    companion object {
        /** Most differing bits between neighbouring burst shots. */
        const val BURST_BITS = 16
        /** Chroma difference (0-255 scale) above which photos differ in colour. */
        const val COLOUR_DIFF_CUT = 28f
    }
}

enum class Relation { KEEP, IDENTICAL, LOOKALIKE, BURST, VIA, POOR }
enum class GroupType { IDENTICAL, SIMILAR, BURST, POOR }
enum class Flag { BLURRY, DARK, CLIPPED, FAVORITE }

class Member(
    val item: Int,
    val keeper: Boolean,
    val relation: Relation,
    /** 100 for identical copies, the visual score (0-100) for look-alikes. */
    val score: Float?,
    /** "rotated", "mirrored" or "cropped" for a look-alike matched that way. */
    val how: String?,
    /** For [Relation.VIA]: the member it was linked through. */
    val via: Int?,
    /** Pre-selected for the trash. */
    val suggestTrash: Boolean,
    val flags: Set<Flag>,
    /** In a burst: detail relative to the sharpest frame, in %. */
    val relSharpness: Float?,
    /** Why it isn't pre-selected, when that isn't obvious. */
    val note: String?,
)

class Group(
    val type: GroupType,
    val kind: MediaKind,
    val members: List<Member>,
    /** The item suggested to keep, or -1 for the list of poor shots. */
    val keeper: Int,
    val burstShots: Int,
)

/**
 * Finds groups of identical files, look-alike photos and burst shots, picks the copy to keep and
 * pre-selects the rest, with DupeFinder's rules:
 *
 *  - identical: same SHA-256;
 *  - look-alike: pHashes at most [MatchOptions.maxBits] apart (also against the other photo turned,
 *    mirrored or cropped), unless the two clearly differ in colour;
 *  - burst: shots from one camera at most [MatchOptions.burstGap] seconds apart that look alike.
 *
 * Linked files form groups (a file can join through another one). Each member is then compared with
 * the kept copy directly: only identical copies, look-alikes and burst frames are pre-selected, never
 * a mirrored or cropped version (it may be a deliberate edit), a favourite, or a file that is only
 * linked through another one.
 */
class Matcher(private val items: List<Item>, private val o: MatchOptions = MatchOptions()) {
    private val uf = UnionFind(items.size)
    private val burstChain = HashMap<Int, Int>()
    private var chains = 0
    private val photos: List<Int> = items.indices.filter { items[it].kind == MediaKind.PHOTO && items[it].features != null }

    fun run(): List<Group> {
        matchIdentical()
        if (o.similar) matchLookalikes()
        if (o.bursts) matchBursts()
        return buildGroups()
    }

    // -- links ---------------------------------------------------------------------------------

    private fun matchIdentical() {
        val bySha = HashMap<String, Int>()
        for ((i, it) in items.withIndex()) {
            val sha = it.sha ?: continue
            val first = bySha.putIfAbsent("${it.kind}:$sha", i)
            if (first != null) uf.union(first, i)
        }
    }

    /** Pairs of look-alike photos, compared directly and against each other's turned, mirrored and cropped hashes. */
    private fun matchLookalikes() {
        val ph = photos.filter { !items[it].features!!.flat }
        val n = ph.size
        if (n < 2) return
        val f = Array(n) { items[ph[it]].features!! }
        val hashes = LongArray(n) { f[it].phash }
        val extra = LongArray(n * 5)
        for (i in 0 until n) {
            for (v in 0 until 4) extra[i * 5 + v] = f[i].variants[v]
            extra[i * 5 + 4] = f[i].cropHash
        }
        val maxBits = o.maxBits
        parallel(n) { from, to, link ->
            for (i in from until to) {
                val h = hashes[i]
                for (j in i + 1 until n) {
                    if (java.lang.Long.bitCount(h xor hashes[j]) <= maxBits && colourOk(f[i], f[j])) link(ph[i], ph[j])
                }
                for (e in extra.indices) {
                    val owner = e / 5
                    if (owner == i) continue
                    if (java.lang.Long.bitCount(h xor extra[e]) <= maxBits && colourOk(f[i], f[owner])) link(ph[i], ph[owner])
                }
            }
        }
    }

    /** Chains shots from one camera taken within the burst gap that look alike. */
    private fun matchBursts() {
        val byCam = HashMap<String, ArrayList<Int>>()
        for (i in photos) {
            val it = items[i]
            if (it.takenAt != null) byCam.getOrPut(it.camera ?: "") { ArrayList() }.add(i)
        }
        for (shots in byCam.values) {
            shots.sortWith(compareBy({ items[it].takenAt!! }, { items[it].path }))
            var open = ArrayList<ArrayList<Int>>()       // still open: last frame within the gap
            for (cur in frames(shots)) {
                val t = items[cur].takenAt!!
                val still = ArrayList<ArrayList<Int>>()
                for (ch in open) if (t - items[ch.last()].takenAt!! <= o.burstGap) still.add(ch) else closeChain(ch)
                open = still
                // Join the first open chain whose last frame looks alike; a shot that looks different
                // (another phone of the same model) doesn't break it.
                val home = open.firstOrNull { burstAlike(it.last(), cur) }
                if (home == null) {
                    open.add(arrayListOf(cur))
                } else {
                    uf.union(home.last(), cur)
                    home.add(cur)
                }
            }
            for (ch in open) closeChain(ch)
        }
    }

    /**
     * The shots that are burst frames, in time order, without copies of them. Shots with the same
     * camera and capture time are copies of one frame (an export, a crop, a re-save) unless they have
     * the frame's exact size and look different, as frames within one second do when the camera
     * stores no fractions of a second. The largest is taken as the frame.
     */
    private fun frames(shots: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < shots.size) {
            val t = items[shots[i]].takenAt
            var j = i
            while (j < shots.size && items[shots[j]].takenAt == t) j++
            val same = shots.subList(i, j).sortedWith(compareBy({ -items[it].pixels }, { -items[it].size }, { items[it].path }))
            val frames = arrayListOf(same[0])
            for (r in same.drop(1)) {
                val twins = frames.filter { items[it].width == items[r].width && items[it].height == items[r].height }
                if (twins.isNotEmpty() && twins.all { hamming(r, it) > o.maxBits }) frames.add(r)
            }
            out.addAll(frames.sortedBy { items[it].path })
            i = j
        }
        return out
    }

    private fun burstAlike(a: Int, b: Int): Boolean {
        val fa = items[a].features!!
        val fb = items[b].features!!
        return !fa.flat && !fb.flat && ImageAnalysis.hamming(fa.phash, fb.phash) <= MatchOptions.BURST_BITS
    }

    private fun closeChain(chain: List<Int>) {
        if (chain.size < 2) return
        val idx = chains++
        for (r in chain) burstChain[r] = idx
    }

    private fun hamming(a: Int, b: Int) = ImageAnalysis.hamming(items[a].features!!.phash, items[b].features!!.phash)

    // -- groups --------------------------------------------------------------------------------

    private fun buildGroups(): List<Group> {
        val comps = HashMap<Int, ArrayList<Int>>()
        val size = IntArray(items.size)
        for (i in items.indices) size[uf.find(i)]++
        for (i in items.indices) {
            val r = uf.find(i)
            if (size[r] > 1) comps.getOrPut(r) { ArrayList() }.add(i)
        }
        val groups = comps.values.map { makeGroup(it) }.sortedWith(
            compareBy({ -space(it) }, { -it.members.size }, { it.members.minOf { m -> m.item } }),
        ).toMutableList()
        if (o.poor) poorGroup(groups)?.let { groups.add(it) }
        return groups
    }

    private fun space(g: Group): Long = g.members.filter { it.suggestTrash }.sumOf { items[it.item].size }

    private fun makeGroup(fids: List<Int>): Group {
        val keeper = chooseKeeper(fids)
        val burstFids = fids.filter { it in burstChain }
        val isBurst = burstFids.isNotEmpty()
        val topSharp = burstFids.maxOfOrNull { items[it].features?.detail ?: 0f } ?: 0f
        val members = fids.map { f ->
            val r = items[f]
            val flags = HashSet<Flag>()
            var relSharp: Float? = null
            r.features?.let { fe ->
                if (isBurst && f in burstChain && topSharp > 0) {
                    relSharp = ImageAnalysis.round1(fe.detail / topSharp * 100.0)
                } else if (!fe.sharpness.isNaN() && fe.sharpness < o.blurCutoff && !fe.flat) {
                    flags.add(Flag.BLURRY)
                }
                flags.addAll(exposureFlags(fe))
            }
            if (f == keeper) {
                if (r.favorite) flags.add(Flag.FAVORITE)
                return@map Member(f, true, Relation.KEEP, null, null, null, false, flags, relSharp, null)
            }
            val rel = relation(f, keeper)
            var relation = rel?.relation ?: Relation.VIA
            var suggest = relation == Relation.IDENTICAL || relation == Relation.BURST || relation == Relation.LOOKALIKE
            var note: String? = null
            var via: Int? = null
            if (rel?.how == "mirrored" || rel?.how == "cropped") {
                suggest = false
                note = "A ${rel.how} version may be a deliberate edit, so it isn't pre-selected"
            }
            if (rel == null) {
                via = fids.firstOrNull { it != f && it != keeper && relation(f, it) != null }
                relation = Relation.VIA
            }
            if (r.favorite) {
                flags.add(Flag.FAVORITE)
                suggest = false
                note = "A favourite, so it isn't pre-selected"
            }
            Member(f, false, relation, rel?.score, rel?.how, via, suggest, flags, relSharp, note)
        }
        val others = members.filter { !it.keeper }
        val type = when {
            isBurst -> GroupType.BURST
            others.all { it.relation == Relation.IDENTICAL } -> GroupType.IDENTICAL
            else -> GroupType.SIMILAR
        }
        val ordered = if (type == GroupType.BURST) {
            members.sortedWith(compareBy({ items[it.item].takenAt ?: 0.0 }, { items[it.item].path }))
        } else {
            members.sortedWith(compareBy({ !it.keeper }, { -(it.score ?: -1f) }, { items[it.item].path }))
        }
        return Group(type, items[keeper].kind, ordered, keeper, if (isBurst) burstFids.size else 0)
    }

    private class Rel(val relation: Relation, val score: Float?, val how: String?)

    /** The strongest check that matches item r directly against item k. */
    private fun relation(r: Int, k: Int): Rel? {
        val a = items[r]
        val b = items[k]
        if (a.sha != null && a.sha == b.sha && a.kind == b.kind) return Rel(Relation.IDENTICAL, 100f, null)
        val fa = a.features ?: return null
        val fb = b.features ?: return null
        val ca = burstChain[r]
        if (ca != null && ca == burstChain[k]) return Rel(Relation.BURST, null, null)
        if (fa.flat || fb.flat || !colourOk(fa, fb)) return null
        val d = ImageAnalysis.hamming(fa.phash, fb.phash)
        if (d <= o.maxBits) return Rel(Relation.LOOKALIKE, ImageAnalysis.visualScore(d), null)
        var best = Int.MAX_VALUE
        var how: String? = null
        for (v in 0 until 4) {
            val dv = ImageAnalysis.hamming(fa.phash, fb.variants[v])
            if (dv < best) {
                best = dv
                how = VARIANT_NAMES[v]
            }
        }
        val dc = min(ImageAnalysis.hamming(fa.phash, fb.cropHash), ImageAnalysis.hamming(fb.phash, fa.cropHash))
        if (dc < best) {
            best = dc
            how = "cropped"
        }
        if (best <= o.maxBits) return Rel(Relation.LOOKALIKE, ImageAnalysis.visualScore(best), how)
        return null
    }

    private fun chooseKeeper(fids: List<Int>): Int {
        var pool = fids
        val favs = pool.filter { items[it].favorite }
        if (favs.isNotEmpty()) pool = favs
        if (items[pool[0]].kind == MediaKind.PHOTO) {
            val frames = pool.filter { it in burstChain }
            if (frames.isNotEmpty()) {
                // Only burst frames compete on sharpness; a smaller export or copy of a frame must
                // never outrank the full-size originals.
                return frames.minWith(compareBy({ -quality(it) }, { -items[it].pixels }, { items[it].path }))
            }
            return pool.minWith(
                compareBy<Int>({ -items[it].pixels }, { -items[it].size }, { CopyNames.hasCopyMarker(items[it].path) },
                    { !items[it].original }, { items[it].takenAt ?: Double.MAX_VALUE }, { items[it].added }, { items[it].path }),
            )
        }
        return pool.minWith(
            compareBy<Int>({ CopyNames.hasCopyMarker(items[it].path) }, { !items[it].original }, { items[it].added }, { items[it].path }),
        )
    }

    private fun quality(i: Int): Double {
        val f = items[i].features ?: return 0.0
        return if (exposureFlags(f).isEmpty()) f.detail.toDouble() else f.detail * 0.5
    }

    fun exposureFlags(f: ImageFeatures): List<Flag> {
        val out = ArrayList<Flag>(2)
        if (f.median < o.darkCutoff) out.add(Flag.DARK)
        if ((f.black + f.white) * 100 > o.clipCutoff) out.add(Flag.CLIPPED)
        return out
    }

    /** Blurry or badly exposed photos that aren't in any group, worst first. Never pre-selected. */
    private fun poorGroup(groups: List<Group>): Group? {
        val grouped = HashSet<Int>()
        for (g in groups) for (m in g.members) grouped.add(m.item)
        val members = ArrayList<Member>()
        for (i in photos) {
            if (i in grouped) continue
            val f = items[i].features!!
            if (f.sharpness.isNaN()) continue
            val flags = HashSet<Flag>()
            if (f.sharpness < o.blurCutoff && !f.flat) flags.add(Flag.BLURRY)
            flags.addAll(exposureFlags(f))
            if (flags.isEmpty()) continue
            if (items[i].favorite) flags.add(Flag.FAVORITE)
            members.add(Member(i, false, Relation.POOR, null, null, null, false, flags, null, null))
        }
        if (members.isEmpty()) return null
        members.sortWith(compareBy({ items[it.item].features!!.sharpness }, { items[it.item].path }))
        return Group(GroupType.POOR, MediaKind.PHOTO, members, -1, 0)
    }

    private fun colourOk(a: ImageFeatures, b: ImageFeatures): Boolean {
        // Black-and-white or nearly colourless photos always pass, so a greyscale conversion still
        // matches its colour original.
        if (min(a.saturation, b.saturation) < 6f) return true
        return abs(a.cb - b.cb) + abs(a.cr - b.cr) <= MatchOptions.COLOUR_DIFF_CUT
    }

    /**
     * Runs [body] over 0 until n in chunks, on [MatchOptions.threads] threads; each chunk reports its
     * pairs through `link`, which joins them under a lock (no list of every pair is ever kept).
     */
    private fun parallel(n: Int, body: (from: Int, to: Int, link: (Int, Int) -> Unit) -> Unit) {
        val lock = Any()
        val threads = o.threads.coerceIn(1, 16)
        if (threads == 1 || n < 1000) {
            body(0, n) { a, b -> uf.union(a, b) }
            return
        }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // Rows near the end are shorter (j > i): many chunks keep the threads evenly busy.
            val chunks = threads * 16
            val tasks = (0 until chunks).map { c ->
                Callable {
                    val buf = IntArray(2048)
                    var k = 0
                    val flush = { synchronized(lock) { var p = 0; while (p < k) { uf.union(buf[p], buf[p + 1]); p += 2 } }; k = 0 }
                    body(c * n / chunks, (c + 1) * n / chunks) { a, b ->
                        if (k == buf.size) flush()
                        buf[k++] = a
                        buf[k++] = b
                    }
                    flush()
                }
            }
            for (fut in pool.invokeAll(tasks)) fut.get()
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private val VARIANT_NAMES = arrayOf("rotated", "rotated", "rotated", "mirrored")
    }
}

class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = ByteArray(n)

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) {
            val next = parent[c]
            parent[c] = r
            c = next
        }
        return r
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> {
                parent[rb] = ra
                rank[ra]++
            }
        }
    }
}

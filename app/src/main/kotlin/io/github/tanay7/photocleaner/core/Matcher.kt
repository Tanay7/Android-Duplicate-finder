package io.github.tanay7.photocleaner.core

import kotlin.math.abs
import kotlin.math.max
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
    /** A camera RAW file (DNG and the like): never pre-selected against its JPEG twin, nor the twin against it. */
    val raw: Boolean,
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
    /** Threads for comparing every pair of photos: all cores, the calling thread included. */
    val threads: Int = 1,
    /** Asked now and then while comparing; true stops the work with a CancellationException. */
    val cancelled: () -> Boolean = { false },
    /** Told how far comparing every pair is: (done, total). Called from several threads. */
    val progress: ((Int, Int) -> Unit)? = null,
) {
    /**
     * Most differing fine bits ([ImageFeatures.fine], of 255) for two photos to count as look-alikes
     * at all. The 64 bits of the pHash only see a picture's layout: about 1 pair in 100 of different
     * text pages, and some pairs of different app screens, pass them. Real copies differ in under 16
     * fine bits (under 35 when cut by a hundredth); different pictures that share their layout in
     * over 55.
     */
    val linkFine: Int get() = if (maxBits <= 4) 16 else 48

    /** A look-alike is pre-selected only when this sure: at most 8 pHash bits, whatever [maxBits] allows. */
    val sureBits: Int get() = min(maxBits, 8)

    /** ... and at most this many fine bits (the same share of the bits). */
    val sureFine: Int get() = sureBits * 4

    companion object {
        /** Most differing pHash bits between neighbouring burst shots. */
        const val BURST_BITS = 16
        /**
         * Most differing fine bits between burst shots. Pages of a document photographed one after
         * the other are within [BURST_BITS] three times in ten, but differ in over 60 fine bits
         * (in 80 or more nineteen times in twenty); hand-held frames of one moment differ in under 55.
         */
        const val BURST_FINE = 56
        /** Chroma difference (0-255 scale) above which photos differ in colour. */
        const val COLOUR_DIFF_CUT = 28f
        // A look-alike differing by more than these from the kept copy is an edit, not a re-save.
        const val EDIT_MEAN = 8f           // mean brightness, 0-255
        const val EDIT_CONTRAST = 1.15f    // ratio of the contrasts (halving a photo's size changes it by up to 1.08)
        const val EDIT_SATURATION = 3f     // colourfulness, and at least 15 % of it
        const val EDIT_CHROMA = 5f         // average colour
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
    /** A frame of the group's burst (not a copy of one). */
    val frame: Boolean = false,
    /** A look-alike whose brightness or colours differ from the kept copy's: an edit. */
    val edited: Boolean = false,
    /**
     * Which version of the picture it belongs to: 0 for the best copy's, 1 and up for others (an
     * edit, a crop, the RAW file). Every version keeps its own best copy.
     */
    val version: Int = 0,
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
 * On top of DupeFinder's rules, every look-alike and burst link must also pass the fine fingerprint
 * ([MatchOptions.linkFine], [MatchOptions.BURST_FINE]), which tells real copies from different
 * pictures with the same layout (text pages, app screens).
 *
 * Linked files form groups (a file can join through another one). Within a group, files that are
 * surely the same picture, unedited, form a version; a mirrored or cropped copy, one with other
 * brightness or colours (it may be a deliberate edit) and a RAW file's JPEG twin are versions of
 * their own. The best copy of every version is kept. Each other member is compared with the kept copy
 * of its version directly: pre-selected are only identical copies, sure look-alikes and burst frames
 * close to the kept shot; never a favourite, or a file that is only linked through another one.
 */
class Matcher(private val items: List<Item>, private val o: MatchOptions = MatchOptions()) {
    private val uf = UnionFind(items.size)
    /** Joins files that are surely the same picture, unedited: one of them is kept, the others can go. */
    private val same = UnionFind(items.size)
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
            if (first != null) {
                uf.union(first, i)
                same.union(first, i)
            }
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
        val linkFine = o.linkFine
        val sureBits = o.sureBits
        val sureFine = o.sureFine
        val raw = BooleanArray(n) { items[ph[it]].raw }
        parallel(n) { from, to, link ->
            for (i in from until to) {
                val h = hashes[i]
                val fi = f[i]
                for (j in i + 1 until n) {
                    val bits = java.lang.Long.bitCount(h xor hashes[j])
                    if (bits > maxBits) continue
                    val fj = f[j]
                    if (!colourOk(fi, fj)) continue
                    val fd = ImageAnalysis.fineDistance(fi.fine, fj.fine)
                    if (fd <= linkFine) link(ph[i], ph[j], bits <= sureBits && fd <= sureFine && raw[i] == raw[j] && !edited(fi, fj))
                }
                for (j in 0 until n) {
                    if (j == i) continue
                    val base = j * 5
                    for (v in 0 until 5) {
                        val bits = java.lang.Long.bitCount(h xor extra[base + v])
                        if (bits > maxBits) continue
                        val fj = f[j]
                        if (!colourOk(fi, fj)) continue
                        val fd = ImageAnalysis.fineDistance(fi.fine, if (v == 4) fj.cropFine else ImageAnalysis.fineVariant(fj.fine, v))
                        // Turned is the same picture; mirrored or cropped is a version of its own.
                        if (fd <= linkFine) link(ph[i], ph[j], v < 3 && bits <= sureBits && fd <= sureFine && raw[i] == raw[j] && !edited(fi, fj))
                    }
                }
            }
        }
    }

    /**
     * Chains shots from one camera taken within the burst gap that look alike. A camera that saves
     * every shot twice, as a RAW file and as a JPEG, gives two bursts: the sharpest of each is kept.
     */
    private fun matchBursts() {
        val byCam = HashMap<String, ArrayList<Int>>()
        for (i in photos) {
            val it = items[i]
            if (it.takenAt != null) byCam.getOrPut((if (it.raw) "raw:" else "jpg:") + (it.camera ?: "")) { ArrayList() }.add(i)
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
                    same.union(home.last(), cur)
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

    /** Two shots of one moment: alike in layout and in detail. */
    private fun burstAlike(a: Int, b: Int): Boolean {
        val fa = items[a].features!!
        val fb = items[b].features!!
        return !fa.flat && !fb.flat && ImageAnalysis.hamming(fa.phash, fb.phash) <= MatchOptions.BURST_BITS &&
            ImageAnalysis.fineDistance(fa.fine, fb.fine) <= MatchOptions.BURST_FINE
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
        // The versions of the picture, and the best copy of each: the best copy overall for its own
        // version (number 0), then the others by name.
        val byVersion = LinkedHashMap<Int, ArrayList<Int>>()
        for (f in fids) byVersion.getOrPut(same.find(f)) { ArrayList() }.add(f)
        val versions = byVersion.values.map { v -> (if (keeper in v) keeper else chooseKeeper(v)) to v }
            .sortedWith(compareBy({ it.first != keeper }, { items[it.first].path }))
        val keeperOf = HashMap<Int, Int>()
        val versionOf = HashMap<Int, Int>()
        versions.forEachIndexed { idx, (k, v) ->
            for (f in v) {
                keeperOf[f] = k
                versionOf[f] = idx
            }
        }
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
                } else if (cameraShot(r) && !fe.sharpness.isNaN() && fe.sharpness < o.blurCutoff && !fe.flat) {
                    flags.add(Flag.BLURRY)
                }
                if (cameraShot(r)) flags.addAll(exposureFlags(fe))
            }
            val frame = f in burstChain
            val version = versionOf.getValue(f)
            val k = keeperOf.getValue(f)
            if (r.favorite) flags.add(Flag.FAVORITE)
            if (f == keeper) return@map Member(f, true, Relation.KEEP, null, null, null, false, flags, relSharp, null, frame)
            if (f == k) {
                // The best copy of another version: kept too. What sets it apart from the best copy overall:
                val rel = relation(f, keeper)
                val edited = rel?.relation == Relation.LOOKALIKE && edited(r.features!!, items[keeper].features!!)
                val note = when {
                    r.raw != items[keeper].raw -> "The ${if (r.raw) "RAW" else "developed"} version of the photo: kept as well"
                    rel == null -> "Linked to the best copy only through another file: kept as well"
                    rel.how == "mirrored" || rel.how == "cropped" -> "A ${rel.how} version (it may be a deliberate edit): kept as well"
                    edited -> "Its brightness or colours differ from the best copy's (it may be an edit): kept as well"
                    else -> "It looks like the best copy but differs in the details: kept as well"
                }
                return@map Member(f, true, Relation.KEEP, rel?.score, rel?.how, null, false, flags, relSharp, note, frame, edited, version)
            }
            val rel = relation(f, k)
            val relation = rel?.relation ?: Relation.VIA
            var suggest = rel != null && rel.sure
            var note: String? = null
            var via: Int? = null
            var edited = false
            when (relation) {
                Relation.VIA -> via = fids.firstOrNull { it != f && it != k && relation(f, it) != null }
                Relation.BURST ->
                    if (!suggest) note = "A different moment of the burst than the kept shot, so it isn't pre-selected"
                Relation.LOOKALIKE -> {
                    edited = edited(r.features!!, items[k].features!!)
                    if (rel!!.how == "mirrored" || rel.how == "cropped") {
                        suggest = false
                        note = "A ${rel.how} version may be a deliberate edit, so it isn't pre-selected"
                    } else if (!suggest) {
                        note = "It looks like the kept copy but differs in the details, so it isn't pre-selected"
                    } else if (edited) {
                        suggest = false
                        note = "Its brightness or colours differ from the kept copy's (it may be an edit), so it isn't pre-selected"
                    }
                }
                else -> Unit
            }
            if (relation != Relation.IDENTICAL && relation != Relation.VIA && r.raw != items[k].raw) {
                suggest = false
                note = "RAW and non-RAW versions of the same photo, so it isn't pre-selected"
            }
            if (r.favorite) {
                suggest = false
                note = "A favourite, so it isn't pre-selected"
            }
            Member(f, false, relation, rel?.score, rel?.how, via, suggest, flags, relSharp, note, frame, edited, version)
        }
        val others = members.filter { !it.keeper }
        val type = when {
            isBurst -> GroupType.BURST
            versions.size == 1 && others.all { it.relation == Relation.IDENTICAL } -> GroupType.IDENTICAL
            else -> GroupType.SIMILAR
        }
        val ordered = if (type == GroupType.BURST) {
            members.sortedWith(compareBy({ items[it.item].takenAt ?: 0.0 }, { items[it.item].path }))
        } else {
            members.sortedWith(compareBy({ it.version }, { !it.keeper }, { -(it.score ?: -1f) }, { items[it.item].path }))
        }
        return Group(type, items[keeper].kind, ordered, keeper, if (isBurst) burstFids.size else 0)
    }

    private class Rel(val relation: Relation, val score: Float?, val how: String?, val sure: Boolean)

    /** The strongest check that matches item r directly against item k; [Rel.sure] when it may be pre-selected. */
    private fun relation(r: Int, k: Int): Rel? {
        val a = items[r]
        val b = items[k]
        if (a.sha != null && a.sha == b.sha && a.kind == b.kind) return Rel(Relation.IDENTICAL, 100f, null, true)
        val fa = a.features ?: return null
        val fb = b.features ?: return null
        val ca = burstChain[r]
        if (ca != null && ca == burstChain[k]) return Rel(Relation.BURST, null, null, burstAlike(r, k))
        if (fa.flat || fb.flat || !colourOk(fa, fb)) return null
        var best: Rel? = null
        var bestFine = Int.MAX_VALUE
        fun consider(bits: Int, fine: () -> Int, how: String?) {
            if (bits > o.maxBits) return
            val fd = fine()
            if (fd > o.linkFine || fd >= bestFine) return
            bestFine = fd
            best = Rel(Relation.LOOKALIKE, ImageAnalysis.fineScore(fd), how, bits <= o.sureBits && fd <= o.sureFine)
        }
        consider(ImageAnalysis.hamming(fa.phash, fb.phash), { ImageAnalysis.fineDistance(fa.fine, fb.fine) }, null)
        if (best == null) {
            for (v in 0 until 4) {
                consider(ImageAnalysis.hamming(fa.phash, fb.variants[v]),
                    { ImageAnalysis.fineDistance(fa.fine, ImageAnalysis.fineVariant(fb.fine, v)) }, VARIANT_NAMES[v])
            }
            consider(ImageAnalysis.hamming(fa.phash, fb.cropHash), { ImageAnalysis.fineDistance(fa.fine, fb.cropFine) }, "cropped")
            consider(ImageAnalysis.hamming(fb.phash, fa.cropHash), { ImageAnalysis.fineDistance(fb.fine, fa.cropFine) }, "cropped")
        }
        return best
    }

    /**
     * True when two look-alikes differ in brightness, contrast or colour by more than saving a photo
     * again or resizing it changes them: one is an edit of the other (a filter, black and white, a
     * brighter version), which someone made on purpose.
     */
    private fun edited(a: ImageFeatures, b: ImageFeatures): Boolean {
        if (abs(a.mean - b.mean) > MatchOptions.EDIT_MEAN) return true
        if (max(a.contrast, b.contrast) > min(a.contrast, b.contrast) * MatchOptions.EDIT_CONTRAST + 1f) return true
        val sat = max(a.saturation, b.saturation)
        if (abs(a.saturation - b.saturation) > max(MatchOptions.EDIT_SATURATION, 0.15f * sat)) return true
        return abs(a.cb - b.cb) + abs(a.cr - b.cr) > MatchOptions.EDIT_CHROMA
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

    /**
     * A photo a camera took (its EXIF data names the camera or the moment). Only those are judged
     * blurry, dark or badly exposed: a screenshot of a white page is "overexposed" by the numbers,
     * and a drawing "blurry", and neither is a poor shot.
     */
    private fun cameraShot(it: Item) = it.camera != null || it.takenAt != null

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
            if (i in grouped || !cameraShot(items[i])) continue
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
     * Runs [body] over 0 until n on [MatchOptions.threads] threads, the calling one included. Chunks
     * are handed out one at a time from a shared counter, so faster cores (of a big.LITTLE CPU) take
     * more of them and every core stays busy until the end. Each chunk reports its pairs through
     * `link`, which joins them under a lock: no list of every pair is ever kept.
     */
    private fun parallel(n: Int, body: (from: Int, to: Int, link: (Int, Int, Boolean) -> Unit) -> Unit) {
        val lock = Any()
        val threads = o.threads.coerceIn(1, 64)
        if (threads == 1 || n < 256) {
            // In slices, so that a big job on one thread can still be stopped and shows how far it is.
            val slices = if (n < 256) 1 else 64
            for (c in 0 until slices) {
                if (o.cancelled()) throw java.util.concurrent.CancellationException()
                body(n.toLong().times(c).div(slices).toInt(), n.toLong().times(c + 1).div(slices).toInt()) { a, b, version ->
                    uf.union(a, b)
                    if (version) same.union(a, b)
                }
                o.progress?.invoke(c + 1, slices)
            }
            return
        }
        // Row i costs (n - 1 - i) direct comparisons plus 5(n - 1) against the turned, mirrored and
        // cropped hashes: about a - i with a = 6n. Chunks of equal cost (not equal rows) end where the
        // running cost a·i - i²/2 reaches each share, so the last chunk is as small as the first.
        val chunks = minOf(n, threads * 32)
        val a = 6.0 * n - 5.5
        val total = n * a - n.toDouble() * n / 2
        val bounds = IntArray(chunks + 1) { c ->
            (a - Math.sqrt(maxOf(0.0, a * a - 2 * total * c / chunks))).toInt().coerceIn(0, n)
        }
        bounds[chunks] = n
        val next = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val work = Runnable {
            val buf = IntArray(4096)
            var k = 0
            fun flush() {
                synchronized(lock) {
                    var p = 0
                    while (p < k) {
                        val b = buf[p + 1] and SAME.inv()
                        uf.union(buf[p], b)
                        if (buf[p + 1] and SAME != 0) same.union(buf[p], b)
                        p += 2
                    }
                }
                k = 0
            }
            try {
                while (failure.get() == null) {
                    val c = next.getAndIncrement()
                    if (c >= chunks) break
                    if (o.cancelled()) throw java.util.concurrent.CancellationException()
                    body(bounds[c], bounds[c + 1]) { a, b, version ->
                        if (k == buf.size) flush()
                        buf[k++] = a
                        buf[k++] = if (version) b or SAME else b
                    }
                    o.progress?.invoke(done.incrementAndGet(), chunks)
                }
                flush()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val helpers = (1 until threads).map { Thread(work, "match-$it").apply { isDaemon = true; start() } }
        work.run()
        helpers.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    companion object {
        private val VARIANT_NAMES = arrayOf("rotated", "rotated", "rotated", "mirrored")
        /** Marks, in a buffered pair, that the two are the same version (item numbers stay below it). */
        private const val SAME = 1 shl 30
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

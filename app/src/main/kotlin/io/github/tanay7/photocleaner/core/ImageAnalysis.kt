package io.github.tanay7.photocleaner.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the matcher knows about one photo's pixels.
 *
 * Computed from the photo reduced to at most [ImageAnalysis.REDUCED_SIZE] px on its long side, with the
 * algorithms and cut-offs of DupeFinder, the desktop duplicate finder, so both tools judge photos alike.
 */
class ImageFeatures(
    /** 64-bit perceptual hash (DCT of a 32x32 grey copy, 8x8 block against its median). */
    val phash: Long,
    /** pHash of the same photo turned 90°, 180°, 270° and mirrored, in that order. */
    val variants: LongArray,
    /** pHash of the centre square (wide or tall photos) or of the central 80 % (squarish ones). */
    val cropHash: Long,
    /**
     * 255-bit fingerprint of finer detail: the signs of the 16x16 lowest frequencies of the same DCT
     * (4 longs). Two different text pages or app screens can share the 64 bits of [phash], which only
     * see the layout; they don't share these. Every look-alike and burst frame must pass it too.
     */
    val fine: LongArray,
    /** The same fingerprint of the centre crop that [cropHash] describes. */
    val cropFine: LongArray,
    /** A featureless picture (a blank page, a black frame): never matched on its looks. */
    val flat: Boolean,
    /** Average colour (YCbCr chroma) and how colourful the photo is. */
    val cb: Float,
    val cr: Float,
    val saturation: Float,
    /** Mean brightness and contrast (standard deviation of the brightness), 0-255: an edit changes them, a re-save doesn't. */
    val mean: Float,
    val contrast: Float,
    /** 0 (blurry) to 100 (sharp); NaN when the photo is too small to measure. */
    val sharpness: Float,
    /** Laplacian detail of the 4 sharpest of 16 tiles: ranks the frames of a burst. */
    val detail: Float,
    /** Median brightness (0-255) and the shares of near-black and near-white pixels. */
    val median: Float,
    val black: Float,
    val white: Float,
    /**
     * What the picture looks like place by place: the mean brightness, then the mean of each of the
     * two colour channels, of every cell of a [ImageAnalysis.GRID] x [ImageAnalysis.GRID] grid (three
     * planes of bytes, row by row). The fingerprints above see the picture as a whole: a copy with a
     * sticker or a caption added, a face blurred out or an object removed still passes them. It
     * doesn't pass this: a re-saved or resized copy keeps every cell within a few levels.
     *
     * Null for pictures under [ImageAnalysis.GRID] pixels a side. Large for something kept for every
     * photo, so it isn't held in memory once it is saved: whoever needs it then reads it back.
     */
    @Volatile var grid: ByteArray? = null,
)

/**
 * Buffers one worker thread reuses for every photo it analyses: the grey copy, the noise filter's
 * output, a block of pixel rows and the resizing scratch. Analysing a photo then allocates (almost)
 * nothing, so many threads can run without the garbage collector stopping them all. Not thread-safe:
 * one per thread.
 */
class Scratch {
    internal var gray = IntArray(0)
    internal var med = IntArray(0)
    internal var rows = IntArray(0)
    internal var mid = DoubleArray(0)
    internal val small = DoubleArray(32 * 32)
    internal val line = DoubleArray(ImageAnalysis.REDUCED_SIZE + 2)
    internal val blurred = DoubleArray(ImageAnalysis.REDUCED_SIZE + 2)
    internal val hist = IntArray(256)
    /** For each pixel column: the grid cell it falls in, and the share of it in that cell (the rest is in the next). */
    internal var colCell = IntArray(0)
    internal var colShare = DoubleArray(0)
    internal val rowSums = DoubleArray(3 * ImageAnalysis.GRID)
    internal val cellSums = DoubleArray(3 * ImageAnalysis.GRID * ImageAnalysis.GRID)

    internal fun ensure(w: Int, h: Int) {
        val n = w * h
        if (colCell.size < w) {
            colCell = IntArray(w)
            colShare = DoubleArray(w)
        }
        if (gray.size < n) {
            gray = IntArray(n)
            med = IntArray(n)
        }
        if (rows.size < w * ImageAnalysis.ROW_BLOCK) rows = IntArray(w * ImageAnalysis.ROW_BLOCK)
        if (mid.size < h * 32) mid = DoubleArray(h * 32)
    }
}

object ImageAnalysis {
    /** Photos are analysed reduced to at most this long side. */
    const val REDUCED_SIZE = 1024
    /**
     * ... and, when they are larger than that, to no less than this: a photo is reduced by a power
     * of two where that lands between the two (see [Parallelism.sampleFor]), so every larger photo
     * is measured at nearly the same scale, and blur means the same for all of them.
     */
    const val SMALLEST_REDUCED = 960
    const val HASH_BITS = 64
    /** Rows of pixels fetched at a time. */
    const val ROW_BLOCK = 32
    /** The fine fingerprint covers the FINE x FINE lowest frequencies, less the first: 255 bits. */
    private const val FINE = 16
    const val FINE_BITS = FINE * FINE - 1
    private const val FLAT_STD = 2.0
    /** Cells a side of the grid that describes a picture place by place ([ImageFeatures.grid]). */
    const val GRID = 16
    const val GRID_BYTES = 3 * GRID * GRID
    /**
     * Two pictures differ in part when a cell differs by this much in brightness or in colour, on top
     * of what they differ by as a whole. Measured on generated photos reduced the way the app reduces
     * them: re-saved and resized copies (JPEG quality 55 to 95, 800 px to full size, sharpened) stay
     * within 3 and 2; a sticker 2.5 % of the width, a date stamp, a drawn circle or an object
     * replaced give 6 or more in one of the two.
     */
    const val LOCAL_LUMA = 6
    const val LOCAL_CHROMA = 5

    /**
     * How much wider those limits are for a small picture, whose cells hold few pixels (and a quarter
     * as many colour samples), so that the edges of a resized copy fall differently in them. Measured
     * on copies of 20 photos: with 400 px or more on the long side every cell stays within 1.1 in
     * brightness and 2.1 in colour; at 240 px within 3 and 4, at 160 px 4 and 10, at 96 px 9 and 19.
     * None for 400 px and more, then growing with the square of the reduction, to 8 times at most.
     */
    fun cellSlack(longSide: Int): Double {
        if (longSide >= 400 || longSide <= 0) return 1.0
        val r = 400.0 / longSide
        return min(8.0, r * r)
    }

    /**
     * Analyses one reduced photo. [fill] writes `count` rows of unpremultiplied ARGB pixels from row
     * `y0` into the start of the array it gets (what `Bitmap.getPixels` does); transparent pixels are
     * seen over white.
     */
    fun analyze(w: Int, h: Int, s: Scratch = Scratch(), fill: (y0: Int, count: Int, out: IntArray) -> Unit): ImageFeatures {
        require(w in 1..REDUCED_SIZE * 4 && h in 1..REDUCED_SIZE * 4) { "bad image size ${w}x$h" }
        s.ensure(w, h)
        val gray = s.gray
        var sumCb = 0.0
        var sumCr = 0.0
        var sumSat = 0.0
        var sumY = 0L
        var sumY2 = 0L
        // The grid: every pixel adds to the cell it lies in; one that straddles the line between two
        // cells adds its share to each, so a copy of another size gives the same cells.
        val local = w >= GRID && h >= GRID
        val colCell = s.colCell
        val colShare = s.colShare
        val rowSums = s.rowSums
        val cellSums = s.cellSums
        if (local) {
            cellSums.fill(0.0)
            for (x in 0 until w) {
                val c = (x.toLong() * GRID / w).toInt()
                val edge = (c + 1).toDouble() * w / GRID
                colCell[x] = c * 3
                colShare[x] = if (edge < x + 1 && c < GRID - 1) edge - x else 1.0
            }
        }
        var y0 = 0
        while (y0 < h) {
            val count = min(ROW_BLOCK, h - y0)
            fill(y0, count, s.rows)
            val rows = s.rows
            for (ry in 0 until count) {
                val y = y0 + ry
                val base = y * w
                val src = ry * w
                if (local) rowSums.fill(0.0)
                for (x in 0 until w) {
                    val p = rows[src + x]
                    val a = p ushr 24
                    var r = (p shr 16) and 0xFF
                    var g = (p shr 8) and 0xFF
                    var b = p and 0xFF
                    if (a < 255) {
                        r = (r * a + 255 * (255 - a) + 127) / 255
                        g = (g * a + 255 * (255 - a) + 127) / 255
                        b = (b * a + 255 * (255 - a) + 127) / 255
                    }
                    val luma = (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16   // ITU-R 601 luma, like PIL's "L"
                    gray[base + x] = luma
                    sumY += luma
                    sumY2 += luma * luma
                    val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
                    val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
                    sumCb += cb
                    sumCr += cr
                    sumSat += abs(cb - 128.0) + abs(cr - 128.0)
                    if (local) {
                        val c = colCell[x]
                        val share = colShare[x]
                        if (share >= 1.0) {
                            rowSums[c] += luma
                            rowSums[c + 1] += cb
                            rowSums[c + 2] += cr
                        } else {
                            val rest = 1.0 - share
                            rowSums[c] += luma * share
                            rowSums[c + 1] += cb * share
                            rowSums[c + 2] += cr * share
                            rowSums[c + 3] += luma * rest
                            rowSums[c + 4] += cb * rest
                            rowSums[c + 5] += cr * rest
                        }
                    }
                }
                if (local) {
                    val c = (y.toLong() * GRID / h).toInt()
                    val edge = (c + 1).toDouble() * h / GRID
                    val share = if (edge < y + 1 && c < GRID - 1) edge - y else 1.0
                    val at = c * GRID * 3
                    if (share >= 1.0) {
                        for (k in 0 until GRID * 3) cellSums[at + k] += rowSums[k]
                    } else {
                        val rest = 1.0 - share
                        for (k in 0 until GRID * 3) {
                            cellSums[at + k] += rowSums[k] * share
                            cellSums[at + GRID * 3 + k] += rowSums[k] * rest
                        }
                    }
                }
            }
            y0 += count
        }
        var grid: ByteArray? = null
        if (local) {
            val area = (w.toDouble() / GRID) * (h.toDouble() / GRID)
            val out = ByteArray(GRID_BYTES)
            for (cell in 0 until GRID * GRID) {
                for (ch in 0 until 3) out[ch * GRID * GRID + cell] = (cellSums[cell * 3 + ch] / area).roundToInt().coerceIn(0, 255).toByte()
            }
            grid = out
        }
        val n = w * h
        val ph = phash(gray, w, h, s = s)
        val crop = centreCrop(gray, w, h, s)
        val blur = Sharpness.measure(gray, w, h, s)
        val exp = exposure(gray, n, s.hist)
        val mean = sumY.toDouble() / n
        return ImageFeatures(
            phash = ph.hash,
            variants = ph.variants,
            cropHash = crop.hash,
            fine = ph.fine,
            cropFine = crop.fine,
            flat = isFlat(ph.hash, ph.std) || min(w, h) < 8,
            cb = (sumCb / n).toFloat(),
            cr = (sumCr / n).toFloat(),
            saturation = (sumSat / n).toFloat(),
            mean = mean.toFloat(),
            contrast = sqrt(max(0.0, sumY2.toDouble() / n - mean * mean)).toFloat(),
            sharpness = blur.score,
            detail = blur.detail,
            median = exp[0],
            black = exp[1],
            white = exp[2],
            grid = grid,
        )
    }

    /**
     * True when picture a and picture b differ in part: in some cell of their grids, by more than
     * they differ as a whole (the mean difference, which a slightly brighter re-save has everywhere).
     * [turn] says how b must be turned to lie like a: -1 as it is, 0 a quarter clockwise, 1 half,
     * 2 a quarter the other way (the order of [ImageFeatures.variants]). [slack] widens the limits
     * for small pictures ([cellSlack] of the smaller one's long side).
     *
     * Allocates nothing: it runs for every pair of photos that look the same as a whole, which in a
     * folder of a thousand near-identical frames is half a million pairs.
     */
    fun differsInPart(a: ByteArray, b: ByteArray, turn: Int = -1, slack: Double = 1.0): Boolean {
        if (a.size != GRID_BYTES || b.size != GRID_BYTES) return true
        val n = GRID
        for (ch in 0 until 3) {
            val at = ch * n * n
            var sum = 0
            var least = Int.MAX_VALUE
            var most = Int.MIN_VALUE
            for (m in 0 until n) {
                for (k in 0 until n) {
                    val from = when (turn) {
                        0 -> (n - 1 - k) * n + m
                        1 -> (n - 1 - m) * n + (n - 1 - k)
                        2 -> k * n + (n - 1 - m)
                        else -> m * n + k
                    }
                    val d = (a[at + m * n + k].toInt() and 0xFF) - (b[at + from].toInt() and 0xFF)
                    sum += d
                    if (d < least) least = d
                    if (d > most) most = d
                }
            }
            val mean = sum.toDouble() / (n * n)
            val limit = (if (ch == 0) LOCAL_LUMA else LOCAL_CHROMA) * slack
            if (mean - least >= limit || most - mean >= limit) return true
        }
        return false
    }

    /** The same, for pixels already in one array (the tests, and small pictures). */
    fun analyze(argb: IntArray, w: Int, h: Int, s: Scratch = Scratch()): ImageFeatures {
        require(argb.size >= w * h) { "too few pixels" }
        return analyze(w, h, s) { y0, count, out -> System.arraycopy(argb, y0 * w, out, 0, count * w) }
    }

    class PHash(val hash: Long, val std: Double, val variants: LongArray, val fine: LongArray)

    /** pHash of the region (x0, y0, cw, ch) of a grey image, plus its turned and mirrored variants. */
    fun phash(gray: IntArray, w: Int, h: Int, x0: Int = 0, y0: Int = 0, cw: Int = w, ch: Int = h, s: Scratch = Scratch()): PHash {
        s.ensure(w, h)
        val x = s.small
        resizeArea(gray, w, x0, y0, cw, ch, 32, 32, s.mid, x)
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var sq = 0.0
        for (v in x) sq += (v - mean) * (v - mean)
        val std = sqrt(sq / x.size)

        // C · X · Cᵀ: the 16x16 lowest frequencies of the unnormalised DCT-II. The pHash uses the
        // first 8x8 of them, the fine fingerprint the signs of all (but the first, the brightness).
        val t = DoubleArray(FINE * 32)
        for (k in 0 until FINE) {
            for (m in 0 until 32) {
                val c = DCT[k * 32 + m]
                val row = m * 32
                val out = k * 32
                for (col in 0 until 32) t[out + col] += c * x[row + col]
            }
        }
        val low16 = DoubleArray(FINE * FINE)
        for (k in 0 until FINE) {
            for (l in 0 until FINE) {
                var sum = 0.0
                for (col in 0 until 32) sum += t[k * 32 + col] * DCT[l * 32 + col]
                low16[k * FINE + l] = sum
            }
        }
        val low = DoubleArray(64) { low16[(it / 8) * FINE + it % 8] }
        val fine = LongArray(4)
        for (b in 1 until FINE * FINE) if (low16[b] > 0.0) fine[b ushr 6] = fine[b ushr 6] or (1L shl (b and 63))
        // A flip or a quarter turn only swaps axes and changes signs in the DCT, so every variant
        // comes from this one transform.
        val v = LongArray(4)
        val tmp = DoubleArray(64)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[j * 8 + i] * sign(j)
        v[0] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[i * 8 + j] * sign(i) * sign(j)
        v[1] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[j * 8 + i] * sign(i)
        v[2] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[i * 8 + j] * sign(j)
        v[3] = bits64(tmp)
        return PHash(bits64(low), std, v, fine)
    }

    /**
     * The fine fingerprint the same picture has when turned a quarter one way (0), half (1), a quarter
     * the other way (2) or mirrored (3): a turn or flip only swaps the axes of the DCT and changes the
     * signs of its odd rows or columns, so it comes from the stored bits (in the order of
     * [ImageFeatures.variants]).
     */
    fun fineVariant(f: LongArray, variant: Int): LongArray {
        val out = LongArray(4)
        for (i in 0 until FINE) {
            for (j in 0 until FINE) {
                if (i == 0 && j == 0) continue
                val src = if (variant == 0 || variant == 2) j * FINE + i else i * FINE + j
                val flip = when (variant) {
                    0 -> j
                    1 -> i + j
                    2 -> i
                    else -> j
                } and 1
                val bit = ((f[src ushr 6] ushr (src and 63)) and 1L) xor flip.toLong()
                val b = i * FINE + j
                if (bit != 0L) out[b ushr 6] = out[b ushr 6] or (1L shl (b and 63))
            }
        }
        return out
    }

    /** How many of the 255 fine bits differ. */
    fun fineDistance(a: LongArray, b: LongArray): Int =
        java.lang.Long.bitCount(a[0] xor b[0]) + java.lang.Long.bitCount(a[1] xor b[1]) +
            java.lang.Long.bitCount(a[2] xor b[2]) + java.lang.Long.bitCount(a[3] xor b[3])

    /** The share of the fine bits that agree, in %: what a look-alike is shown with. */
    fun fineScore(bits: Int): Float = (FINE_BITS - bits) * 100f / FINE_BITS

    private fun sign(i: Int) = if (i % 2 == 0) 1.0 else -1.0

    private val DCT = DoubleArray(FINE * 32) { idx ->
        val k = idx / 32
        val m = idx % 32
        cos(PI * k * (2 * m + 1) / 64.0)
    }

    /** Bits of the 64 values above their median, first value in the highest bit. */
    fun bits64(block: DoubleArray): Long {
        val sorted = block.copyOf()
        sorted.sort()
        val median = (sorted[31] + sorted[32]) / 2
        var h = 0L
        for (i in 0 until 64) if (block[i] > median) h = h or (1L shl (63 - i))
        return h
    }

    fun isFlat(hash: Long, std: Double): Boolean {
        val ones = java.lang.Long.bitCount(hash)
        return std < FLAT_STD || ones < 8 || ones > 56
    }

    /** The hashes of the centre square (wide or tall photos) or of the central 80 % (squarish ones). */
    fun centreCrop(gray: IntArray, w: Int, h: Int, s: Scratch = Scratch()): PHash {
        return if (max(w, h) > 1.15 * min(w, h)) {
            val side = min(w, h)
            phash(gray, w, h, (w - side) / 2, (h - side) / 2, side, side, s)
        } else {
            phash(gray, w, h, w / 10, h / 10, w - 2 * (w / 10), h - 2 * (h / 10), s)
        }
    }

    /** Median brightness and the shares of pixels at or below 5 and at or above 250, of the first n. */
    fun exposure(gray: IntArray, n: Int = gray.size, hist: IntArray = IntArray(256)): FloatArray {
        hist.fill(0)
        for (i in 0 until n) hist[gray[i]]++
        fun kth(k: Int): Int {
            var c = 0
            for (v in 0..255) {
                c += hist[v]
                if (c > k) return v
            }
            return 255
        }
        val med = if (n % 2 == 1) kth(n / 2).toFloat() else (kth(n / 2 - 1) + kth(n / 2)) / 2f
        var black = 0
        for (v in 0..5) black += hist[v]
        var white = 0
        for (v in 250..255) white += hist[v]
        return floatArrayOf(med, black.toFloat() / n, white.toFloat() / n)
    }

    /**
     * Resizes the region (x0, y0, cw, ch) of a grey image of width [w] to tw x th by area averaging:
     * every output pixel is the mean of the source area it covers, so the result doesn't depend on
     * which way the photo is turned. [mid] holds at least ch * tw values; the result goes to [out].
     */
    fun resizeArea(
        src: IntArray, w: Int, x0: Int, y0: Int, cw: Int, ch: Int, tw: Int, th: Int,
        mid: DoubleArray = DoubleArray(ch * tw), out: DoubleArray = DoubleArray(th * tw),
    ): DoubleArray {
        val wx = AreaWeights(cw, tw)
        val wy = AreaWeights(ch, th)
        for (y in 0 until ch) {
            val row = (y0 + y) * w + x0
            for (tx in 0 until tw) {
                val s = wx.start[tx]
                val ws = wx.weights[tx]
                var acc = 0.0
                for (k in ws.indices) acc += src[row + s + k] * ws[k]
                mid[y * tw + tx] = acc
            }
        }
        for (ty in 0 until th) {
            val s = wy.start[ty]
            val ws = wy.weights[ty]
            for (tx in 0 until tw) {
                var acc = 0.0
                for (k in ws.indices) acc += mid[(s + k) * tw + tx] * ws[k]
                out[ty * tw + tx] = acc
            }
        }
        return out
    }

    private class AreaWeights(srcLen: Int, dstLen: Int) {
        val start = IntArray(dstLen)
        val weights: Array<DoubleArray>

        init {
            val scale = srcLen.toDouble() / dstLen
            weights = Array(dstLen) { t ->
                val a = t * scale
                val b = (t + 1) * scale
                val s = floor(a).toInt().coerceIn(0, srcLen - 1)
                val e = min(srcLen, max(s + 1, ceil(b - 1e-9).toInt()))
                start[t] = s
                val ws = DoubleArray(e - s) { k ->
                    val p = (s + k).toDouble()
                    max(0.0, min(b, p + 1) - max(a, p))
                }
                val sum = ws.sum()
                if (sum > 0) for (k in ws.indices) ws[k] /= sum
                ws
            }
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** DupeFinder's visual score: the share of the 64 hash bits that agree. */
    fun visualScore(bits: Int): Float = (HASH_BITS - bits) * 100f / HASH_BITS

    internal fun round1(v: Double): Float = ((v * 10).roundToInt() / 10.0).toFloat()
    internal fun round2(v: Double): Float = ((v * 100).roundToInt() / 100.0).toFloat()
}

/** Blur and detail measures (DupeFinder's `blur_measures`), on 0-255 grey values. */
object Sharpness {
    class Result(val score: Float, val detail: Float)

    /**
     * The score is the re-blur measure (Crété et al. 2007) on the 4 sharpest of 16 tiles of a
     * noise-filtered copy: noise looks like fine detail, so it is filtered first, by an amount that
     * depends on the measured noise level. Using the sharpest tiles means a sharp subject on a soft
     * background still scores well. The detail (Laplacian variance of the 4 sharpest tiles of the
     * unfiltered photo) ranks the frames of a burst, which share their noise.
     */
    fun measure(gray: IntArray, w: Int, h: Int, s: Scratch = Scratch()): Result {
        val rawTiles = tiles(gray, w, h) ?: return Result(Float.NaN, 0f)
        val lap = rawTiles.first
        val sigma = noiseSigma(gray, w, h)
        val a = when {
            sigma > 6 -> medianFilter(gray, w, h, 2, s.medFor(w, h))
            sigma > 2.5 -> medianFilter(gray, w, h, 1, s.medFor(w, h))
            else -> gray
        }
        val ft = tiles(a, w, h)!!.second
        val hs = (h - 2) / 4
        val ws = (w - 2) / 4
        if (hs + 2 < 12 || ws + 2 < 12) return Result(Float.NaN, ImageAnalysis.round2(lap))
        val top = ft.indices.sortedWith(compareBy({ ft[it] }, { it })).takeLast(4)
        var blur = 0.0
        for (t in top) {
            val r0 = (t / 4) * hs
            val c0 = (t % 4) * ws
            blur += reblur(a, w, r0, r0 + hs + 2, c0, c0 + ws + 2, s)
        }
        blur /= top.size
        return Result(ImageAnalysis.round1((1 - blur) * 100), ImageAnalysis.round2(lap))
    }

    private fun Scratch.medFor(w: Int, h: Int): IntArray {
        ensure(w, h)
        return med
    }

    /** Variance of the Laplacian in each tile of a 4x4 grid; first = mean of the 4 sharpest. */
    fun tiles(a: IntArray, w: Int, h: Int): Pair<Double, DoubleArray>? {
        if (h < 12 || w < 12) return null
        val hs = (h - 2) / 4
        val ws = (w - 2) / 4
        val out = DoubleArray(16)
        for (ti in 0 until 4) {
            for (tj in 0 until 4) {
                var sum = 0L
                var sum2 = 0L
                for (y in ti * hs until (ti + 1) * hs) {
                    var c = (y + 1) * w + tj * ws + 1
                    for (x in 0 until ws) {
                        val v = (a[c - 1] + a[c + 1] + a[c - w] + a[c + w] - 4 * a[c]).toLong()
                        sum += v
                        sum2 += v * v
                        c++
                    }
                }
                val cnt = (hs * ws).toDouble()
                val mean = sum / cnt
                out[ti * 4 + tj] = max(0.0, sum2 / cnt - mean * mean)
            }
        }
        val sorted = out.sortedArray()
        return Pair((sorted[12] + sorted[13] + sorted[14] + sorted[15]) / 4, out)
    }

    /** Immerkær's fast estimate of the noise standard deviation. */
    fun noiseSigma(a: IntArray, w: Int, h: Int): Double {
        if (h < 3 || w < 3) return 0.0
        var sum = 0L
        for (y in 1 until h - 1) {
            var c = y * w + 1
            for (x in 1 until w - 1) {
                val m = a[c - w - 1] - 2 * a[c - w] + a[c - w + 1] - 2 * a[c - 1] + 4 * a[c] -
                    2 * a[c + 1] + a[c + w - 1] - 2 * a[c + w] + a[c + w + 1]
                sum += abs(m)
                c++
            }
        }
        return sqrt(PI / 2) * sum / (6.0 * (w - 2) * (h - 2))
    }

    /**
     * Re-blur measure of rows r0 until r1 and columns c0 until c1: 0 = sharp ... 1 = blurry.
     * Re-blurring a sharp picture removes much of its variation; re-blurring a blurry one changes
     * little. Measured both ways, so motion blur counts too.
     */
    fun reblur(a: IntArray, w: Int, r0: Int, r1: Int, c0: Int, c1: Int, s: Scratch = Scratch()): Double {
        val th = r1 - r0
        val tw = c1 - c0
        var worst = 0.0
        var measured = false
        val v = s.line
        val b = s.blurred
        for (axis in 0..1) {
            val len = if (axis == 0) th else tw
            val lines = if (axis == 0) tw else th
            if (len < 12) continue
            var sf = 0.0
            var sv = 0.0
            for (ln in 0 until lines) {
                for (i in 0 until len) {
                    v[i] = if (axis == 0) a[(r0 + i) * w + c0 + ln].toDouble() else a[(r0 + ln) * w + c0 + i].toDouble()
                }
                boxBlur9(v, len, b)
                for (i in 0 until len - 1) {
                    val df = abs(v[i + 1] - v[i])
                    val db = abs(b[i + 1] - b[i])
                    sf += df
                    sv += max(0.0, df - db)
                }
            }
            if (sf <= 0) continue          // no change along this axis (a barcode): use the other
            worst = max(worst, (sf - sv) / sf)
            measured = true
        }
        return if (measured) worst else 1.0
    }

    /** Mean of 9 neighbours along the first n values of a line, repeating the end values past the ends. */
    private fun boxBlur9(v: DoubleArray, n: Int, out: DoubleArray) {
        var acc = 0.0
        for (k in -4..4) acc += v[k.coerceIn(0, n - 1)]
        out[0] = acc / 9
        for (i in 1 until n) {
            acc += v[(i + 4).coerceAtMost(n - 1)] - v[(i - 5).coerceAtLeast(0)]
            out[i] = acc / 9
        }
    }

    /**
     * Median filter of radius r (window 2r+1) on 0-255 values, edges repeated, into [out]. The 3 x 3
     * window, which most photos need, has a quicker way of its own.
     */
    fun medianFilter(src: IntArray, w: Int, h: Int, r: Int, out: IntArray = IntArray(w * h)): IntArray =
        if (r == 1) median3(src, w, h, out) else medianByHistogram(src, w, h, r, out)

    /**
     * The 3 x 3 median: of the nine values, the middle one of (the largest of the columns' smallest,
     * the middle of their middles, the smallest of their largest). Every column of three is sorted
     * once and used by the three windows it is in, with nothing but minima and maxima: a third of
     * the time of the sliding histogram, and the same values.
     */
    private fun median3(src: IntArray, w: Int, h: Int, out: IntArray): IntArray {
        for (y in 0 until h) {
            val r0 = (if (y > 0) y - 1 else 0) * w
            val r1 = y * w
            val r2 = (if (y < h - 1) y + 1 else h - 1) * w
            // The sorted columns left of, at and right of the pixel. Left of the first is the first again.
            var a = src[r0]
            var b = src[r1]
            var c = src[r2]
            var hi1 = max(a, b)
            var lo1 = min(a, b)
            var mid1 = min(hi1, c)
            hi1 = max(hi1, c)
            var t = lo1
            lo1 = min(t, mid1)
            mid1 = max(t, mid1)
            var lo0 = lo1
            var mid0 = mid1
            var hi0 = hi1
            for (x in 0 until w) {
                val xn = if (x < w - 1) x + 1 else x
                a = src[r0 + xn]
                b = src[r1 + xn]
                c = src[r2 + xn]
                var hi2 = max(a, b)
                var lo2 = min(a, b)
                var mid2 = min(hi2, c)
                hi2 = max(hi2, c)
                t = lo2
                lo2 = min(t, mid2)
                mid2 = max(t, mid2)
                val low = max(lo0, max(lo1, lo2))
                val high = min(hi0, min(hi1, hi2))
                val middle = max(min(mid0, mid1), min(max(mid0, mid1), mid2))
                out[r1 + x] = max(min(low, middle), min(max(low, middle), high))
                lo0 = lo1
                mid0 = mid1
                hi0 = hi1
                lo1 = lo2
                mid1 = mid2
                hi1 = hi2
            }
        }
        return out
    }

    /** Any window: a histogram of the window slides along each row, and the median follows it. */
    internal fun medianByHistogram(src: IntArray, w: Int, h: Int, r: Int, out: IntArray = IntArray(w * h)): IntArray {
        val k = 2 * r + 1
        val rank = (k * k) / 2
        val hist = IntArray(256)
        val rowAt = IntArray(k)
        for (y in 0 until h) {
            hist.fill(0)
            for (dy in -r..r) {
                val row = (y + dy).coerceIn(0, h - 1) * w
                rowAt[dy + r] = row
                for (dx in -r..r) hist[src[row + dx.coerceIn(0, w - 1)]]++
            }
            var m = 0
            var lt = 0
            while (lt + hist[m] <= rank) {
                lt += hist[m]
                m++
            }
            out[y * w] = m
            for (x in 1 until w) {
                val xOut = (x - r - 1).coerceIn(0, w - 1)
                val xIn = (x + r).coerceIn(0, w - 1)
                var i = 0
                while (i < k) {
                    val row = rowAt[i]
                    val vo = src[row + xOut]
                    val vi = src[row + xIn]
                    hist[vo]--
                    hist[vi]++
                    // one fewer below the median for a value that leaves, one more for one that comes (no branches)
                    lt += ((vi - m) ushr 31) - ((vo - m) ushr 31)
                    i++
                }
                while (lt > rank) {
                    m--
                    lt -= hist[m]
                }
                while (lt + hist[m] <= rank) {
                    lt += hist[m]
                    m++
                }
                out[y * w + x] = m
            }
        }
        return out
    }
}

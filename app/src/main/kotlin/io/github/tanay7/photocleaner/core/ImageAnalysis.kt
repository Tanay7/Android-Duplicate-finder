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
    /** A featureless picture (a blank page, a black frame): never matched on its looks. */
    val flat: Boolean,
    /** Average colour (YCbCr chroma) and how colourful the photo is. */
    val cb: Float,
    val cr: Float,
    val saturation: Float,
    /** 0 (blurry) to 100 (sharp); NaN when the photo is too small to measure. */
    val sharpness: Float,
    /** Laplacian detail of the 4 sharpest of 16 tiles: ranks the frames of a burst. */
    val detail: Float,
    /** Median brightness (0-255) and the shares of near-black and near-white pixels. */
    val median: Float,
    val black: Float,
    val white: Float,
)

object ImageAnalysis {
    /** Photos are analysed reduced to at most this long side. */
    const val REDUCED_SIZE = 1024
    const val HASH_BITS = 64
    private const val FLAT_STD = 2.0
    private const val VARIANT_COUNT = 4

    /**
     * Analyses one reduced photo. [argb] holds unpremultiplied ARGB pixels row by row (what
     * `Bitmap.getPixels` returns); transparent pixels are seen over white.
     */
    fun analyze(argb: IntArray, w: Int, h: Int): ImageFeatures {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad image size ${w}x$h" }
        val n = w * h
        val gray = IntArray(n)
        var sumCb = 0.0
        var sumCr = 0.0
        var sumSat = 0.0
        for (i in 0 until n) {
            val p = argb[i]
            val a = p ushr 24
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            if (a < 255) {
                r = (r * a + 255 * (255 - a) + 127) / 255
                g = (g * a + 255 * (255 - a) + 127) / 255
                b = (b * a + 255 * (255 - a) + 127) / 255
            }
            gray[i] = (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16   // ITU-R 601 luma, like PIL's "L"
            val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
            sumCb += cb
            sumCr += cr
            sumSat += abs(cb - 128.0) + abs(cr - 128.0)
        }
        val ph = phash(gray, w, h)
        val blur = Sharpness.measure(gray, w, h)
        val exp = exposure(gray)
        return ImageFeatures(
            phash = ph.hash,
            variants = ph.variants,
            cropHash = centreCropHash(gray, w, h),
            flat = isFlat(ph.hash, ph.std) || min(w, h) < 8,
            cb = (sumCb / n).toFloat(),
            cr = (sumCr / n).toFloat(),
            saturation = (sumSat / n).toFloat(),
            sharpness = blur.score,
            detail = blur.detail,
            median = exp[0],
            black = exp[1],
            white = exp[2],
        )
    }

    class PHash(val hash: Long, val std: Double, val variants: LongArray)

    /** pHash of the region (x0, y0, cw, ch) of a grey image, plus its turned and mirrored variants. */
    fun phash(gray: IntArray, w: Int, h: Int, x0: Int = 0, y0: Int = 0, cw: Int = w, ch: Int = h): PHash {
        val x = resizeArea(gray, w, x0, y0, cw, ch, 32, 32)
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var sq = 0.0
        for (v in x) sq += (v - mean) * (v - mean)
        val std = sqrt(sq / x.size)

        // low = C · X · Cᵀ, first 8x8 coefficients of the unnormalised DCT-II.
        val t = DoubleArray(8 * 32)
        for (k in 0 until 8) {
            for (m in 0 until 32) {
                val c = DCT[k * 32 + m]
                val row = m * 32
                val out = k * 32
                for (col in 0 until 32) t[out + col] += c * x[row + col]
            }
        }
        val low = DoubleArray(64)
        for (k in 0 until 8) {
            for (l in 0 until 8) {
                var s = 0.0
                for (col in 0 until 32) s += t[k * 32 + col] * DCT[l * 32 + col]
                low[k * 8 + l] = s
            }
        }
        // A flip or a quarter turn only swaps axes and changes signs in the DCT, so every variant
        // comes from this one transform.
        val v = LongArray(VARIANT_COUNT)
        val tmp = DoubleArray(64)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[j * 8 + i] * sign(j)
        v[0] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[i * 8 + j] * sign(i) * sign(j)
        v[1] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[j * 8 + i] * sign(i)
        v[2] = bits64(tmp)
        for (i in 0 until 8) for (j in 0 until 8) tmp[i * 8 + j] = low[i * 8 + j] * sign(j)
        v[3] = bits64(tmp)
        return PHash(bits64(low), std, v)
    }

    private fun sign(i: Int) = if (i % 2 == 0) 1.0 else -1.0

    private val DCT = DoubleArray(8 * 32) { idx ->
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

    /** pHash of the centre square (wide or tall photos) or of the central 80 % (squarish ones). */
    fun centreCropHash(gray: IntArray, w: Int, h: Int): Long {
        return if (max(w, h) > 1.15 * min(w, h)) {
            val side = min(w, h)
            phash(gray, w, h, (w - side) / 2, (h - side) / 2, side, side).hash
        } else {
            phash(gray, w, h, w / 10, h / 10, w - 2 * (w / 10), h - 2 * (h / 10)).hash
        }
    }

    /** Median brightness and the shares of pixels at or below 5 and at or above 250. */
    fun exposure(gray: IntArray): FloatArray {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val n = gray.size
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
     * which way the photo is turned.
     */
    fun resizeArea(src: IntArray, w: Int, x0: Int, y0: Int, cw: Int, ch: Int, tw: Int, th: Int): DoubleArray {
        val wx = AreaWeights(cw, tw)
        val wy = AreaWeights(ch, th)
        val mid = DoubleArray(ch * tw)
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
        val out = DoubleArray(th * tw)
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

/** Blur and detail measures (DupeFinder's `blur_measures`). */
object Sharpness {
    class Result(val score: Float, val detail: Float)

    /**
     * The score is the re-blur measure (Crété et al. 2007) on the 4 sharpest of 16 tiles of a
     * noise-filtered copy: noise looks like fine detail, so it is filtered first, by an amount that
     * depends on the measured noise level. Using the sharpest tiles means a sharp subject on a soft
     * background still scores well. The detail (Laplacian variance of the 4 sharpest tiles of the
     * unfiltered photo) ranks the frames of a burst, which share their noise.
     */
    fun measure(gray: IntArray, w: Int, h: Int): Result {
        val raw = FloatArray(gray.size) { gray[it].toFloat() }
        val rawTiles = tiles(raw, w, h) ?: return Result(Float.NaN, 0f)
        val lap = rawTiles.first
        val sigma = noiseSigma(raw, w, h)
        val filtered = when {
            sigma > 6 -> medianFilter(gray, w, h, 2)
            sigma > 2.5 -> medianFilter(gray, w, h, 1)
            else -> gray
        }
        val a = if (filtered === gray) raw else FloatArray(filtered.size) { filtered[it].toFloat() }
        val ft = tiles(a, w, h)!!.second
        val hs = (h - 2) / 4
        val ws = (w - 2) / 4
        if (hs + 2 < 12 || ws + 2 < 12) return Result(Float.NaN, ImageAnalysis.round2(lap))
        val top = ft.indices.sortedWith(compareBy({ ft[it] }, { it })).takeLast(4)
        var blur = 0.0
        for (t in top) {
            val r0 = (t / 4) * hs
            val c0 = (t % 4) * ws
            blur += reblur(a, w, r0, r0 + hs + 2, c0, c0 + ws + 2)
        }
        blur /= top.size
        return Result(ImageAnalysis.round1((1 - blur) * 100), ImageAnalysis.round2(lap))
    }

    /** Variance of the Laplacian in each tile of a 4x4 grid; first = mean of the 4 sharpest. */
    fun tiles(a: FloatArray, w: Int, h: Int): Pair<Double, DoubleArray>? {
        if (h < 12 || w < 12) return null
        val hs = (h - 2) / 4
        val ws = (w - 2) / 4
        val out = DoubleArray(16)
        for (ti in 0 until 4) {
            for (tj in 0 until 4) {
                var s = 0.0
                var s2 = 0.0
                for (y in ti * hs until (ti + 1) * hs) {
                    var c = (y + 1) * w + tj * ws + 1
                    for (x in 0 until ws) {
                        val v = (a[c - 1] + a[c + 1] + a[c - w] + a[c + w] - 4 * a[c]).toDouble()
                        s += v
                        s2 += v * v
                        c++
                    }
                }
                val cnt = (hs * ws).toDouble()
                val mean = s / cnt
                out[ti * 4 + tj] = max(0.0, s2 / cnt - mean * mean)
            }
        }
        val sorted = out.sortedArray()
        return Pair((sorted[12] + sorted[13] + sorted[14] + sorted[15]) / 4, out)
    }

    /** Immerkær's fast estimate of the noise standard deviation. */
    fun noiseSigma(a: FloatArray, w: Int, h: Int): Double {
        if (h < 3 || w < 3) return 0.0
        var sum = 0.0
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
    fun reblur(a: FloatArray, w: Int, r0: Int, r1: Int, c0: Int, c1: Int): Double {
        val th = r1 - r0
        val tw = c1 - c0
        var worst = 0.0
        var measured = false
        for (axis in 0..1) {
            val len = if (axis == 0) th else tw
            val lines = if (axis == 0) tw else th
            if (len < 12) continue
            val v = DoubleArray(len)
            var sf = 0.0
            var sv = 0.0
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    v[i] = if (axis == 0) a[(r0 + i) * w + c0 + line].toDouble() else a[(r0 + line) * w + c0 + i].toDouble()
                }
                val b = boxBlur9(v)
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

    /** Mean of 9 neighbours along a line, repeating the end values past the ends. */
    private fun boxBlur9(v: DoubleArray): DoubleArray {
        val n = v.size
        val out = DoubleArray(n)
        var acc = 0.0
        for (k in -4..4) acc += v[k.coerceIn(0, n - 1)]
        out[0] = acc / 9
        for (i in 1 until n) {
            acc += v[(i + 4).coerceAtMost(n - 1)] - v[(i - 5).coerceAtLeast(0)]
            out[i] = acc / 9
        }
        return out
    }

    /** Median filter of radius r (window 2r+1) on 0-255 values, edges repeated (a sliding histogram). */
    fun medianFilter(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val k = 2 * r + 1
        val rank = (k * k) / 2
        val hist = IntArray(256)
        for (y in 0 until h) {
            hist.fill(0)
            for (dy in -r..r) {
                val row = (y + dy).coerceIn(0, h - 1) * w
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
                for (dy in -r..r) {
                    val row = (y + dy).coerceIn(0, h - 1) * w
                    val vo = src[row + xOut]
                    hist[vo]--
                    if (vo < m) lt--
                    val vi = src[row + xIn]
                    hist[vi]++
                    if (vi < m) lt++
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

package io.github.tanay7.photocleaner.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Synthetic photos for the tests: ARGB pixel arrays with their size. */
class Pic(val w: Int, val h: Int, val px: IntArray) {
    fun features() = ImageAnalysis.analyze(px, w, h)
}

object Scenes {
    /** A random scene: a soft gradient, coloured shapes and fine texture, like a busy photo. */
    fun scene(seed: Int, w: Int = 640, h: Int = 480, colour: Boolean = true): Pic {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        val c0 = rgb(rnd, colour)
        val c1 = rgb(rnd, colour)
        for (y in 0 until h) for (x in 0 until w) {
            val t = (x + y).toDouble() / (w + h)
            px[y * w + x] = mix(c0, c1, t)
        }
        repeat(14) {
            val c = rgb(rnd, colour)
            val cx = rnd.nextInt(w)
            val cy = rnd.nextInt(h)
            val rx = 20 + rnd.nextInt(w / 3)
            val ry = 20 + rnd.nextInt(h / 3)
            val ellipse = rnd.nextBoolean()
            for (y in max(0, cy - ry) until min(h, cy + ry)) for (x in max(0, cx - rx) until min(w, cx + rx)) {
                val dx = (x - cx).toDouble() / rx
                val dy = (y - cy).toDouble() / ry
                if (!ellipse || dx * dx + dy * dy <= 1) {
                    // a fine stripe texture inside each shape gives the photo detail to measure
                    val tex = if (((x / 3) + (y / 3)) % 2 == 0) 18 else -18
                    px[y * w + x] = shade(c, tex)
                }
            }
        }
        return Pic(w, h, px)
    }

    fun uniform(v: Int, w: Int = 400, h: Int = 300) = Pic(w, h, IntArray(w * h) { argb(v, v, v) })

    /** Bilinear resize. */
    fun resize(p: Pic, tw: Int, th: Int): Pic {
        val out = IntArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val sx = ((x + 0.5) * p.w / tw - 0.5).coerceIn(0.0, p.w - 1.0)
            val sy = ((y + 0.5) * p.h / th - 0.5).coerceIn(0.0, p.h - 1.0)
            val x0 = sx.toInt()
            val y0 = sy.toInt()
            val x1 = min(x0 + 1, p.w - 1)
            val y1 = min(y0 + 1, p.h - 1)
            val fx = sx - x0
            val fy = sy - y0
            out[y * tw + x] = channelMap { ch ->
                val a = comp(p.px[y0 * p.w + x0], ch) * (1 - fx) + comp(p.px[y0 * p.w + x1], ch) * fx
                val b = comp(p.px[y1 * p.w + x0], ch) * (1 - fx) + comp(p.px[y1 * p.w + x1], ch) * fx
                (a * (1 - fy) + b * fy).roundToInt()
            }
        }
        return Pic(tw, th, out)
    }

    /** Turned a quarter clockwise. */
    fun rotate90(p: Pic): Pic {
        val out = IntArray(p.w * p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) out[x * p.h + (p.h - 1 - y)] = p.px[y * p.w + x]
        return Pic(p.h, p.w, out)
    }

    fun rotate180(p: Pic) = Pic(p.w, p.h, IntArray(p.w * p.h) { p.px[p.w * p.h - 1 - it] })

    fun mirror(p: Pic): Pic {
        val out = IntArray(p.w * p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) out[y * p.w + (p.w - 1 - x)] = p.px[y * p.w + x]
        return Pic(p.w, p.h, out)
    }

    fun crop(p: Pic, x0: Int, y0: Int, cw: Int, ch: Int): Pic {
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) out[y * cw + x] = p.px[(y0 + y) * p.w + x0 + x]
        return Pic(cw, ch, out)
    }

    /** Box blur of radius r, repeated twice (close to a Gaussian). */
    fun blur(p: Pic, r: Int): Pic {
        var cur = p.px
        repeat(2) {
            val tmp = IntArray(cur.size)
            for (y in 0 until p.h) for (x in 0 until p.w) {
                tmp[y * p.w + x] = channelMap { ch ->
                    var s = 0
                    for (k in -r..r) s += comp(cur[y * p.w + (x + k).coerceIn(0, p.w - 1)], ch)
                    s / (2 * r + 1)
                }
            }
            val out = IntArray(cur.size)
            for (y in 0 until p.h) for (x in 0 until p.w) {
                out[y * p.w + x] = channelMap { ch ->
                    var s = 0
                    for (k in -r..r) s += comp(tmp[(y + k).coerceIn(0, p.h - 1) * p.w + x], ch)
                    s / (2 * r + 1)
                }
            }
            cur = out
        }
        return Pic(p.w, p.h, cur)
    }

    fun noise(p: Pic, sigma: Double, seed: Int): Pic {
        val rnd = java.util.Random(seed.toLong())
        return Pic(p.w, p.h, IntArray(p.px.size) { i ->
            val n = (rnd.nextGaussian() * sigma).roundToInt()
            channelMap { ch -> comp(p.px[i], ch) + n }
        })
    }

    fun scale(p: Pic, f: Double) = Pic(p.w, p.h, IntArray(p.px.size) { i -> channelMap { ch -> (comp(p.px[i], ch) * f).roundToInt() } })

    /** Shifted right by dx pixels, the edge column repeated: a frame of a burst a moment later. */
    fun shift(p: Pic, dx: Int): Pic {
        val out = IntArray(p.px.size)
        for (y in 0 until p.h) for (x in 0 until p.w) out[y * p.w + x] = p.px[y * p.w + (x - dx).coerceIn(0, p.w - 1)]
        return Pic(p.w, p.h, out)
    }

    fun grey(p: Pic) = Pic(p.w, p.h, IntArray(p.px.size) { i ->
        val c = p.px[i]
        val l = (comp(c, 0) * 299 + comp(c, 1) * 587 + comp(c, 2) * 114) / 1000
        argb(l, l, l)
    })

    /** A colour cast: same layout, other colours. */
    fun tint(p: Pic, dr: Int, dg: Int, db: Int) = Pic(p.w, p.h, IntArray(p.px.size) { i ->
        val c = p.px[i]
        argb(comp(c, 0) + dr, comp(c, 1) + dg, comp(c, 2) + db)
    })

    private fun rgb(rnd: Random, colour: Boolean): Int {
        if (!colour) {
            val v = 30 + rnd.nextInt(200)
            return argb(v, v, v)
        }
        return argb(30 + rnd.nextInt(200), 30 + rnd.nextInt(200), 30 + rnd.nextInt(200))
    }

    private fun mix(a: Int, b: Int, t: Double) = channelMap { ch -> (comp(a, ch) * (1 - t) + comp(b, ch) * t).roundToInt() }
    private fun shade(c: Int, d: Int) = channelMap { ch -> comp(c, ch) + d }
    private fun comp(c: Int, ch: Int) = (c shr (16 - 8 * ch)) and 0xFF
    private inline fun channelMap(f: (Int) -> Int): Int = argb(f(0), f(1), f(2))
    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

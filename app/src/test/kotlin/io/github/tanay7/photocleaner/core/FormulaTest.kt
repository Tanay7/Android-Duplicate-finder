package io.github.tanay7.photocleaner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/** Every formula and cut-off, at its exact values and on either side of each boundary. */
class FormulaTest {
    private fun solid(r: Int, g: Int, b: Int, w: Int = 32, h: Int = 32) = Pic(w, h, IntArray(w * h) { Scenes.argb(r, g, b) })

    @Test fun brightnessAndColourAreTheJpegFormulas() {
        // luma like PIL's "L" ((r*19595 + g*38470 + b*7471 + 0x8000) >> 16), chroma the JPEG YCbCr
        val f = solid(200, 100, 50).features()
        assertEquals(124f, f.mean, 0f)                                  // 200*.299 + 100*.587 + 50*.114 = 124.2
        assertEquals(86.1264f, f.cb, 1e-3f)                             // 128 - .168736*200 - .331264*100 + .5*50
        assertEquals(182.0656f, f.cr, 1e-3f)                            // 128 + .5*200 - .418688*100 - .081312*50
        assertEquals(95.9392f, f.saturation, 1e-3f)                     // |cb - 128| + |cr - 128|
        assertEquals(0f, f.contrast, 0f)
        assertEquals(124f, f.median, 0f)
        assertEquals(0f, f.black, 0f)
        assertEquals(0f, f.white, 0f)
        val g = f.grid!!
        for (cell in 0 until 256) {
            assertEquals(124, g[cell].toInt() and 0xFF)
            assertEquals(86, g[256 + cell].toInt() and 0xFF)
            assertEquals(182, g[512 + cell].toInt() and 0xFF)
        }
        // white and black: no colour at all, every pixel at the ends
        val white = solid(255, 255, 255).features()
        assertEquals(255f, white.mean, 0f)
        assertEquals(128f, white.cb, 1e-4f)
        assertEquals(128f, white.cr, 1e-4f)
        assertEquals(1f, white.white, 0f)
        assertEquals(1f, solid(0, 0, 0).features().black, 0f)
        // the greys keep their level
        for (v in 0..255) assertEquals(v.toFloat(), solid(v, v, v, 16, 16).features().mean, 0f)
    }

    @Test fun contrastIsTheStandardDeviationOfTheBrightness() {
        // half black, half white: mean 127.5, standard deviation 127.5
        val p = Pic(32, 32, IntArray(32 * 32) { if (it % 32 < 16) Scenes.argb(0, 0, 0) else Scenes.argb(255, 255, 255) })
        val f = p.features()
        assertEquals(127.5f, f.mean, 1e-4f)
        assertEquals(127.5f, f.contrast, 1e-3f)
        assertEquals(127.5f, f.median, 0f)
        assertEquals(0.5f, f.black, 0f)
        assertEquals(0.5f, f.white, 0f)
    }

    @Test fun exposureCountsAtAndBelow5AndAtAndAbove250() {
        val e = ImageAnalysis.exposure(intArrayOf(5, 6, 249, 250))
        assertEquals((6 + 249) / 2f, e[0], 0f)                        // even count: the mean of the middle two
        assertEquals(0.25f, e[1], 0f)
        assertEquals(0.25f, e[2], 0f)
        assertEquals(6f, ImageAnalysis.exposure(intArrayOf(9, 6, 0))[0], 0f)    // odd count: the middle one
        assertEquals(10f, ImageAnalysis.exposure(intArrayOf(10, 99, 0), n = 1)[0], 0f)   // only the first n
    }

    @Test fun areaResizeWeighsEverySourcePixelByTheShareItCovers() {
        // 3 pixels to 2: [0, 3, 6] -> (0*1 + 3*0.5) / 1.5 and (3*0.5 + 6*1) / 1.5
        assertArrayEquals(doubleArrayOf(1.0, 5.0), ImageAnalysis.resizeArea(intArrayOf(0, 3, 6), 3, 0, 0, 3, 1, 2, 1), 1e-12)
        // 1 pixel to 4: copied
        assertArrayEquals(doubleArrayOf(7.0, 7.0, 7.0, 7.0), ImageAnalysis.resizeArea(intArrayOf(7), 1, 0, 0, 1, 1, 4, 1), 1e-12)
        // a region: rows 1..2, columns 1..2 of a 4 x 4 picture, to 1 x 1: the mean of the 4 pixels
        val src = IntArray(16) { it }
        assertArrayEquals(doubleArrayOf((5 + 6 + 9 + 10) / 4.0), ImageAnalysis.resizeArea(src, 4, 1, 1, 2, 2, 1, 1), 1e-12)
    }

    @Test fun thePerceptualHashAndTheFineFingerprintAreTheDctOfThe32x32Copy() {
        val rnd = Random(9)
        repeat(5) {
            val gray = IntArray(32 * 32) { rnd.nextInt(256) }
            // the unnormalised DCT-II, straight from its definition
            val d = Array(16) { k ->
                DoubleArray(16) { l ->
                    var s = 0.0
                    for (m in 0 until 32) for (n in 0 until 32) {
                        s += gray[m * 32 + n] * cos(PI * k * (2 * m + 1) / 64) * cos(PI * l * (2 * n + 1) / 64)
                    }
                    s
                }
            }
            val low = DoubleArray(64) { d[it / 8][it % 8] }
            val median = low.sorted().let { (it[31] + it[32]) / 2 }
            var hash = 0L
            for (i in 0 until 64) if (low[i] > median) hash = hash or (1L shl (63 - i))
            val fine = LongArray(4)
            for (b in 1 until 256) if (d[b / 16][b % 16] > 0) fine[b ushr 6] = fine[b ushr 6] or (1L shl (b and 63))
            val ph = ImageAnalysis.phash(gray, 32, 32)
            assertEquals(hash, ph.hash)
            assertArrayEquals(fine, ph.fine)
            assertEquals(255, ImageAnalysis.FINE_BITS)
            assertEquals(0, ImageAnalysis.fineDistance(ph.fine, fine))
        }
        assertEquals(100f, ImageAnalysis.fineScore(0), 0f)
        assertEquals(0f, ImageAnalysis.fineScore(255), 0f)
        assertEquals(100f * 223 / 255, ImageAnalysis.fineScore(32), 1e-4f)
    }

    @Test fun aPictureFarWiderThanTallIsCroppedToItsCentreSquare() {
        val rnd = Random(4)
        fun crop(w: Int, h: Int): Pair<ImageAnalysis.PHash, IntArray> {
            val gray = IntArray(w * h) { rnd.nextInt(256) }
            return ImageAnalysis.centreCrop(gray, w, h) to gray
        }
        // 116 x 100 is wider than 1.15 to 1: the centre 100 x 100 square
        var (c, gray) = crop(116, 100)
        assertEquals(ImageAnalysis.phash(gray, 116, 100, 8, 0, 100, 100).hash, c.hash)
        // 114 x 100 isn't: the central 80 % (11 px off each side, 10 off top and bottom)
        val r = crop(114, 100)
        c = r.first
        gray = r.second
        assertEquals(ImageAnalysis.phash(gray, 114, 100, 11, 10, 92, 80).hash, c.hash)
        // tall photos the same way
        val t = crop(100, 200)
        assertEquals(ImageAnalysis.phash(t.second, 100, 200, 0, 50, 100, 100).hash, t.first.hash)
    }

    @Test fun flatMeansAlmostNoVariationOrALopsidedHash() {
        assertTrue(ImageAnalysis.isFlat(0x00FF00FF00FF00FFL, 1.99))
        assertFalse(ImageAnalysis.isFlat(0x00FF00FF00FF00FFL, 2.0))
        assertTrue(ImageAnalysis.isFlat(0x7FL, 50.0))                     // 7 bits set
        assertFalse(ImageAnalysis.isFlat(0xFFL, 50.0))                    // 8
        assertFalse(ImageAnalysis.isFlat(-1L ushr 8, 50.0))               // 56
        assertTrue(ImageAnalysis.isFlat(-1L ushr 7, 50.0))                // 57
    }

    @Test fun differingInPartIsJudgedAgainstTheMeanDifference() {
        val a = ByteArray(ImageAnalysis.GRID_BYTES) { 100 }
        fun with(plane: Int, d: Int) = a.copyOf().also { it[plane * 256 + 77] = (100 + d).toByte() }
        // one cell off by d moves the mean by d/256: it counts d - d/256 from it
        assertFalse(ImageAnalysis.differsInPart(a, with(0, 6)))           // 5.98 < 6
        assertTrue(ImageAnalysis.differsInPart(a, with(0, 7)))            // 6.97
        assertFalse(ImageAnalysis.differsInPart(a, with(1, 5)))           // 4.98 < 5
        assertTrue(ImageAnalysis.differsInPart(a, with(2, 6)))            // 5.98
        assertTrue(ImageAnalysis.differsInPart(a, with(0, -7)))           // darker counts the same
        // the same shift everywhere is no difference in part, however large
        assertFalse(ImageAnalysis.differsInPart(a, ByteArray(ImageAnalysis.GRID_BYTES) { 140.toByte() }))
        // wider limits for small pictures
        assertFalse(ImageAnalysis.differsInPart(a, with(0, 12), slack = 2.0))   // 11.95 < 12
        assertTrue(ImageAnalysis.differsInPart(a, with(0, 13), slack = 2.0))    // 12.95
        assertEquals(1.0, ImageAnalysis.cellSlack(400), 0.0)
        assertEquals((400.0 / 399) * (400.0 / 399), ImageAnalysis.cellSlack(399), 1e-12)
        assertEquals(8.0, ImageAnalysis.cellSlack(141), 0.0)              // (400/141)^2 = 8.05: capped
        assertEquals((400.0 / 142) * (400.0 / 142), ImageAnalysis.cellSlack(142), 1e-12)
    }

    @Test fun sharpnessNeedsTilesOfAtLeast12Pixels() {
        val rnd = Random(1)
        fun noisy(w: Int, h: Int) = IntArray(w * h) { rnd.nextInt(256) }
        // tiles of (h - 2) / 4 rows, plus 2, must be at least 12 to be re-blurred: 42 px and up
        assertTrue(Sharpness.measure(noisy(100, 41), 100, 41).score.isNaN())
        assertFalse(Sharpness.measure(noisy(100, 42), 100, 42).score.isNaN())
        assertTrue(Sharpness.measure(noisy(41, 100), 41, 100).score.isNaN())
        // too small for tiles at all: no detail either
        assertEquals(null, Sharpness.tiles(noisy(11, 50), 11, 50))
        assertNotNull(Sharpness.tiles(noisy(12, 12), 12, 12))
        // a line that never changes can't be judged: blurry
        assertEquals(1.0, Sharpness.reblur(IntArray(40 * 40) { 90 }, 40, 0, 40, 0, 40), 0.0)
        // a hard edge every 2 pixels is as sharp as it gets: the re-blur removes nearly all of it
        val stripes = IntArray(40 * 40) { if ((it % 40) / 2 % 2 == 0) 0 else 255 }
        assertTrue(Sharpness.reblur(stripes, 40, 0, 40, 0, 40) < 0.2)
    }

    @Test fun theNoiseEstimateIsImmerkaersFormula() {
        // a single bright pixel: |4 * 255| at it, |-2 * 255| at its 4 side neighbours, 255 at its 4 corners
        val a = IntArray(9 * 9).also { it[4 * 9 + 4] = 255 }
        val sum = 4 * 255 + 4 * 2 * 255 + 4 * 255
        assertEquals(Math.sqrt(PI / 2) * sum / (6.0 * 7 * 7), Sharpness.noiseSigma(a, 9, 9), 1e-9)
        assertEquals(0.0, Sharpness.noiseSigma(IntArray(4), 2, 2), 0.0)
    }

    @Test fun theLimitsFollowTheChosenStrictness() {
        fun check(bits: Int, link: Int, sure: Int, sureFine: Int) {
            val o = MatchOptions(maxBits = bits)
            assertEquals("link $bits", link, o.linkFine)
            assertEquals("sure $bits", sure, o.sureBits)
            assertEquals("sure fine $bits", sureFine, o.sureFine)
        }
        check(0, 16, 0, 0)
        check(4, 16, 4, 16)                 // strict
        check(5, 48, 5, 20)
        check(8, 48, 8, 32)                 // normal
        check(12, 48, 8, 32)                // loose: finds more, ticks no more
        check(32, 48, 8, 32)
    }
}

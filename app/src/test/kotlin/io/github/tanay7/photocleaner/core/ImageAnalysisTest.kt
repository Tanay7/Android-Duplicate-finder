package io.github.tanay7.photocleaner.core

import io.github.tanay7.photocleaner.core.ImageAnalysis.hamming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageAnalysisTest {
    private val base = Scenes.scene(1, 800, 600)
    private val f = base.features()

    @Test fun differentPhotosAreFarApart() {
        for (seed in 2..12) {
            val d = hamming(f.phash, Scenes.scene(seed, 800, 600).features().phash)
            assertTrue("scene $seed only $d bits away", d > 16)
        }
    }

    @Test fun resizedAndRecompressedCopiesStayClose() {
        assertTrue(hamming(f.phash, Scenes.resize(base, 400, 300).features().phash) <= 3)
        assertTrue(hamming(f.phash, Scenes.resize(base, 1024, 768).features().phash) <= 3)
        // a re-save: a little noise and a brightness change
        assertTrue(hamming(f.phash, Scenes.noise(Scenes.scale(base, 0.92), 3.0, 7).features().phash) <= 4)
    }

    @Test fun variantsAreTheHashesOfTheTurnedAndMirroredPhoto() {
        val r90 = Scenes.rotate90(base).features().phash
        val r180 = Scenes.rotate180(base).features().phash
        val r270 = Scenes.rotate90(Scenes.rotate180(base)).features().phash
        val mir = Scenes.mirror(base).features().phash
        val v = f.variants
        assertTrue(minOf(hamming(r90, v[0]), hamming(r90, v[2])) <= 2)
        assertTrue(minOf(hamming(r270, v[0]), hamming(r270, v[2])) <= 2)
        assertTrue(hamming(r180, v[1]) <= 2)
        assertTrue(hamming(mir, v[3]) <= 2)
        assertTrue("turning should change the plain hash", hamming(r90, f.phash) > 8)
    }

    @Test fun fineFingerprintFollowsTurnsAndFlips() {
        val fine = ImageAnalysis::fineDistance
        assertTrue(fine(Scenes.rotate180(base).features().fine, ImageAnalysis.fineVariant(f.fine, 1)) <= 8)
        assertTrue(fine(Scenes.mirror(base).features().fine, ImageAnalysis.fineVariant(f.fine, 3)) <= 8)
        val r90 = Scenes.rotate90(base).features().fine
        val r270 = Scenes.rotate90(Scenes.rotate180(base)).features().fine
        // the same variant that the pHash of the turned photo matches
        val v90 = if (hamming(Scenes.rotate90(base).features().phash, f.variants[0]) <= 2) 0 else 2
        assertTrue(fine(r90, ImageAnalysis.fineVariant(f.fine, v90)) <= 8)
        assertTrue(fine(r270, ImageAnalysis.fineVariant(f.fine, 2 - v90)) <= 8)
        assertTrue("turning should change the plain fingerprint", fine(r90, f.fine) > 64)
        // turning back gives the fingerprint back, bit for bit
        assertEquals(0, fine(ImageAnalysis.fineVariant(ImageAnalysis.fineVariant(f.fine, 0), 2), f.fine))
        assertEquals(0, fine(ImageAnalysis.fineVariant(ImageAnalysis.fineVariant(f.fine, 3), 3), f.fine))
    }

    @Test fun fineFingerprintTellsCopiesFromPicturesWithTheSameLayout() {
        val fine = ImageAnalysis::fineDistance
        // copies: resized, saved again
        for (seed in 0 until 6) {
            for (p in listOf(Scenes.page(seed), Scenes.screen(seed), Scenes.scene(seed, 800, 600))) {
                val fp = p.features()
                assertTrue(fine(fp.fine, Scenes.resize(p, p.w / 2, p.h / 2).features().fine) <= 16)
                assertTrue(fine(fp.fine, Scenes.noise(Scenes.resize(p, p.w / 3, p.h / 3), 3.0, 1).features().fine) <= 24)
            }
        }
        // different pages whose pHashes are close
        val pages = (0 until 60).map { Scenes.page(it).features() }
        var close = 0
        for (i in pages.indices) for (j in i + 1 until pages.size) {
            if (hamming(pages[i].phash, pages[j].phash) > 16) continue
            close++
            val d = fine(pages[i].fine, pages[j].fine)
            assertTrue("pages $i and $j only $d fine bits apart", d > 64)
        }
        assertTrue(close > 100)
    }

    @Test fun brightnessAndContrastAreMeasured() {
        val grey = Scenes.uniform(100).features()
        assertEquals(100f, grey.mean, 0.01f)
        assertEquals(0f, grey.contrast, 0.01f)
        val brighter = Scenes.scale(base, 1.15).features()
        assertTrue(brighter.mean > f.mean + 8)
        assertTrue(Scenes.contrast(base, 0.8).features().contrast < f.contrast * 0.87f)
        // a resize leaves both nearly alone
        val small = Scenes.resize(base, 400, 300).features()
        assertEquals(f.mean, small.mean, 1f)
        assertTrue(f.contrast / small.contrast < 1.1f)
    }

    @Test fun centreCropMatchesTheCropHash() {
        // a wide photo cropped to its centre square
        val wide = Scenes.scene(3, 900, 500)
        val square = Scenes.crop(wide, 200, 0, 500, 500)
        assertTrue(hamming(square.features().phash, wide.features().cropHash) <= 2)
        assertTrue(ImageAnalysis.fineDistance(square.features().fine, wide.features().cropFine) <= 8)
    }

    @Test fun flatPicturesAreFlagged() {
        assertTrue(Scenes.uniform(128).features().flat)
        assertTrue(Scenes.uniform(3).features().flat)
        assertFalse(f.flat)
    }

    @Test fun blurLowersSharpnessAndDetail() {
        val blurred = Scenes.blur(base, 4).features()
        assertTrue("sharp ${f.sharpness}", f.sharpness > 45)
        assertTrue("blurred ${blurred.sharpness}", blurred.sharpness < f.sharpness - 10)
        assertTrue(blurred.detail < f.detail / 4)
        // noise must not count as detail for the blur score
        val noisy = Scenes.noise(Scenes.blur(base, 4), 8.0, 3).features()
        assertTrue("noisy blurred ${noisy.sharpness}", noisy.sharpness < f.sharpness - 5)
    }

    @Test fun tinyPhotosHaveNoSharpnessScore() {
        assertTrue(Scenes.scene(4, 10, 10).features().sharpness.isNaN())
        assertTrue(Scenes.scene(4, 30, 30).features().sharpness.isNaN())
    }

    @Test fun exposureMeasures() {
        val dark = Scenes.scale(base, 0.15).features()
        assertTrue(dark.median < 40)
        val black = Scenes.uniform(2).features()
        assertEquals(1f, black.black, 0f)
        assertEquals(2f, black.median, 0f)
        assertEquals(1f, Scenes.uniform(252).features().white, 0f)
    }

    @Test fun greyCopyKeepsItsLooksButLosesColour() {
        val g = Scenes.grey(base).features()
        assertTrue(hamming(g.phash, f.phash) <= 4)
        assertTrue(g.saturation < 1f)
        assertTrue(f.saturation > 6f)
    }

    @Test fun transparentPixelsAreSeenOverWhite() {
        val px = IntArray(100 * 100) { 0x00000000 }
        val feats = ImageAnalysis.analyze(px, 100, 100)
        assertEquals(255f, feats.median, 0f)
    }

    @Test fun medianFilterMatchesTheNaiveOne() {
        val rnd = Random(5)
        val w = 37
        val h = 23
        val src = IntArray(w * h) { rnd.nextInt(256) }
        for (r in 1..2) {
            val fast = Sharpness.medianFilter(src, w, h, r)
            for (y in 0 until h) for (x in 0 until w) {
                val win = ArrayList<Int>()
                for (dy in -r..r) for (dx in -r..r) win.add(src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)])
                win.sort()
                assertEquals("r=$r at $x,$y", win[win.size / 2], fast[y * w + x])
            }
        }
    }

    @Test fun areaResizeKeepsTheMean() {
        val rnd = Random(9)
        val w = 101
        val h = 67
        val src = IntArray(w * h) { rnd.nextInt(256) }
        val out = ImageAnalysis.resizeArea(src, w, 0, 0, w, h, 32, 32)
        assertEquals(src.average(), out.average(), 0.5)
        // upsampling a tiny picture works too
        val up = ImageAnalysis.resizeArea(intArrayOf(10, 20, 30, 40), 2, 0, 0, 2, 2, 32, 32)
        assertEquals(10.0, up[0], 1e-9)
        assertEquals(40.0, up[32 * 32 - 1], 1e-9)
    }

    @Test fun noiseEstimateFollowsTheNoise() {
        val flat = Scenes.uniform(120, 300, 300)
        val noisy = Scenes.noise(flat, 10.0, 1)
        val gray = IntArray(noisy.px.size) { noisy.px[it] and 0xFF }
        val sigma = Sharpness.noiseSigma(gray, 300, 300)
        assertTrue("sigma $sigma", sigma in 8.0..12.0)
    }

    @Test fun reusedBuffersGiveTheSameAnswers() {
        val shared = Scratch()
        for ((seed, size) in listOf(21 to (900 to 700), 22 to (300 to 200), 23 to (1024 to 512), 24 to (64 to 900))) {
            val p = Scenes.noise(Scenes.scene(seed, size.first, size.second), 7.0, seed)
            val fresh = p.features()
            val reused = ImageAnalysis.analyze(p.px, p.w, p.h, shared)
            assertEquals(fresh.phash, reused.phash)
            assertEquals(fresh.cropHash, reused.cropHash)
            assertEquals(fresh.sharpness, reused.sharpness, 0f)
            assertEquals(fresh.detail, reused.detail, 0f)
            assertEquals(fresh.median, reused.median, 0f)
            assertTrue(fresh.variants.contentEquals(reused.variants))
        }
    }

    @Test fun bitsAreOrderedFromTheFirstValue() {
        val block = DoubleArray(64) { if (it == 0) 100.0 else it.toDouble() / 1000 }
        val h = ImageAnalysis.bits64(block)
        assertTrue(h < 0)   // the first value sets the highest bit
        assertEquals(32, java.lang.Long.bitCount(h))
    }
}

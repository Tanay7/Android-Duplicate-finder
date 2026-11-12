package io.github.tanay7.photocleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatcherTest {
    private val rnd = Random(42)
    private fun far() = rnd.nextLong()

    /** Features with a chosen hash; variants and crop hash are unrelated unless given. */
    private fun feat(
        phash: Long,
        sharp: Float = 80f,
        detail: Float = 100f,
        median: Float = 120f,
        cb: Float = 128f,
        cr: Float = 128f,
        sat: Float = 0f,
        variants: LongArray = longArrayOf(far(), far(), far(), far()),
        crop: Long = far(),
        flat: Boolean = false,
        fine: LongArray = LongArray(4),
        cropFine: LongArray = LongArray(4),
        mean: Float = 120f,
        contrast: Float = 50f,
        grid: ByteArray? = ByteArray(ImageAnalysis.GRID_BYTES),
    ) = ImageFeatures(phash, variants, crop, fine, cropFine, flat, cb, cr, sat, mean, contrast, sharp, detail, median, 0f, 0f, grid)

    /** Fine bits that differ from all-zero in [bits] places (never the first, the unused one). */
    private fun fineBits(bits: Int) = LongArray(4).also { for (b in 1..bits) it[b ushr 6] = it[b ushr 6] or (1L shl (b and 63)) }

    private fun photo(
        path: String,
        f: ImageFeatures?,
        size: Long = 3_000_000,
        sha: String? = null,
        w: Int = 4000,
        h: Int = 3000,
        taken: Double? = null,
        camera: String? = null,
        favorite: Boolean = false,
        added: Long = 0,
    ) = Item(MediaKind.PHOTO, path, size, added, favorite, CopyNames.isOriginalFolder(path), CopyNames.isRaw(path), sha, w, h, taken, camera, f)

    private fun video(path: String, sha: String, size: Long = 50_000_000, added: Long = 0) =
        Item(MediaKind.VIDEO, path, size, added, false, CopyNames.isOriginalFolder(path), false, sha, 1920, 1080, null, null, null)

    private fun flip(h: Long, bits: Int) = h xor ((1L shl bits) - 1)

    private fun match(items: List<Item>, o: MatchOptions = MatchOptions(poor = false)) = Matcher(items, o).run()

    @Test fun identicalCopiesKeepTheCameraOriginal() {
        val h = far()
        val items = listOf(
            photo("Download/IMG_1.jpg", feat(h), sha = "aa"),
            photo("DCIM/Camera/IMG_1.jpg", feat(h), sha = "aa"),
            photo("Pictures/WhatsApp/IMG_1 (1).jpg", feat(h), sha = "aa"),
        )
        val g = match(items).single()
        assertEquals(GroupType.IDENTICAL, g.type)
        assertEquals(1, g.keeper)
        assertEquals(1, g.members[0].item)   // the kept copy comes first
        assertTrue(g.members.filter { !it.keeper }.all { it.suggestTrash && it.relation == Relation.IDENTICAL })
    }

    @Test fun copyMarkerLoses() {
        val items = listOf(
            photo("Pictures/a (1).jpg", null, sha = "x"),
            photo("Pictures/a.jpg", null, sha = "x"),
        )
        assertEquals(1, match(items).single().keeper)
    }

    @Test fun favouritesAreKept() {
        val h = far()
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), sha = "x"),
            photo("Download/a.jpg", feat(h), sha = "x", favorite = true),
        )
        val g = match(items).single()
        assertEquals(1, g.keeper)
        assertTrue(g.members.first { !it.keeper }.suggestTrash)
        // a second favourite is never pre-selected
        val both = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), sha = "x", favorite = true),
            photo("Download/a.jpg", feat(h), sha = "x", favorite = true),
        )
        val m = match(both).single().members.first { !it.keeper }
        assertFalse(m.suggestTrash)
        assertTrue(Flag.FAVORITE in m.flags)
    }

    @Test fun smallerLookAlikeGoesTheBiggerStays() {
        val h = far()
        val items = listOf(
            photo("Pictures/WhatsApp/small.jpg", feat(flip(h, 3)), size = 200_000, w = 1600, h = 1200),
            photo("DCIM/Camera/big.jpg", feat(h), size = 4_000_000),
        )
        val g = match(items).single()
        assertEquals(GroupType.SIMILAR, g.type)
        assertEquals(1, g.keeper)
        val m = g.members.first { !it.keeper }
        assertEquals(Relation.LOOKALIKE, m.relation)
        assertEquals(100f, m.score!!, 0.001f)
        assertTrue(m.suggestTrash)
    }

    @Test fun tooDifferentOrOffSwitchedMeansNoGroup() {
        val h = far()
        val items = listOf(photo("a.jpg", feat(h)), photo("b.jpg", feat(flip(h, 9))))
        assertTrue(match(items).isEmpty())
        val close = listOf(photo("a.jpg", feat(h)), photo("b.jpg", feat(flip(h, 2))))
        assertTrue(match(close, MatchOptions(similar = false, poor = false)).isEmpty())
        assertEquals(1, match(close, MatchOptions(maxBits = 4, poor = false)).size)
        assertTrue(match(listOf(photo("a.jpg", feat(h)), photo("b.jpg", feat(flip(h, 5)))), MatchOptions(maxBits = 4, poor = false)).isEmpty())
    }

    @Test fun differentColoursDoNotMatch() {
        val h = far()
        val items = listOf(
            photo("a.jpg", feat(h, cb = 100f, cr = 140f, sat = 30f)),
            photo("b.jpg", feat(h, cb = 140f, cr = 110f, sat = 30f)),
        )
        assertTrue(match(items).isEmpty())
        // but a black-and-white copy matches its colour original: shown as another version, and kept as well
        val bw = listOf(photo("a.jpg", feat(h, cb = 100f, cr = 140f, sat = 30f)), photo("b.jpg", feat(h, sat = 1f)))
        val g = match(bw).single()
        val m = g.members.single { it.version == 1 }
        assertTrue(m.keeper)
        assertTrue(m.edited)
        assertTrue(g.members.none { it.suggestTrash })
    }

    @Test fun anEditedCopyIsAVersionOfItsOwnAndKept() {
        val h = far()
        fun pair(f: ImageFeatures) = match(listOf(photo("DCIM/Camera/a.jpg", feat(h), size = 4_000_000), photo("Pictures/b.jpg", f, size = 3_000_000)))
            .single().members.first { it.item == 1 }
        assertTrue(pair(feat(h)).suggestTrash)                                   // a plain re-save
        assertTrue(pair(feat(h, mean = 123f, contrast = 52f)).suggestTrash)      // as much as resizing changes
        assertEquals(0, pair(feat(h)).version)
        for (edit in listOf(feat(h, mean = 135f), feat(h, contrast = 62f), feat(h, cb = 132f, cr = 125f, sat = 9f), feat(h, sat = 5f))) {
            val m = pair(edit)
            assertTrue(m.keeper)
            assertEquals(1, m.version)
            assertEquals(Relation.KEEP, m.relation)
            assertTrue(m.edited)
            assertFalse(m.suggestTrash)
            assertTrue(m.note!!.contains("edit"))
        }
    }

    @Test fun everyVersionKeepsItsBestCopyAndLosesItsDuplicates() {
        val h = far()
        val edit = feat(h, mean = 140f)
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), size = 4_000_000),
            photo("Pictures/WhatsApp/a.jpg", feat(flip(h, 2)), size = 400_000, w = 1600, h = 1200),
            // the edit is the larger file: the best copy overall, but no reason to leave the original's copy unticked
            photo("Pictures/Edited/a_bright.jpg", edit, size = 5_000_000, sha = "e"),
            photo("Download/a_bright.jpg", edit, size = 500_000, w = 1600, h = 1200),
            photo("Download/a_bright (1).jpg", edit, size = 5_000_000, sha = "e"),
            photo("Download/a_bright (2).jpg", edit, size = 5_000_000, sha = "e"),
        )
        val g = match(items).single()
        assertEquals(GroupType.SIMILAR, g.type)
        assertEquals(2, g.keeper)
        // the best copy and its duplicates first, then the other version the same way
        assertEquals(listOf(2, 4, 5, 3, 0, 1), g.members.map { it.item })
        assertEquals(listOf(0, 0, 0, 0, 1, 1), g.members.map { it.version })
        assertEquals(listOf(true, false, false, false, true, false), g.members.map { it.keeper })
        assertEquals(listOf(false, true, true, true, false, true), g.members.map { it.suggestTrash })
        val original = g.members[4]
        assertTrue(original.edited)
        assertTrue(original.note!!.contains("kept as well"))
        assertEquals(Relation.LOOKALIKE, g.members[5].relation)
        assertEquals(listOf(Relation.KEEP, Relation.IDENTICAL, Relation.IDENTICAL, Relation.LOOKALIKE), g.members.take(4).map { it.relation })
    }

    @Test fun aCopyThatDriftedFromItsVersionsBestCopyIsNotPreselected() {
        val h = far()
        // each a little brighter than the one before: one version, but the last is an edit of the first
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h, mean = 120f), size = 4_000_000),
            photo("Pictures/b.jpg", feat(h, mean = 126f), size = 3_000_000),
            photo("Pictures/c.jpg", feat(h, mean = 132f), size = 2_000_000),
        )
        val g = match(items).single()
        assertEquals(listOf(0, 0, 0), g.members.map { it.version })
        assertTrue(g.members.first { it.item == 1 }.suggestTrash)
        val c = g.members.first { it.item == 2 }
        assertFalse(c.keeper)
        assertTrue(c.edited)
        assertFalse(c.suggestTrash)
    }

    @Test fun theDetailsMustMatchToo() {
        val h = far()
        fun groups(fineApart: Int, bitsApart: Int = 2, o: MatchOptions = MatchOptions(poor = false)) =
            match(listOf(photo("DCIM/Camera/a.jpg", feat(h), size = 4_000_000), photo("Pictures/b.jpg", feat(flip(h, bitsApart), fine = fineBits(fineApart)), size = 3_000_000)), o)
        // the same layout, other details (another page of text): not a look-alike at all
        assertTrue(groups(80).isEmpty())
        assertTrue(groups(49).isEmpty())
        // alike, but not surely the same picture: shown, and kept as well
        val maybe = groups(40).single().members.first { it.item == 1 }
        assertTrue(maybe.keeper)
        assertEquals(1, maybe.version)
        assertFalse(maybe.suggestTrash)
        assertTrue(maybe.note!!.contains("details"))
        assertEquals(100f * (255 - 40) / 255, maybe.score!!, 0.01f)
        // surely the same
        assertTrue(groups(32).single().members.first { it.item == 1 }.suggestTrash)
        // strict wants nearly every detail to match
        assertTrue(groups(20, o = MatchOptions(maxBits = 4, poor = false)).isEmpty())
        assertTrue(groups(16, o = MatchOptions(maxBits = 4, poor = false)).single().members.first { it.item == 1 }.suggestTrash)
    }

    @Test fun looseFindsMoreButTicksNoMore() {
        val h = far()
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), size = 4_000_000),
            photo("Pictures/b.jpg", feat(flip(h, 10)), size = 3_000_000),
            photo("Pictures/b_small.jpg", feat(flip(h, 10)), size = 300_000, w = 1600, h = 1200),
        )
        // normally b isn't a look-alike of a at all: only b and its small copy are
        assertEquals(listOf(1, 2), match(items).single().members.map { it.item })
        val g = match(items, MatchOptions(maxBits = 12, poor = false)).single()
        val b = g.members.first { it.item == 1 }
        assertTrue(b.keeper)                          // shown with a, and kept as well
        assertEquals(1, b.version)
        assertTrue(b.note!!.contains("details"))
        // still only what is surely a copy of something kept is ticked
        assertEquals(listOf(2), g.members.filter { it.suggestTrash }.map { it.item })
    }

    @Test fun aRawFileAndItsJpegAreBothKept() {
        val h = far()
        val items = listOf(
            photo("DCIM/Camera/IMG_7.dng", feat(h), size = 25_000_000),
            photo("DCIM/Camera/IMG_7.jpg", feat(h), size = 4_000_000),
            photo("Download/IMG_7.jpg", feat(h), size = 4_000_000, w = 2000, h = 1500),
        )
        val g = match(items).single()
        assertEquals(0, g.keeper)
        val jpeg = g.members.first { it.item == 1 }
        assertTrue(jpeg.keeper)
        assertTrue(jpeg.note!!.contains("developed"))
        // the smaller copy of the JPEG is a duplicate of the JPEG, whatever the RAW file is
        val small = g.members.first { it.item == 2 }
        assertEquals(1, small.version)
        assertEquals(Relation.LOOKALIKE, small.relation)
        assertTrue(small.suggestTrash)
        // a JPEG larger than the RAW file is the best copy; the RAW file is kept as well
        val bigger = match(listOf(items[0], photo("DCIM/Camera/IMG_7.jpg", feat(h), size = 4_000_000, w = 8000, h = 6000))).single()
        assertEquals(1, bigger.keeper)
        assertTrue(bigger.members.all { it.keeper })
        assertTrue(bigger.members.first { it.item == 0 }.note!!.contains("RAW"))
        // without the RAW file, the smaller copy goes as usual
        assertTrue(match(items.drop(1)).single().members.first { !it.keeper }.suggestTrash)
    }

    @Test fun aBurstSavedAsRawAndJpegKeepsTheSharpestOfEach() {
        val h = far()
        val detail = listOf(60f, 120f, 90f)
        val items = (0 until 3).flatMap {
            listOf(
                photo("DCIM/Camera/B_$it.dng", feat(h, detail = detail[it]), size = 25_000_000, taken = 100.0 + it, camera = "Pixel 9"),
                photo("DCIM/Camera/B_$it.jpg", feat(h, detail = detail[it] + 5), size = 4_000_000, taken = 100.0 + it, camera = "Pixel 9"),
            )
        }
        val g = match(items).single()
        assertEquals(GroupType.BURST, g.type)
        assertEquals(6, g.burstShots)
        assertEquals(setOf(2, 3), g.members.filter { it.keeper }.map { it.item }.toSet())   // B_1.dng and B_1.jpg
        assertEquals(4, g.members.count { it.suggestTrash })
        assertTrue(g.members.all { it.frame })
        assertTrue(g.members.filter { !it.keeper }.all { it.relation == Relation.BURST })
    }

    @Test fun aBurstFrameFarFromTheKeptShotIsNotPreselected() {
        val h = far()
        // a slow pan: each frame like its neighbour, the last one unlike the first
        val fine = listOf(fineBits(0), fineBits(40), fineBits(80))
        val detail = listOf(120f, 100f, 90f)
        val items = fine.indices.map {
            photo("DCIM/Camera/P_$it.jpg", feat(h, detail = detail[it], fine = fine[it]), taken = 500.0 + it, camera = "Pixel 9")
        }
        val g = match(items).single()
        assertEquals(GroupType.BURST, g.type)
        assertEquals(0, g.keeper)
        assertTrue(g.members[1].suggestTrash)
        assertFalse(g.members[2].suggestTrash)
        assertEquals(Relation.BURST, g.members[2].relation)
        assertTrue(g.members[2].note!!.contains("moment"))
        assertTrue(g.members.all { it.frame })
        // frames whose details differ too much aren't one burst at all
        val apart = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), taken = 1.0, camera = "X"),
            photo("DCIM/Camera/b.jpg", feat(h xor 0x3FFL, fine = fineBits(70)), taken = 2.0, camera = "X"),
        )
        assertTrue(match(apart).isEmpty())
    }

    @Test fun differentPagesAndScreensAreNeverGrouped() {
        // What a 64-bit pHash alone gets wrong: it only sees a picture's layout.
        val pics = (0 until 60).map { Scenes.page(it) } + (0 until 40).map { Scenes.screen(it) }
        val f = pics.map { it.features() }
        var confusable = 0
        for (i in f.indices) for (j in i + 1 until f.size) if (ImageAnalysis.hamming(f[i].phash, f[j].phash) <= 8) confusable++
        assertTrue("the test needs pairs a pHash confuses, found $confusable", confusable >= 5)
        val items = f.mapIndexed { i, fe -> photo("Pictures/Screenshots/s$i.png", fe, size = 300_000L + i, w = pics[i].w, h = pics[i].h) }
        assertTrue(match(items).isEmpty())
        assertTrue(match(items, MatchOptions(maxBits = 12, poor = false)).isEmpty())
    }

    @Test fun pagesPhotographedOneAfterTheOtherAreNotABurst() {
        val f = (0 until 40).map { Scenes.page(100 + it).features() }
        var close = 0
        for (i in 0 until f.size - 1) if (ImageAnalysis.hamming(f[i].phash, f[i + 1].phash) <= MatchOptions.BURST_BITS) close++
        assertTrue("the test needs neighbours a pHash takes for burst frames, found $close", close >= 5)
        val items = f.mapIndexed { i, fe -> photo("DCIM/Camera/IMG_$i.jpg", fe, taken = 1000.0 + 2 * i, camera = "Pixel 9", w = 3000, h = 4000) }
        assertTrue(match(items).isEmpty())
    }

    @Test fun aHandHeldBurstOfAPageIsFound() {
        val base = Scenes.page(7)
        val frames = (0 until 5).map { Scenes.noise(Scenes.shift(base, it * 3), 2.0, it) }      // 0.4 % of the width a frame
        val items = frames.mapIndexed { i, p -> photo("DCIM/Camera/B_$i.jpg", p.features(), taken = 50.0 + 0.3 * i, camera = "Pixel 9", w = 3000, h = 4000) }
        val g = match(items).single()
        assertEquals(GroupType.BURST, g.type)
        assertEquals(5, g.burstShots)
        assertEquals(4, g.members.count { it.suggestTrash })
    }

    @Test fun editsOfRealPicturesAreToldFromResaves() {
        val base = Scenes.scene(31, 800, 600)
        fun other(p: Pic) = match(
            listOf(photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600), photo("Pictures/b.jpg", p.features(), size = 1_000_000, w = p.w, h = p.h)),
        ).single().members.first { it.item == 1 }
        assertTrue(other(Scenes.resize(base, 400, 300)).suggestTrash)
        assertTrue(other(Scenes.noise(Scenes.resize(base, 400, 300), 4.0, 1)).suggestTrash)
        val edits = listOf(Scenes.scale(base, 1.15), Scenes.contrast(base, 0.8), Scenes.grey(base), Scenes.tint(base, 12, 0, -12))
        for (edit in edits) {
            val m = other(edit)
            assertTrue(m.keeper)
            assertTrue(m.edited)
            assertFalse(m.suggestTrash)
        }
        // every edit with a small copy of its own: all the versions are kept, all their copies can go
        val items = listOf(photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600)) + edits.flatMapIndexed { i, e ->
            val small = Scenes.resize(e, 400, 300)
            listOf(
                photo("Pictures/Edited/e$i.jpg", e.features(), size = 3_000_000, w = 800, h = 600),
                photo("Pictures/WhatsApp/e$i.jpg", small.features(), size = 300_000, w = 400, h = 300),
            )
        }
        val g = match(items).single()
        assertEquals(setOf(0, 1, 3, 5, 7), g.members.filter { it.keeper }.map { it.item }.toSet())
        assertEquals(setOf(2, 4, 6, 8), g.members.filter { it.suggestTrash }.map { it.item }.toSet())
        assertEquals(5, g.members.map { it.version }.toSet().size)
    }

    @Test fun anEditOfPartOfThePictureIsAVersionOfItsOwn() {
        val base = Scenes.scene(21, 800, 600)
        val yellow = Scenes.argb(255, 220, 40)
        // a sticker 5 % of the width, a caption bar, and the same scene shot again a little to the side
        val edits = listOf(
            Scenes.sticker(base, 500, 200, 40, 40, yellow),
            Scenes.sticker(base, 0, 560, 800, 40, Scenes.argb(20, 20, 20)),
            Scenes.shift(base, 12),
        )
        for (edit in edits) {
            val small = Scenes.resize(edit, 400, 300)
            val items = listOf(
                photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600),
                photo("Pictures/WhatsApp/a.jpg", Scenes.resize(base, 400, 300).features(), size = 300_000, w = 400, h = 300),
                photo("Pictures/Edited/a_edit.jpg", edit.features(), size = 3_000_000, w = 800, h = 600),
                photo("Pictures/WhatsApp/a_edit.jpg", small.features(), size = 250_000, w = 400, h = 300),
            )
            val groups = match(items)
            if (groups.size == 2) {
                // too different to be shown together at all: each with its own copy
                assertEquals(listOf(setOf(0, 1), setOf(2, 3)), groups.map { g -> g.members.map { it.item }.toSet() }.sortedBy { it.min() })
                assertTrue(groups.all { g -> g.members.count { it.suggestTrash } == 1 })
                continue
            }
            val g = groups.single()
            assertEquals(setOf(0, 2), g.members.filter { it.keeper }.map { it.item }.toSet())
            assertEquals(setOf(1, 3), g.members.filter { it.suggestTrash }.map { it.item }.toSet())
            val kept = g.members.first { it.item == 2 }
            assertEquals(1, kept.version)
            assertTrue(kept.note, kept.note!!.contains("Part of the picture") || kept.note!!.contains("brightness") || kept.note!!.contains("details"))
        }
        // the sticker is what tells them apart: without the grids, the copy with the sticker was a plain duplicate
        val sticker = Scenes.sticker(base, 500, 200, 40, 40, yellow)
        val pair = listOf(
            photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600),
            photo("Pictures/a_sticker.jpg", sticker.features(), size = 3_000_000, w = 800, h = 600),
        )
        val m = match(pair).single().members.first { it.item == 1 }
        assertTrue(m.keeper)
        assertFalse(m.edited)
        assertTrue(m.note!!.contains("Part of the picture"))
    }

    @Test fun withoutItsGridAPhotoIsNeverTakenForAPlainCopy() {
        val h = far()
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h, grid = null), size = 4_000_000),
            photo("Pictures/b.jpg", feat(h, grid = null), size = 3_000_000),
        )
        // the grids can't be had (the cache lost them): shown, not ticked
        assertTrue(match(items).single().members.none { it.suggestTrash })
        assertTrue(match(items, MatchOptions(poor = false, grid = { null })).single().members.none { it.suggestTrash })
        // read back from the cache when they are no longer in memory
        var asked = 0
        val g = match(items, MatchOptions(poor = false, grid = { asked++; ByteArray(ImageAnalysis.GRID_BYTES) })).single()
        assertTrue(g.members.first { it.item == 1 }.suggestTrash)
        assertTrue(asked > 0)
        // identical files need no grid
        val same = items.map { photo(it.path, it.features, sha = "s") }
        assertTrue(match(same).single().members.first { !it.keeper }.suggestTrash)
    }

    @Test fun aTurnedCopyIsComparedTurned() {
        val base = Scenes.scene(33, 800, 600)
        val small = Scenes.resize(base, 400, 300)
        for (turned in listOf(Scenes.rotate90(small), Scenes.rotate180(small), Scenes.rotate90(Scenes.rotate180(small)))) {
            val items = listOf(
                photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600),
                photo("Pictures/turned.jpg", turned.features(), size = 300_000, w = turned.w, h = turned.h),
            )
            val m = match(items).single().members.first { it.item == 1 }
            assertEquals("rotated", m.how)
            assertTrue(m.suggestTrash)
            // and the other way round: the turned one is the larger
            val back = listOf(
                photo("DCIM/Camera/a.jpg", Scenes.rotate90(base).features(), size = 4_000_000, w = 600, h = 800),
                photo("Pictures/b.jpg", turned.features(), size = 300_000, w = turned.w, h = turned.h),
            )
            assertTrue(match(back).single().members.first { it.item == 1 }.suggestTrash)
        }
        // a turned copy with a small sticker on it (2.5 % of the width: the fingerprints of the whole
        // picture hardly notice it) is no plain copy
        val sticker = Scenes.rotate90(Scenes.sticker(small, 250, 100, 10, 10, Scenes.argb(255, 220, 40)))
        val items = listOf(
            photo("DCIM/Camera/a.jpg", base.features(), size = 4_000_000, w = 800, h = 600),
            photo("Pictures/turned.jpg", sticker.features(), size = 300_000, w = sticker.w, h = sticker.h),
        )
        val m = match(items).single().members.first { it.item == 1 }
        assertTrue(m.keeper)
        assertEquals("rotated", m.how)
        assertTrue(m.note!!.contains("Part of the picture"))
    }

    @Test fun comparingCanBeStoppedAndTellsHowFarItIs() {
        val r = Random(3)
        val items = (0 until 600).map { photo("Pictures/p$it.jpg", feat(r.nextLong())) }
        for (threads in listOf(1, 4)) {
            var last = 0 to 0
            match(items, MatchOptions(poor = false, threads = threads, progress = { d, t -> synchronized(this) { if (d > last.first) last = d to t } }))
            assertTrue(last.first > 1)
            assertEquals(last.second, last.first)
            try {
                match(items, MatchOptions(poor = false, threads = threads, cancelled = { true }))
                org.junit.Assert.fail("not stopped")
            } catch (_: java.util.concurrent.CancellationException) {
            }
        }
    }

    @Test fun turnedCopiesArePreselectedMirroredAndCroppedAreNot() {
        val h = far()
        val rot = far()
        val mir = far()
        val crop = far()
        val fine = longArrayOf(far() and 1L.inv(), far(), far(), far())
        val cropFine = longArrayOf(far(), far(), far(), far())
        val orig = feat(h, variants = longArrayOf(rot, far(), far(), mir), crop = crop, fine = fine, cropFine = cropFine)
        val items = listOf(
            photo("DCIM/Camera/orig.jpg", orig, size = 5_000_000),
            photo("Download/turned.jpg", feat(rot, fine = ImageAnalysis.fineVariant(fine, 0)), size = 1_000_000, w = 3000, h = 4000),
            photo("Download/mirrored.jpg", feat(mir, fine = ImageAnalysis.fineVariant(fine, 3)), size = 1_000_000, w = 2000),
            photo("Download/cropped.jpg", feat(crop, fine = cropFine), size = 1_000_000, w = 3000),
        )
        val g = match(items).single()
        assertEquals(0, g.keeper)
        val byName = g.members.associateBy { items[it.item].path.substringAfterLast('/') }
        assertEquals("rotated", byName["turned.jpg"]!!.how)
        assertTrue(byName["turned.jpg"]!!.suggestTrash)
        assertEquals(0, byName["turned.jpg"]!!.version)
        // mirrored and cropped copies may be deliberate edits: versions of their own, kept as well
        assertEquals("mirrored", byName["mirrored.jpg"]!!.how)
        assertTrue(byName["mirrored.jpg"]!!.keeper)
        assertFalse(byName["mirrored.jpg"]!!.suggestTrash)
        assertEquals("cropped", byName["cropped.jpg"]!!.how)
        assertTrue(byName["cropped.jpg"]!!.keeper)
        assertFalse(byName["cropped.jpg"]!!.suggestTrash)
        assertNotNull(byName["cropped.jpg"]!!.note)
        assertEquals(3, g.members.map { it.version }.toSet().size)
        // a second copy of the cropped version is a duplicate of the cropped version
        val more = items + photo("Pictures/WhatsApp/cropped.jpg", feat(crop, fine = cropFine), size = 200_000, w = 1500, h = 1500)
        val g2 = match(more).single()
        val copy = g2.members.first { it.item == 4 }
        assertTrue(copy.suggestTrash)
        assertEquals(g2.members.first { it.item == 3 }.version, copy.version)
        assertTrue(g2.members.first { it.item == 3 }.keeper)
    }

    @Test fun aFileLinkedOnlyThroughAnotherIsNotPreselected() {
        val h = far()
        val b = flip(h, 6)
        val c = b xor (0x3FL shl 20)   // 6 more bits: 12 from the original
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h), size = 9_000_000, w = 6000),
            photo("Pictures/b.jpg", feat(b), size = 2_000_000),
            photo("Pictures/c.jpg", feat(c), size = 1_000_000, w = 2000),
        )
        val g = match(items).single()
        assertEquals(0, g.keeper)
        val mc = g.members.first { it.item == 2 }
        assertEquals(Relation.VIA, mc.relation)
        assertEquals(1, mc.via)
        assertFalse(mc.suggestTrash)
        assertTrue(g.members.first { it.item == 1 }.suggestTrash)
    }

    @Test fun burstKeepsTheSharpestFrame() {
        val h = far()
        // neighbouring frames 6 to 10 bits apart: too far for a look-alike, close enough for a burst,
        // and none more than 16 bits from the frame that is kept
        val hashes = listOf(h, h xor 0x3FFL, h xor 0x3FFL xor (0x3FL shl 12), h xor (0x3FL shl 12))
        val detail = listOf(50f, 120f, 80f, 60f)
        val items = hashes.indices.map {
            photo("DCIM/Camera/B_$it.jpg", feat(hashes[it], detail = detail[it]), taken = 1000.0 + it, camera = "Pixel 9")
        }
        val g = match(items).single()
        assertEquals(GroupType.BURST, g.type)
        assertEquals(4, g.burstShots)
        assertEquals(1, g.keeper)
        assertEquals(listOf(0, 1, 2, 3), g.members.map { it.item })   // in time order
        assertTrue(g.members.filter { !it.keeper }.all { it.suggestTrash && it.relation == Relation.BURST })
        assertEquals(100f, g.members[1].relSharpness!!, 0.01f)
        assertEquals(41.7f, g.members[0].relSharpness!!, 0.01f)
    }

    @Test fun burstNeedsTheSameCameraAndShortGaps() {
        val h = far()
        val apart = listOf(
            photo("DCIM/a.jpg", feat(h), taken = 1000.0, camera = "X"),
            photo("DCIM/b.jpg", feat(h xor 0x3FFL), taken = 1005.0, camera = "X"),
        )
        assertTrue(match(apart).isEmpty())
        val cams = listOf(
            photo("DCIM/a.jpg", feat(h), taken = 1000.0, camera = "X"),
            photo("DCIM/b.jpg", feat(h xor 0x3FFL), taken = 1001.0, camera = "Y"),
        )
        assertTrue(match(cams).isEmpty())
        assertTrue(match(apart.map { it }, MatchOptions(bursts = false, poor = false)).isEmpty())
    }

    @Test fun anExportOfABurstFrameIsNotAFrame() {
        val h = far()
        val items = listOf(
            photo("DCIM/Camera/a.jpg", feat(h, detail = 100f), taken = 1000.0, camera = "X"),
            photo("DCIM/Camera/b.jpg", feat(h xor 0x3FFL, detail = 90f), taken = 1001.0, camera = "X"),
            // a small export of frame b: same capture time, sharper-looking because it's smaller
            photo("Pictures/b_small.jpg", feat(h xor 0x3FFL, detail = 500f), taken = 1001.0, camera = "X", w = 800, h = 600, size = 100_000),
        )
        val g = match(items).single()
        assertEquals(0, g.keeper)              // the export never outranks the full-size frames
        assertEquals(2, g.burstShots)
    }

    @Test fun poorShotsAreListedButNeverPreselected() {
        val items = listOf(
            photo("DCIM/Camera/blurry.jpg", feat(far(), sharp = 20f), camera = "Pixel 9"),
            photo("DCIM/Camera/dark.jpg", feat(far(), median = 15f), taken = 77.0),
            photo("DCIM/Camera/fine.jpg", feat(far()), camera = "Pixel 9"),
            photo("DCIM/Camera/tiny.png", feat(far(), sharp = Float.NaN), camera = "Pixel 9"),
            // not from a camera: a dark-theme screenshot and a soft drawing are not poor shots
            photo("Pictures/Screenshots/dark-mode.png", feat(far(), median = 15f)),
            photo("Download/drawing.png", feat(far(), sharp = 20f)),
        )
        val g = match(items, MatchOptions()).single()
        assertEquals(GroupType.POOR, g.type)
        assertEquals(-1, g.keeper)
        assertEquals(listOf(0, 1), g.members.map { it.item })   // worst first
        assertTrue(Flag.BLURRY in g.members[0].flags)
        assertTrue(Flag.DARK in g.members[1].flags)
        assertTrue(g.members.none { it.suggestTrash })
        assertTrue(match(items, MatchOptions(poor = false)).isEmpty())
    }

    @Test fun flatPicturesOnlyMatchWhenIdentical() {
        val h = far()
        val items = listOf(photo("a.png", feat(h, flat = true)), photo("b.png", feat(h, flat = true)))
        assertTrue(match(items).isEmpty())
        val same = listOf(photo("a.png", feat(h, flat = true), sha = "s"), photo("b.png", feat(h, flat = true), sha = "s"))
        assertEquals(GroupType.IDENTICAL, match(same).single().type)
    }

    @Test fun identicalVideos() {
        val items = listOf(
            video("Download/VID_1.mp4", "v", added = 50),
            video("DCIM/Camera/VID_1.mp4", "v", added = 100),
            video("Movies/other.mp4", "w"),
        )
        val g = match(items).single()
        assertEquals(MediaKind.VIDEO, g.kind)
        assertEquals(1, g.keeper)
    }

    @Test fun groupsWithMoreSpaceComeFirst() {
        val items = listOf(
            photo("a.jpg", null, sha = "1", size = 100),
            photo("b.jpg", null, sha = "1", size = 100),
            photo("c.jpg", null, sha = "2", size = 5000),
            photo("d.jpg", null, sha = "2", size = 5000),
        )
        val groups = match(items)
        assertEquals(listOf(2, 0), groups.map { it.members.minOf { m -> m.item } })
    }

    @Test fun manyPhotosOnManyThreadsFindThePlantedPairs() {
        val items = ArrayList<Item>()
        val r = Random(7)
        repeat(1500) { items.add(photo("Pictures/p$it.jpg", feat(r.nextLong(), variants = longArrayOf(r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()), crop = r.nextLong()))) }
        repeat(10) {
            val src = items[it * 100].features!!
            items.add(photo("Download/copy$it.jpg", feat(flip(src.phash, 4)), w = 1000, h = 750))
        }
        val single = match(items)
        val multi = match(items, MatchOptions(poor = false, threads = 4))
        assertEquals(10, single.size)
        assertEquals(single.map { g -> g.members.map { it.item }.sorted() }.toSet(), multi.map { g -> g.members.map { it.item }.sorted() }.toSet())
    }

    @Test fun realPicturesEndToEnd() {
        val base = Scenes.scene(11, 800, 600)
        val frame2 = Scenes.shift(base, 6)
        val frame3 = Scenes.blur(Scenes.shift(base, 12), 3)
        val other = Scenes.scene(12, 800, 600)
        val items = listOf(
            photo("DCIM/Camera/IMG_1.jpg", base.features(), size = 3_000_000, w = 800, h = 600, taken = 10.0, camera = "C"),
            photo("DCIM/Camera/IMG_2.jpg", frame2.features(), size = 3_000_000, w = 800, h = 600, taken = 11.0, camera = "C"),
            photo("DCIM/Camera/IMG_3.jpg", frame3.features(), size = 3_000_000, w = 800, h = 600, taken = 12.0, camera = "C"),
            photo("Pictures/small.jpg", Scenes.resize(base, 400, 300).features(), size = 300_000, w = 400, h = 300),
            photo("Pictures/turned.jpg", Scenes.rotate90(Scenes.resize(base, 400, 300)).features(), size = 300_000, w = 300, h = 400),
            photo("Pictures/other.jpg", other.features(), size = 3_000_000, w = 800, h = 600),
        )
        val groups = match(items)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(GroupType.BURST, g.type)
        assertEquals(setOf(0, 1, 2, 3, 4), g.members.map { it.item }.toSet())
        assertTrue("the blurred frame must not be kept", g.keeper != 2)
        assertEquals("rotated", g.members.first { it.item == 4 }.how)
        assertNull(g.members.firstOrNull { it.item == 5 })
    }
}

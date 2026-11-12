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
    ) = ImageFeatures(phash, variants, crop, flat, cb, cr, sat, sharp, detail, median, 0f, 0f)

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
    ) = Item(MediaKind.PHOTO, path, size, added, favorite, CopyNames.isOriginalFolder(path), sha, w, h, taken, camera, f)

    private fun video(path: String, sha: String, size: Long = 50_000_000, added: Long = 0) =
        Item(MediaKind.VIDEO, path, size, added, false, CopyNames.isOriginalFolder(path), sha, 1920, 1080, null, null, null)

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
        assertEquals(95.3125f, m.score!!, 0.001f)
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
        // but a black-and-white copy matches its colour original
        val bw = listOf(photo("a.jpg", feat(h, cb = 100f, cr = 140f, sat = 30f)), photo("b.jpg", feat(h, sat = 1f)))
        assertEquals(1, match(bw).size)
    }

    @Test fun turnedCopiesArePreselectedMirroredAndCroppedAreNot() {
        val h = far()
        val rot = far()
        val mir = far()
        val crop = far()
        val orig = feat(h, variants = longArrayOf(rot, far(), far(), mir), crop = crop)
        val items = listOf(
            photo("DCIM/Camera/orig.jpg", orig, size = 5_000_000),
            photo("Download/turned.jpg", feat(rot), size = 1_000_000, w = 3000, h = 4000),
            photo("Download/mirrored.jpg", feat(mir), size = 1_000_000, w = 2000),
            photo("Download/cropped.jpg", feat(crop), size = 1_000_000, w = 3000),
        )
        val g = match(items).single()
        assertEquals(0, g.keeper)
        val byName = g.members.associateBy { items[it.item].path.substringAfterLast('/') }
        assertEquals("rotated", byName["turned.jpg"]!!.how)
        assertTrue(byName["turned.jpg"]!!.suggestTrash)
        assertEquals("mirrored", byName["mirrored.jpg"]!!.how)
        assertFalse(byName["mirrored.jpg"]!!.suggestTrash)
        assertEquals("cropped", byName["cropped.jpg"]!!.how)
        assertFalse(byName["cropped.jpg"]!!.suggestTrash)
        assertNotNull(byName["cropped.jpg"]!!.note)
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
        // neighbouring frames 10 bits apart: too far for a look-alike, close enough for a burst
        val hashes = listOf(h, h xor 0x3FFL, h xor 0x3FFL xor (0x3FFL shl 12), h xor (0x3FFL shl 12))
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
            photo("DCIM/Camera/blurry.jpg", feat(far(), sharp = 20f)),
            photo("DCIM/Camera/dark.jpg", feat(far(), median = 15f)),
            photo("DCIM/Camera/fine.jpg", feat(far())),
            photo("DCIM/Camera/tiny.png", feat(far(), sharp = Float.NaN)),
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

package io.github.tanay7.photocleaner.scan

import io.github.tanay7.photocleaner.core.Flag
import io.github.tanay7.photocleaner.core.GroupType
import io.github.tanay7.photocleaner.core.ImageAnalysis
import io.github.tanay7.photocleaner.core.MatchOptions
import io.github.tanay7.photocleaner.core.Matcher
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Pic
import io.github.tanay7.photocleaner.core.Relation
import io.github.tanay7.photocleaner.core.Scenes
import io.github.tanay7.photocleaner.media.FeatureRow
import io.github.tanay7.photocleaner.media.MediaItem
import io.github.tanay7.photocleaner.ui.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * From what the media index and the reading threads hand over to what the screens say and do: every
 * value that crosses a boundary (the matcher's items, the groups, the ticks, the words) is checked
 * against the one it came from.
 */
class DataflowTest {
    private var nextId = 1L

    private fun media(folder: String, name: String, size: Long, w: Int = 800, h: Int = 600, orientation: Int = 0,
                      mime: String = "image/jpeg", favorite: Boolean = false, volume: String = "external_primary") =
        MediaItem(MediaKind.PHOTO, nextId++, volume, name, folder, size, 1_700_000_000, 1_700_000_000 + nextId, 0, w, h, orientation, mime, 0, favorite)

    private fun row(m: MediaItem, p: Pic?, sha: String, w: Int = 0, h: Int = 0, taken: Double? = null, camera: String? = null) =
        FeatureRow(m.key, m.size, m.modified, sha, w, h, taken, camera, p?.let { ImageAnalysis.analyze(it.px, it.w, it.h) }, null)

    @Test fun theMatchersItemsSayWhatTheMediaIndexAndTheReadingFound() {
        val camera = media("DCIM/Camera", "IMG_1.jpg", 3_000_000, w = 4000, h = 3000, orientation = 90)
        // read: the decoded size wins over the media index's, and is the size the photo is shown at
        var item = matcherItem(camera, row(camera, null, "aa", w = 3000, h = 4000, taken = 12.5, camera = "Pixel 9"))
        assertEquals(3000, item.width)
        assertEquals(4000, item.height)
        assertEquals("aa", item.sha)
        assertEquals(12.5, item.takenAt!!, 0.0)
        assertEquals("Pixel 9", item.camera)
        assertTrue(item.original)
        assertFalse(item.raw)
        assertEquals("DCIM/Camera/IMG_1.jpg", item.path)
        assertEquals("jpeg", item.format)
        // not read (or no size known from reading): the media index's size, turned the way it is shown
        item = matcherItem(camera, null)
        assertEquals(3000, item.width)
        assertEquals(4000, item.height)
        assertNull(item.sha)
        assertNull(item.features)
        assertEquals(3000, matcherItem(camera, row(camera, null, "aa", w = 0, h = 0)).width)
        // RAW by name (any case) or by type
        assertTrue(matcherItem(media("DCIM/Camera", "IMG_2.ARW", 1, mime = "image/x-sony-arw"), null).raw)
        assertTrue(matcherItem(media("DCIM/Camera", "IMG_3", 1, mime = "image/x-adobe-dng"), null).raw)
        assertTrue(matcherItem(media("DCIM/Camera", "IMG_4", 1, mime = "image/x-panasonic-raw"), null).raw)
        assertFalse(matcherItem(media("DCIM/Camera", "IMG_5.heic", 1, mime = "image/heic"), null).raw)
        // where originals are made: by the folder, never by the file's name
        assertTrue(matcherItem(media("Pictures/Screenshots", "s.png", 1, mime = "image/png"), null).original)
        assertFalse(matcherItem(media("Pictures", "DCIM.jpg", 1), null).original)
        assertFalse(matcherItem(media("Download", "Screenshots.png", 1, mime = "image/png"), null).original)
        // a favourite stays one, and the time it joined the index is kept for breaking ties
        val fav = media("Pictures", "f.jpg", 1, favorite = true)
        assertTrue(matcherItem(fav, null).favorite)
        assertEquals(fav.added, matcherItem(fav, null).added)
    }

    @Test fun fromTheMediaIndexToWhatTheScreensSayAndDo() {
        val base = Scenes.scene(81, 800, 600)
        val small = Scenes.resize(base, 400, 300)
        val edit = Scenes.scale(base, 1.2)
        val other = Scenes.scene(82, 800, 600)
        val frames = (0 until 3).map { Scenes.noise(Scenes.shift(Scenes.scene(83, 800, 600), it * 4), 2.0, it) }
        val files = ArrayList<Pair<MediaItem, FeatureRow?>>()
        fun add(m: MediaItem, p: Pic?, sha: String, taken: Double? = null) =
            files.add(m to row(m, p, sha, w = m.width, h = m.height, taken = taken, camera = if (taken != null) "Pixel 9" else null))
        add(media("DCIM/Camera", "IMG_1.jpg", 4_000_000), base, "a")
        add(media("Download", "IMG_1 (1).jpg", 4_000_000), base, "a")                       // identical
        add(media("Pictures/WhatsApp", "IMG-1.jpg", 300_000, 400, 300), small, "b")          // smaller copy
        add(media("Pictures/Edited", "IMG_1_bright.jpg", 3_500_000), edit, "c")              // an edit: kept as well
        add(media("Pictures/WhatsApp", "IMG-1-bright.jpg", 250_000, 400, 300), Scenes.resize(edit, 400, 300), "d")
        add(media("Pictures/Other", "other.jpg", 2_000_000), other, "e")                     // alone: in no group
        frames.forEachIndexed { k, p -> add(media("DCIM/Camera", "B_$k.jpg", 3_000_000), p, "f$k", taken = 100.0 + k) }
        val items = files.map { it.first }
        val rows = files.map { it.second }

        val groups = Matcher(items.mapIndexed { i, m -> matcherItem(m, rows[i]) }, MatchOptions(poor = false)).run()
        val r = ScanResult(items, rows, groups, ScanOptions(), 4, 0, 1.0, 4)

        // every file is in one group at most
        val seen = HashSet<Int>()
        for (g in groups) for (m in g.members) assertTrue("item ${m.item} in two groups", seen.add(m.item))
        assertFalse(5 in seen)
        val look = groups.single { g -> g.members.any { it.item == 0 } }
        val burst = groups.single { it.type == GroupType.BURST }
        assertEquals(GroupType.SIMILAR, look.type)
        assertEquals(3, burst.members.count { it.frame })

        for (g in groups) {
            // one copy kept for every version, and nothing kept is ticked, by the matcher or in the results
            val versions = g.members.groupBy { it.version }
            for ((v, ms) in versions) assertEquals("version $v", 1, ms.count { it.keeper })
            assertTrue(g.members.single { it.item == g.keeper }.keeper)
            assertEquals(0, g.members.single { it.item == g.keeper }.version)
            for (m in g.members) {
                if (m.keeper) {
                    assertFalse(m.suggestTrash)
                    assertFalse(m.item in r.selected)
                }
                assertEquals(m.suggestTrash, m.item in r.selected)
                // the words for every member: never empty, and the kept copy's never speak of sharpness outside a burst
                assertTrue(Words.label(m, g).isNotBlank())
                val why = Words.reason(r, m, g)
                assertTrue(why.isNotBlank())
                if (m.keeper && !m.frame) assertFalse(why, why.contains("sharp"))
                if (m.suggestTrash && m.relation != Relation.BURST) assertTrue(why, why.contains(items[g.members.first { it.keeper && it.version == m.version }.item].name))
            }
        }
        assertEquals(setOf(1, 2, 4), look.members.filter { it.suggestTrash }.map { it.item }.toSet())
        assertTrue(look.members.single { it.item == 3 }.keeper)
        assertEquals("Edited · keep", Words.label(look.members.single { it.item == 3 }, look))
        assertTrue(Words.reason(r, look.members.single { it.item == 2 }, look).contains("IMG_1.jpg"))
        assertTrue(Words.reason(r, look.members.single { it.item == 4 }, look).contains("IMG_1_bright.jpg"))
        assertTrue(Words.reason(r, burst.members.single { it.keeper }, burst).contains("sharpest"))

        // the ticked files go to the trash: what is kept stays unticked, nothing is left to settle
        val ticked = r.selected.toSet()
        r.trashed.addAll(ticked)
        assertTrue(r.settle().isEmpty())
        assertTrue(groups.filter { r.shown(it) }.all { g -> r.live(g).all { it.keeper } })
        assertFalse(r.shown(burst))                        // one shot left: nothing to compare
        assertTrue(r.shown(look))                          // the original and the edit are left
        assertFalse(r.everyCopyTicked(look))

        // the same scan brought back after Android closed the app, with a choice made by hand
        val again = ScanResult(items, rows, groups, ScanOptions(), 4, 0, 1.0, 4,
            keeps = setOf(items[2].key), ticks = setOf(items[3].key))
        assertFalse(2 in again.selected)                   // kept by hand before: never ticked again
        assertTrue(3 in again.selected)                    // ticked by hand in these results
        // the edit (ticked by hand) and its copy (ticked as suggested): every copy of that version
        assertTrue(again.everyCopyTicked(look))

        // a favourite among the copies: shown with its star, never ticked
        val favItems = items.toMutableList().also { it[2] = media("Pictures/WhatsApp", "IMG-1.jpg", 300_000, 400, 300, favorite = true) }
        val favRows = rows.toMutableList().also { it[2] = row(favItems[2], small, "b", w = 400, h = 300) }
        val g2 = Matcher(favItems.mapIndexed { i, m -> matcherItem(m, favRows[i]) }, MatchOptions(poor = false)).run()
            .single { g -> g.members.any { it.item == 2 } }
        val fav = g2.members.single { it.item == 2 }
        assertFalse(fav.suggestTrash)
        assertTrue(Flag.FAVORITE in fav.flags)
        assertTrue(Words.reason(r, fav, g2).contains("favourite"))
    }
}

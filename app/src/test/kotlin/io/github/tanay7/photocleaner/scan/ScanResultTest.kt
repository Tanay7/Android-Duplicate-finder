package io.github.tanay7.photocleaner.scan

import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.GroupType
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Member
import io.github.tanay7.photocleaner.core.Relation
import io.github.tanay7.photocleaner.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is ticked in the results, and what must never stay ticked. */
class ScanResultTest {
    private fun item(id: Int) =
        MediaItem(MediaKind.PHOTO, id.toLong(), "external_primary", "p$id.jpg", "Pictures", 1000L + id, 0, 0, 0, 100, 100, 0, "image/jpeg", 0, false)

    private fun key(id: Int) = item(id).key

    private fun keep(i: Int, version: Int = 0) = Member(i, true, Relation.KEEP, null, null, null, false, emptySet(), null, null, false, false, version)
    private fun copy(i: Int, version: Int = 0, suggest: Boolean = true) =
        Member(i, false, Relation.LOOKALIKE, 100f, null, null, suggest, emptySet(), null, null, false, false, version)

    private fun group(vararg m: Member) = Group(GroupType.SIMILAR, MediaKind.PHOTO, m.toList(), m.first { it.keeper }.item)

    private fun result(groups: List<Group>, keeps: Set<String> = emptySet(), ticks: Set<String> = emptySet()): ScanResult {
        val n = groups.maxOf { g -> g.members.maxOf { it.item } } + 1
        return ScanResult((0 until n).map { item(it) }, List(n) { null }, groups, ScanOptions(), 1, 0, 0.0, 1, keeps, ticks)
    }

    @Test fun suggestionsAreTickedUnlessKeptByHandBefore() {
        val g = group(keep(0), copy(1), copy(2), copy(3, suggest = false))
        assertEquals(setOf(1, 2), result(listOf(g)).selected)
        assertEquals(setOf(1), result(listOf(g), keeps = setOf(key(2))).selected)
        // a tick made by hand in these results comes back; a keep still wins over it
        assertEquals(setOf(1, 2, 3), result(listOf(g), ticks = setOf(key(3))).selected)
        assertEquals(setOf(1, 2), result(listOf(g), keeps = setOf(key(3)), ticks = setOf(key(3))).selected)
    }

    @Test fun ticksByHandSayWhatToRemember() {
        val r = result(listOf(group(keep(0), copy(1), copy(2, suggest = false))))
        // unticking a suggestion is a keep; ticking what wasn't suggested is a tick
        var c = r.tick(mapOf(1 to false, 2 to true, 0 to true))
        assertEquals(setOf(0, 2), r.selected)
        assertEquals(mapOf(key(1) to true, key(2) to false, key(0) to false), c.keep)
        assertEquals(mapOf(key(1) to false, key(2) to true, key(0) to true), c.tick)
        // back to the suggestion: nothing is left to remember
        c = r.tick(mapOf(0 to null, 1 to null, 2 to null))
        assertEquals(setOf(1), r.selected)
        assertTrue(c.keep.values.none { it })
        assertTrue(c.tick.values.none { it })
    }

    @Test fun theLastFileOfAGroupNeverStaysTicked() {
        val r = result(listOf(group(keep(0), copy(1))))
        assertTrue(r.shown(r.groups[0]))
        r.trashed.add(0)                       // the kept copy was deleted in another app
        assertEquals(listOf(1), r.settle())
        assertTrue(r.selected.isEmpty())
        assertFalse(r.shown(r.groups[0]))
    }

    @Test fun theLastCopyOfAVersionNeverStaysTicked() {
        // an original with a copy, and an edit of it with a copy
        val r = result(listOf(group(keep(0), copy(1), keep(2, version = 1), copy(3, version = 1))))
        assertEquals(setOf(1, 3), r.selected)
        assertTrue(r.settle().isEmpty())
        // the kept edit is deleted in another app: its copy is now the only one of the edit
        r.trashed.add(2)
        assertEquals(listOf(3), r.settle())
        assertEquals(setOf(1), r.selected)
        // the usual way round changes nothing: the copies go, the kept ones stay unticked
        r.trashed.add(1)
        assertTrue(r.settle().isEmpty())
        assertTrue(r.selected.none { it !in r.trashed })
    }

    @Test fun aVersionOfOneFileTickedByHandStaysTicked() {
        val r = result(listOf(group(keep(0), copy(1), keep(2, version = 1))))
        r.tick(mapOf(2 to true))               // "I don't want the edit"
        r.trashed.add(1)
        assertTrue(r.settle().isEmpty())
        assertEquals(setOf(1, 2), r.selected)
    }

    @Test fun everyCopyTickedIsNoticedForEachVersion() {
        val g = group(keep(0), copy(1), keep(2, version = 1), copy(3, version = 1), keep(4, version = 2))
        val r = result(listOf(g))
        assertFalse(r.everyCopyTicked(g))
        r.tick(mapOf(4 to true))               // a version of one file, ticked by hand: on purpose
        assertFalse(r.everyCopyTicked(g))
        r.tick(mapOf(2 to true))               // the kept edit, with its copy still ticked: nothing of it would be left
        assertTrue(r.everyCopyTicked(g))
        r.tick(mapOf(3 to false))
        assertFalse(r.everyCopyTicked(g))
        r.tick(mapOf(0 to true, 3 to true))    // everything
        assertTrue(r.everyCopyTicked(g))
        // what has gone doesn't count
        val r2 = result(listOf(g))
        r2.trashed.addAll(listOf(1, 3))
        assertFalse(r2.everyCopyTicked(g))
        r2.tick(mapOf(0 to true))
        assertFalse(r2.everyCopyTicked(g))     // one file left of that version, ticked by hand
        r2.tick(mapOf(2 to true, 4 to true))
        assertTrue(r2.everyCopyTicked(g))
    }
}

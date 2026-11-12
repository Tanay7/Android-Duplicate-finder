package io.github.tanay7.photocleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FolderTreeTest {
    private val folders = listOf(
        FolderInfo("external_primary", "DCIM/Camera", 10, 2, 1000),
        FolderInfo("external_primary", "Pictures/Screenshots", 5, 0, 100),
        FolderInfo("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 7, 0, 70),
        FolderInfo("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", 0, 3, 300),
        FolderInfo("external_primary", "", 1, 0, 1),
        FolderInfo("1234-5678", "DCIM/Camera", 4, 0, 40),
    )
    private val labels = mapOf("external_primary" to "Phone", "1234-5678" to "SD card")
    private val roots = FolderTree.build(folders) { labels.getValue(it) }

    @Test fun buildsOneTreePerVolumeWithChainsMerged() {
        assertEquals(listOf("Phone", "SD card"), roots.map { it.label })
        val phone = roots[0]
        assertEquals(listOf("Android/media/com.whatsapp/WhatsApp/Media", "DCIM/Camera", "Pictures/Screenshots"), phone.children.map { it.label })
        assertEquals(23, phone.photos)
        assertEquals(5, phone.videos)
        assertEquals(1471L, phone.bytes)
        assertEquals("external_primary:", phone.key)
        assertEquals("external_primary:", phone.folder!!.key)   // photos at the top of the storage
        val wa = phone.children[0]
        assertEquals(listOf("WhatsApp Images", "WhatsApp Video"), wa.children.map { it.label })
        assertTrue(wa.children.all { it.parent === wa })
        assertEquals(2, wa.children[0].depth)
        assertEquals("external_primary:DCIM/Camera", phone.children[1].key)
        assertEquals(roots[1], roots[1].children[0].parent)
    }

    @Test fun visibleRowsFollowTheExpandedFolders() {
        val rows = FolderTree.visible(roots, setOf("external_primary:"))
        assertEquals(listOf("Phone", "Android/media/com.whatsapp/WhatsApp/Media", "DCIM/Camera", "Pictures/Screenshots", "SD card"), rows.map { it.label })
        val deeper = FolderTree.visible(roots, setOf("external_primary:", "external_primary:Android/media/com.whatsapp/WhatsApp/Media"))
        assertEquals(7, deeper.size)
    }

    @Test fun selectingAFolderSelectsEverythingBelowIt() {
        val sel = FolderSelection(HashSet())
        val phone = roots[0]
        assertEquals(Check.NONE, sel.state(phone))
        sel.toggle(phone.children[1])                     // DCIM/Camera
        assertEquals(setOf("external_primary:DCIM/Camera"), sel.selected)
        assertEquals(Check.SOME, sel.state(phone))
        sel.toggle(phone)
        assertEquals(Check.ALL, sel.state(phone))
        assertEquals(5, sel.selected.size)
        assertEquals(Check.NONE, sel.state(roots[1]))
        sel.toggle(phone)
        assertEquals(Check.NONE, sel.state(phone))
        sel.setAll(roots, true)
        assertEquals(6, sel.selected.size)
        sel.setAll(roots, false)
        assertTrue(sel.selected.isEmpty())
    }

    @Test fun newFoldersFollowTheSavedChoice() {
        val all = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), FolderTree.initialSelection(all, null, null, false))
        assertEquals(setOf("a"), FolderTree.initialSelection(all, setOf("a"), setOf("a", "b"), false))
        // everything was selected when the choice was saved: a new folder joins in
        assertEquals(setOf("a", "b", "c"), FolderTree.initialSelection(all, setOf("a", "b"), setOf("a", "b"), true))
        // a folder that has gone is simply dropped
        assertFalse("d" in FolderTree.initialSelection(all, setOf("a", "d"), setOf("a", "d"), false))
    }
}

class NamesTest {
    @Test fun copyMarkers() {
        for (n in listOf("IMG_1 (1).jpg", "photo - Copy.jpg", "Copy of photo.png", "photo_copy2.jpg", "a(copy).jpg",
            "Foto - Kopie.jpg", "x - copy (3).heic", "Pictures/sub/IMG (12).JPG")) {
            assertTrue(n, CopyNames.hasCopyMarker(n))
        }
        for (n in listOf("IMG_1.jpg", "Holiday (2023).jpg", "copyright.jpg", "DCIM/Camera/PXL_20240101.jpg", ".hidden")) {
            assertFalse(n, CopyNames.hasCopyMarker(n))
        }
    }

    @Test fun originalFolders() {
        assertTrue(CopyNames.isOriginalFolder("DCIM/Camera/"))
        assertTrue(CopyNames.isOriginalFolder("DCIM"))
        assertTrue(CopyNames.isOriginalFolder("Pictures/Screenshots/IMG.png"))
        assertTrue(CopyNames.isOriginalFolder("Movies/Screen recordings"))
        assertFalse(CopyNames.isOriginalFolder("Download/"))
        assertFalse(CopyNames.isOriginalFolder("Pictures/WhatsApp/DCIM-backup"))
    }

    @Test fun sizesAndCounts() {
        val us = Locale.US
        assertEquals("999 B", Fmt.bytes(999, us))
        assertEquals("1.5 KB", Fmt.bytes(1500, us))
        assertEquals("12 MB", Fmt.bytes(12_345_678, us))
        assertEquals("1.2 GB", Fmt.bytes(1_234_567_890, us))
        assertEquals("25 GB", Fmt.bytes(25_000_000_000, us))
        assertEquals("1,234", Fmt.count(1234, us))
        assertEquals("1 photo", Fmt.plural(1, "photo", "photos", us))
        assertEquals("2 photos", Fmt.plural(2, "photo", "photos", us))
    }
}

class ParallelismTest {
    @Test fun oneThreadPerCoreAsFarAsMemoryAllows() {
        val gb = 1L shl 30
        assertEquals(8, Parallelism.threads(8, 4 * gb, 400L shl 20))          // plenty: every core
        assertEquals(12, Parallelism.threads(12, 8 * gb, 1 * gb))
        assertEquals(3, Parallelism.threads(8, 300L shl 20, 400L shl 20))     // little free memory: fewer
        assertEquals(2, Parallelism.threads(8, 4 * gb, 40L shl 20))           // little heap left: fewer
        assertEquals(1, Parallelism.threads(8, 0, 0))                          // never none
        assertEquals(1, Parallelism.threads(8, -5, 10))
    }

    @Test fun stuckFilesAndHeat() {
        assertEquals(60_000L, Parallelism.stuckAfterMs(0))
        assertEquals(460_000L, Parallelism.stuckAfterMs(4_000_000_000L))       // a 4 GB video gets ~8 minutes
        assertEquals(8, Parallelism.allowed(8, 2))
        assertEquals(4, Parallelism.allowed(8, 3))
        assertEquals(1, Parallelism.allowed(8, 4))
        assertEquals(1, Parallelism.allowed(1, 3))
    }
}

class BudgetAndJournalTest {
    @Test fun decodingNeedsFollowTheFormat() {
        val mb = 1L shl 20
        // a 12-megapixel JPEG is decoded at half size
        assertEquals(12_000_000L / 4 * 4 + 4 * mb, Parallelism.decodeBytes("image/jpeg", "a.jpg", 4000, 3000))
        // HEIC is decoded whole, RAW developed whole
        assertTrue(Parallelism.decodeBytes("image/heic", "a.heic", 8160, 6120) > 290 * mb)
        assertTrue(Parallelism.decodeBytes("image/x-adobe-dng", "a.dng", 4000, 3000) > 270 * mb)
        assertTrue(Parallelism.decodeBytes("image/jpeg", "a.DNG", 4000, 3000) > 270 * mb)
        // unknown size: as 12 megapixels
        assertEquals(Parallelism.decodeBytes("image/jpeg", "a.jpg", 4000, 3000), Parallelism.decodeBytes("image/jpeg", "a.jpg", 0, 0))
        assertTrue(Parallelism.decodeBytes("image/png", "s.png", 1080, 2400) < 16 * mb)
    }

    @Test fun budgetNeverGivesOutMoreThanThereIs() {
        val budget = Budget(100)
        val inUse = java.util.concurrent.atomic.AtomicLong()
        val worst = java.util.concurrent.atomic.AtomicLong()
        val bigRan = java.util.concurrent.atomic.AtomicBoolean()
        val threads = (0 until 8).map { t ->
            kotlin.concurrent.thread {
                val rnd = kotlin.random.Random(t)
                repeat(60) { k ->
                    // thread 0 asks for more than the whole budget now and then: it must get its turn, alone
                    val need = if (t == 0 && k % 20 == 5) 500L else 5L + rnd.nextInt(40)
                    val got = budget.acquire(need)
                    val now = inUse.addAndGet(got)
                    worst.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    if (need == 500L) {
                        assertEquals(100L, got)
                        assertEquals(100L, now)
                        bigRan.set(true)
                    }
                    Thread.sleep(1)
                    inUse.addAndGet(-got)
                    budget.release(got)
                }
            }
        }
        threads.forEach { it.join(20_000) }
        assertTrue(threads.none { it.isAlive })
        assertTrue(worst.get() in 1..100)
        assertTrue(bigRan.get())
    }

    @Test fun waitingForTheBudgetCanBeStopped() {
        val budget = Budget(10)
        val held = budget.acquire(10)
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        var stopped = false
        val waiter = kotlin.concurrent.thread {
            try {
                budget.acquire(5) { stop.get() }
            } catch (_: java.util.concurrent.CancellationException) {
                stopped = true
            }
        }
        Thread.sleep(150)
        assertTrue(waiter.isAlive)
        stop.set(true)
        waiter.join(5_000)
        assertTrue(stopped)
        // the one who left doesn't block those behind it
        budget.release(held)
        assertEquals(7L, budget.acquire(7))
    }

    @Test fun journalRemembersWhatWasInFlightWhenTheAppDied() {
        val file = java.io.File.createTempFile("journal", ".bin")
        try {
            assertTrue(Journal.read(file).isEmpty)
            val j = Journal.open(file)!!
            j.begin(0, "external_primary/i/11", alone = false)
            j.begin(1, "external_primary/i/12", alone = false)
            j.begin(3, "1a2b-3c4d/i/99", alone = false)
            j.end(1)
            // the app dies here: nothing is closed
            var left = Journal.read(file)
            assertEquals(setOf("external_primary/i/11", "1a2b-3c4d/i/99"), left.together)
            assertTrue(left.alone.isEmpty())

            // the next run reads the suspects one at a time, and dies on one of them
            val j2 = Journal.open(file)!!
            assertTrue(Journal.read(file).isEmpty)
            j2.begin(0, "external_primary/i/11", alone = true)
            j2.end(0)
            j2.begin(0, "1a2b-3c4d/i/99", alone = true)
            left = Journal.read(file)
            assertEquals(setOf("1a2b-3c4d/i/99"), left.alone)
            assertTrue(left.together.isEmpty())

            // a run that ends in order leaves nothing
            val j3 = Journal.open(file)!!
            j3.begin(2, "external_primary/v/5", alone = false)
            j3.end(2)
            j3.close()
            assertTrue(Journal.read(file).isEmpty)
            // a key too long for a slot is simply not noted
            val j4 = Journal.open(file)!!
            j4.begin(0, "x".repeat(300), alone = true)
            assertTrue(Journal.read(file).isEmpty)
            j4.close()
        } finally {
            file.delete()
        }
        // rubbish in the file is not a suspect
        val junk = java.io.File.createTempFile("journal", ".bin")
        try {
            junk.writeBytes(ByteArray(400) { 7 })
            assertTrue(Journal.read(junk).isEmpty)
        } finally {
            junk.delete()
        }
    }
}

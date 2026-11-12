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

    @Test fun captureTimesAreTheCamerasOwnClock() {
        // 2024-05-01 10:05:03, whatever zone the photo was taken in or the phone is in now
        val t = java.time.LocalDateTime.of(2024, 5, 1, 10, 5, 3).toEpochSecond(java.time.ZoneOffset.UTC).toDouble()
        assertEquals(t, ExifTime.seconds("2024:05:01 10:05:03")!!, 0.0)
        assertEquals(t, ExifTime.seconds("2024-05-01T10:05:03")!!, 0.0)           // some cameras write it this way
        assertEquals(t, ExifTime.seconds("2024:05:01 10:05:03\u0000")!!, 0.0)
        // fractions of a second tell the frames of a burst apart
        assertEquals(t + 0.25, ExifTime.seconds("2024:05:01 10:05:03", "25")!!, 1e-9)
        assertEquals(t + 0.5, ExifTime.seconds("2024:05:01 10:05:03", "5")!!, 1e-9)
        assertEquals(t + 0.123456, ExifTime.seconds("2024:05:01 10:05:03", "123456 ")!!, 1e-9)
        assertEquals(t, ExifTime.seconds("2024:05:01 10:05:03", "x1")!!, 0.0)
        assertEquals(1.0, ExifTime.seconds("2024:05:01 10:05:04")!! - ExifTime.seconds("2024:05:01 10:05:03")!!, 0.0)
        // not dates
        for (bad in listOf(null, "", "0000:00:00 00:00:00", "    :  :     :  :  ", "2024:06:31 10:00:00", "2024:13:01 10:00:00", "2024:05:01", "yesterday")) {
            assertEquals(bad, null, ExifTime.seconds(bad))
        }
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
        assertEquals(8, Parallelism.threads(8, 400L shl 20, 400L shl 20))     // 48 MB free for each is enough
        assertEquals(3, Parallelism.threads(8, 150L shl 20, 400L shl 20))     // little free memory: fewer
        assertEquals(2, Parallelism.threads(8, 4 * gb, 40L shl 20))           // little heap left: fewer
        assertEquals(1, Parallelism.threads(8, 0, 0))                          // never none
        assertEquals(1, Parallelism.threads(8, -5, 10))
    }

    @Test fun photosAreDecodedReducedByAPowerOfTwo() {
        // the phones' usual sizes land between 960 and 1024 px in one step
        assertEquals(4, Parallelism.sampleFor(4000))
        assertEquals(4, Parallelism.sampleFor(4032))
        assertEquals(4, Parallelism.sampleFor(4080))
        assertEquals(8, Parallelism.sampleFor(8160))
        assertEquals(2, Parallelism.sampleFor(2048))
        assertEquals(2, Parallelism.sampleFor(1920))
        // the others stay just above, and are reduced the rest of the way afterwards
        assertEquals(4, Parallelism.sampleFor(6000))      // 1500 px
        assertEquals(2, Parallelism.sampleFor(3264))      // 1632 px
        assertEquals(1, Parallelism.sampleFor(1919))
        assertEquals(1, Parallelism.sampleFor(1024))
        assertEquals(1, Parallelism.sampleFor(1))
        assertEquals(1, Parallelism.sampleFor(0))
        // decoders that reduce by skipping pixels go half the way: the last halving averages
        assertEquals(4, Parallelism.sampleFor(4032, "image/jpeg"))
        assertEquals(4, Parallelism.sampleFor(4032, "image/webp"))
        assertEquals(2, Parallelism.sampleFor(4032, "image/heic"))
        assertEquals(2, Parallelism.sampleFor(4032, "image/heif"))
        assertEquals(1, Parallelism.sampleFor(2400, "image/png"))
        assertEquals(1, Parallelism.sampleFor(1000, "image/png"))
        assertEquals(2, Parallelism.sampleFor(4032, null))
        for (long in 1..40_000) {
            val s = Parallelism.sampleFor(long)
            assertEquals(1, Integer.bitCount(s))
            // never below the smallest size a large photo is measured at, never twice more than needed
            if (long >= ImageAnalysis.SMALLEST_REDUCED) assertTrue(long / s >= ImageAnalysis.SMALLEST_REDUCED)
            assertTrue(long / s < 2 * ImageAnalysis.SMALLEST_REDUCED || s == 1)
        }
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
        // a 12-megapixel JPEG is decoded at a quarter of its size
        assertEquals(12_000_000L / 16 * 4 + 4 * mb, Parallelism.decodeBytes("image/jpeg", "a.jpg", 4000, 3000))
        // HEIC is decoded whole, RAW developed whole
        assertTrue(Parallelism.decodeBytes("image/heic", "a.heic", 8160, 6120) > 290 * mb)
        assertTrue(Parallelism.decodeBytes("image/x-adobe-dng", "a.dng", 4000, 3000) > 270 * mb)
        assertTrue(Parallelism.decodeBytes("image/jpeg", "a.DNG", 4000, 3000) > 270 * mb)
        // unknown size: as 12 megapixels
        assertEquals(Parallelism.decodeBytes("image/jpeg", "a.jpg", 4000, 3000), Parallelism.decodeBytes("image/jpeg", "a.jpg", 0, 0))
        assertTrue(Parallelism.decodeBytes("image/png", "s.png", 1080, 2400) < 16 * mb)
        // a PNG is reduced only half the way while it is read
        assertEquals(12_000_000L / 4 * 4 + 4 * mb, Parallelism.decodeBytes("image/png", "a.png", 4000, 3000))
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

    @Test fun aHolderThatHangsDoesNotKeepTheOthersWaitingForever() {
        val budget = Budget(100)
        val hung = budget.acquire(70)          // a decoder that never comes back
        val got = java.util.concurrent.atomic.AtomicLong(-1)
        val small = java.util.concurrent.atomic.AtomicLong(-1)
        // first in the queue: a need that no longer fits; behind it, one that would
        val big = kotlin.concurrent.thread { got.set(budget.acquire(60)) }
        Thread.sleep(100)
        val little = kotlin.concurrent.thread { small.set(budget.acquire(10)) }
        Thread.sleep(200)
        assertTrue(big.isAlive)
        assertTrue(little.isAlive)             // first come, first served: it waits behind the big one
        // the scan gives up on the hung thread: its memory is lost, the rest is shared out
        budget.writeOff(hung)
        big.join(5_000)
        assertFalse(big.isAlive)
        assertEquals(30L, got.get())           // cut to what there is now: it runs alone
        assertTrue(little.isAlive)
        budget.release(got.get())
        little.join(5_000)
        assertEquals(10L, small.get())
        // if the hung thread ever comes back, it has nothing left to give back
        budget.release(0)
        budget.release(small.get())
        assertEquals(30L, budget.acquire(1000))
    }

    @Test fun aJournalThatWasClosedTakesNoMoreNotes() {
        val file = java.io.File.createTempFile("journal", ".bin")
        try {
            val j = Journal.open(file)!!
            j.begin(0, "external_primary/i/1", alone = true)
            j.close()
            // a reading thread that is still at work when the scan has ended
            j.begin(1, "external_primary/i/2", alone = true)
            j.end(0)
            j.close()
            assertEquals(0L, file.length())
            assertTrue(Journal.read(file).isEmpty)
        } finally {
            file.delete()
        }
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

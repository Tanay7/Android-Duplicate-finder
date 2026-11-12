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

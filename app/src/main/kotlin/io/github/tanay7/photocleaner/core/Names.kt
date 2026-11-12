package io.github.tanay7.photocleaner.core

import java.text.NumberFormat
import java.util.Locale

object CopyNames {
    /** DupeFinder's copy markers: "(1)", " - Copy", "Copy of ", "_copy 2", "(copy)", " - Kopie", " - Copie". */
    private val MARKER = Regex(
        """(\(\d{1,3}\)$|\s*-\s*copy(\s*\(\d+\))?$|^copy of\s|[\s_]copy(\s?\d+)?$|\(copy\)|\s*-\s*kopie$|\s*-\s*copie$)""",
        RegexOption.IGNORE_CASE,
    )

    fun hasCopyMarker(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return MARKER.containsMatchIn(stem)
    }

    private val RAW = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "rwl", "3fr",
        "iiq", "erf", "kdc", "mos",
    )

    /** A camera RAW file, by its name (DupeFinder's list). */
    fun isRaw(path: String): Boolean = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in RAW

    /**
     * Folders where phones create originals: the camera (DCIM), screenshots and screen recordings.
     * A copy there is kept before one that was saved, downloaded or shared elsewhere.
     */
    fun isOriginalFolder(path: String): Boolean {
        val parts = path.lowercase(Locale.ROOT).split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() == "dcim") return true
        return parts.any { it == "screenshots" || it == "screen recordings" || it == "screenrecorder" }
    }
}

object Fmt {
    /** Sizes in powers of 1000, as Android's own Files app shows them. */
    fun bytes(b: Long, locale: Locale = Locale.getDefault()): String = when {
        b < 1000 -> "$b B"
        b < 1_000_000 -> unit(b / 1e3, "KB", locale)
        b < 1_000_000_000 -> unit(b / 1e6, "MB", locale)
        else -> unit(b / 1e9, "GB", locale)
    }

    private fun unit(v: Double, name: String, locale: Locale): String =
        if (v < 10) String.format(locale, "%.1f %s", v, name) else String.format(locale, "%.0f %s", v, name)

    fun count(n: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(n)

    fun plural(n: Int, one: String, many: String, locale: Locale = Locale.getDefault()): String =
        "${count(n, locale)} ${if (n == 1) one else many}"
}

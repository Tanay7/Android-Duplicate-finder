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

/** The moment a photo was taken, as its EXIF data tells it. */
object ExifTime {
    private val DATE = Regex("""^\s*(\d{4})\D(\d{1,2})\D(\d{1,2})\D+(\d{1,2})\D(\d{1,2})\D(\d{1,2})""")

    /**
     * Seconds since 1970 of "2024:05:01 10:05:03" read as the camera's own clock showed it (as if it
     * were UTC), with the fraction of [subSec] ("25" is a quarter of a second). Null if it is no date.
     *
     * The camera's clock, not the instant: the difference between two shots of one camera is the
     * same either way, a copy keeps the time of its original, and the time can be shown as it was
     * where the photo was taken, whatever zone the phone is in now.
     */
    fun seconds(dateTime: String?, subSec: String? = null): Double? {
        val m = DATE.find(dateTime ?: return null) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        val whole = try {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), minOf(sec.toInt(), 59))
                .toEpochSecond(java.time.ZoneOffset.UTC)
        } catch (_: Exception) {
            return null         // "0000:00:00 00:00:00", the 31st of June
        }
        if (whole <= 0) return null
        val digits = subSec?.trim().orEmpty()
        val fraction = if (digits.isNotEmpty() && digits.length <= 9 && digits.all { it in '0'..'9' }) "0.$digits".toDouble() else 0.0
        return whole + fraction
    }
}

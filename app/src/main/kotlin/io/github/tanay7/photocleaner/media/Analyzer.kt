// The framework's ExifInterface is as good as AndroidX's on the Android versions this app needs (16+).
@file:Suppress("ExifInterface")

package io.github.tanay7.photocleaner.media

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import io.github.tanay7.photocleaner.core.Budget
import io.github.tanay7.photocleaner.core.ExifTime
import io.github.tanay7.photocleaner.core.ImageAnalysis
import io.github.tanay7.photocleaner.core.ImageFeatures
import io.github.tanay7.photocleaner.core.Parallelism
import io.github.tanay7.photocleaner.core.Scratch
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** Reads files: their SHA-256 and, for photos, what their pixels look like. */
object Analyzer {
    /**
     * What one reading thread keeps from file to file: the analysis buffers, a read buffer and the
     * hash state. Reusing them means a photo allocates almost nothing on the Java heap, so every
     * core can read at once without the garbage collector pausing them all. One per thread.
     *
     * [stop] ends a long read early; [touch] is called when a wait for memory is over, so the wait
     * doesn't count as time the file took.
     */
    class Worker(val stop: AtomicBoolean = AtomicBoolean(), val touch: () -> Unit = {}) {
        val scratch = Scratch()
        val buf = ByteArray(1 shl 20)
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        /**
         * Decoding memory this thread holds of the scan's [Budget] right now. Whoever takes it (sets
         * it to 0) gives it back: the thread itself when it is done, or the scan when the thread hangs.
         */
        val held = AtomicLong()
    }

    /**
     * How a scan reads its files.
     *
     * [original]: the files' own bytes (the app holds the media-location permission) rather than the
     * copies Android hands out with the place a photo was taken blanked out. Only the own bytes make
     * "identical" mean byte for byte, and they are quicker to get: Android doesn't have to look for
     * location data in every file, and reads don't pass through its filter.
     *
     * [budget]: the memory decoders may use at once, in bytes.
     */
    class Reading(val cr: ContentResolver, val original: Boolean, val budget: Budget)

    /**
     * Reads a photo, opening it once: its SHA-256 (streamed, never held in memory) and, if
     * [wantFeatures], its EXIF capture time and camera and its pixels, decoded reduced to 960-1024 px
     * (turned the way its EXIF data says, in sRGB) and measured. Failures come back in the
     * row's error, except running out of memory, which the scanner answers by trying the file again
     * on its own.
     */
    fun photo(rd: Reading, item: MediaItem, wantFeatures: Boolean, w: Worker): FeatureRow {
        val pfd = try {
            open(rd, item)
        } catch (e: Exception) {
            return failed(item, rd, "Can't read: ${e.message ?: e.javaClass.simpleName}")
        }
        pfd.use {
            val sha = try {
                sha256(pfd, w)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failed(item, rd, "Can't read: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!wantFeatures) return row(item, rd, sha, item.shownWidth, item.shownHeight, null, null, null, null)
            // Memory first: decoding (and, for a RAW file, even reading its EXIF data) can take hundreds
            // of megabytes. Waiting for it isn't time the file took: the watchdog's clock is reset meanwhile.
            w.held.set(
                rd.budget.acquire(Parallelism.decodeBytes(item.mime, item.name, item.width, item.height)) {
                    w.touch()
                    w.stop.get()
                },
            )
            w.touch()
            var taken: Double? = null
            var camera: String? = null
            try {
                try {
                    Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                    val exif = ExifInterface(FileInputStream(pfd.fileDescriptor))
                    taken = ExifTime.seconds(
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                    )
                    camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                        .joinToString(" ") { it.trim() }.trim().ifEmpty { null }
                } catch (_: Exception) {
                    // no EXIF data (PNG, GIF) or unreadable: no capture time, so it can't be a burst frame
                }
                return try {
                    val d = decode(pfd, w)
                    row(item, rd, sha, d.width, d.height, taken, camera, d.features, null)
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (e: Exception) {
                    row(item, rd, sha, item.shownWidth, item.shownHeight, taken, camera, null, "$CANT_DECODE${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                rd.budget.release(w.held.getAndSet(0))
            }
        }
    }

    /** A video: only its SHA-256, for exact copies. */
    fun video(rd: Reading, item: MediaItem, w: Worker): FeatureRow = try {
        open(rd, item).use { row(item, rd, sha256(it, w), item.shownWidth, item.shownHeight, null, null, null, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(item, rd, "Can't read: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * True if Android gives this app the files' own bytes. Asked of a few files before a scan (with
     * the permission it always should; this makes sure, so a whole scan never fails on it).
     */
    fun givesOriginals(cr: ContentResolver, items: List<MediaItem>): Boolean {
        for (item in items.take(5)) {
            try {
                cr.openFileDescriptor(MediaStore.setRequireOriginal(item.uri), "r")?.close() ?: continue
                return true
            } catch (_: UnsupportedOperationException) {
                return false
            } catch (_: SecurityException) {
                return false
            } catch (_: Exception) {
                // this file can't be opened at all: ask the next one
            }
        }
        return items.isEmpty()
    }

    private fun open(rd: Reading, item: MediaItem): ParcelFileDescriptor =
        rd.cr.openFileDescriptor(if (rd.original) MediaStore.setRequireOriginal(item.uri) else item.uri, "r")
            ?: throw IOException("no access")

    private fun row(
        item: MediaItem, rd: Reading, sha: String?, w: Int, h: Int, taken: Double?, camera: String?, f: ImageFeatures?, error: String?,
    ) = FeatureRow(item.key, item.size, item.modified, sha, w, h, taken, camera, f, error, rd.original)

    fun failed(item: MediaItem, rd: Reading, why: String) = row(item, rd, null, item.shownWidth, item.shownHeight, null, null, null, why)

    /** A file that made the app die when it was read on its own: left out until it changes. */
    fun quarantined(item: MediaItem, original: Boolean) =
        FeatureRow(item.key, item.size, item.modified, null, item.shownWidth, item.shownHeight, null, null, null, QUARANTINED, original)

    const val CANT_DECODE = "Can't decode: "
    const val QUARANTINED = "Skipped: reading it stopped the app"

    class Decoded(val width: Int, val height: Int, val features: ImageFeatures)

    private fun decode(pfd: ParcelFileDescriptor, w: Worker): Decoded {
        var fullW = 0
        var fullH = 0
        // The decoder reads through its own handle on the same open file, from the start, and closes it.
        val source = ImageDecoder.createSource(Callable { AssetFileDescriptor(pfd.dup(), 0, AssetFileDescriptor.UNKNOWN_LENGTH) })
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            fullW = info.size.width
            fullH = info.size.height
            // Reduced by a power of two as it is decoded (the decoder's quick way), never to an
            // arbitrary size (which it reaches by decoding everything and skipping pixels).
            val sample = Parallelism.sampleFor(max(fullW, fullH), info.mimeType)
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Phones save photos in a wider colour space; converted here once, the pixels come out as
            // plain sRGB without a conversion for every row that is read.
            decoder.setTargetColorSpace(SRGB)
        }
        val held = ArrayList<Bitmap>(3)
        held.add(bmp)
        try {
            // The decoder turns the photo the way its EXIF data says; report the size it is shown at.
            if (bmp.width != bmp.height && (bmp.width > bmp.height) != (fullW > fullH)) {
                val t = fullW
                fullW = fullH
                fullH = t
            }
            var b = bmp
            val long = max(b.width, b.height)
            if (long > ImageAnalysis.REDUCED_SIZE) {
                // Still larger (a power of two didn't land in range, or the decoder couldn't reduce):
                // the rest of the way, smoothly.
                val s = ImageAnalysis.REDUCED_SIZE.toDouble() / long
                b = Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
                held.add(b)
            }
            if (b.config != Bitmap.Config.ARGB_8888) {
                b = b.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("can't convert ${b.config}")
                held.add(b)
            }
            val src = b
            val f = ImageAnalysis.analyze(src.width, src.height, w.scratch) { y0, count, out ->
                src.getPixels(out, 0, src.width, 0, y0, src.width, count)
            }
            return Decoded(fullW, fullH, f)
        } finally {
            for (x in held) x.recycle()
        }
    }

    private val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    /**
     * SHA-256 of a whole file, read from its start through the worker's buffer. An empty file has no
     * fingerprint: empty files aren't copies of each other in any useful sense.
     */
    private fun sha256(pfd: ParcelFileDescriptor, w: Worker): String {
        val digest = w.digest
        digest.reset()
        var total = 0L
        // Reads from the handle's position (the start: it was just opened) and doesn't close it.
        val stream = FileInputStream(pfd.fileDescriptor)
        while (true) {
            val n = stream.read(w.buf)
            if (n < 0) break
            digest.update(w.buf, 0, n)
            total += n
            if (w.stop.get()) throw CancellationException()
        }
        if (total == 0L) throw IOException("the file is empty")
        return hex(digest.digest())
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF])
            sb.append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

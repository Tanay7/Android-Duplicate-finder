package io.github.tanay7.photocleaner.core

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * How a scan shares out the phone: how many threads it runs (Lean Reader's rule: one per core, as far
 * as memory allows), how long a file may take, how many threads rest when the phone is hot, and how
 * much a photo is reduced as it is decoded and how much memory that takes.
 */
object Parallelism {
    /**
     * Native memory to have free for each reading thread. By [decodeBytes], a 12-megapixel JPEG takes
     * about 7 MB to decode reduced; the rest is a margin for the app's other memory and the system's.
     * (Decoders that need far more, for HEIC, AVIF and RAW files, share the scan's [Budget] on top
     * of this rule.)
     */
    const val NATIVE_PER_THREAD = 48L shl 20
    /** Java memory one reading thread needs: its reusable buffers (1024 px x 2 grey copies) and a read buffer. */
    const val HEAP_PER_THREAD = 16L shl 20

    /**
     * One thread per core, no more than free memory allows, at least one: [freeNative] is what the
     * system can still give (available minus its low-memory threshold), [freeHeap] what is left of
     * the app's Java heap.
     */
    fun threads(cores: Int, freeNative: Long, freeHeap: Long): Int =
        max(1, min(cores.toLong(), min(freeNative / NATIVE_PER_THREAD, freeHeap / HEAP_PER_THREAD)).toInt())

    /**
     * How long one file may take before the scan gives up on it (a failing SD card can block a read
     * forever): a minute, plus a second for every 10 MB.
     */
    fun stuckAfterMs(bytes: Long): Long = 60_000L + max(0, bytes) / 10_000L

    /** Workers allowed to take new files at a thermal status (0 none ... 6 shutdown): all, half when severe, one when critical. */
    fun allowed(threads: Int, thermalStatus: Int): Int = when {
        thermalStatus >= 4 -> 1
        thermalStatus >= 3 -> max(1, threads / 2)
        else -> threads
    }

    /**
     * The reduction (1, 2, 4, 8 ...) to decode a photo at whose long side has [long] pixels: the
     * largest power of two that leaves at least [ImageAnalysis.SMALLEST_REDUCED] of them (1 for a
     * smaller photo).
     *
     * A JPEG decoder reduces by 2, 4 or 8 as it decodes, for less work than the whole picture.
     * Asked for any other size (1024 px of a 4000 px photo, a reduction by 3.9), Android's decoder
     * reads the whole picture, keeps every third pixel and scales the rest: slower (with Pillow's
     * libjpeg-turbo on a desktop, a 12-megapixel photo took 51 ms whole and 26 ms at a quarter, a
     * 50-megapixel one 248 ms whole and 92 ms at an eighth), and the skipped pixels leave jagged
     * detail. So the decoder is asked for a power of two, and what comes out larger than
     * [ImageAnalysis.REDUCED_SIZE] is reduced the rest of the way, smoothly. The phones' usual sizes
     * (4000, 4032, 4080 and 8160 px) come out at 1000 to 1020 px with no second step.
     */
    fun sampleFor(long: Int): Int {
        var s = 1
        while (long / (s * 2) >= ImageAnalysis.SMALLEST_REDUCED) s *= 2
        return s
    }

    /**
     * The same for a file of type [mime]. JPEG and WebP decoders reduce with every pixel (in the
     * JPEG transform, or libwebp's averaging scaler). The others don't: a PNG decoder keeps every
     * n-th pixel, and HEIC and GIF pictures are decoded whole and shrunk by Android with a bilinear
     * filter, which skips pixels when it shrinks by more than half (AVIF is decoded whole and shrunk
     * by its own library). So they are asked for only half the reduction, and the last halving is
     * done afterwards, averaging: a photo is measured as sharp as it is, whatever its format.
     */
    fun sampleFor(long: Int, mime: String?): Int {
        val s = sampleFor(long)
        val type = mime?.lowercase().orEmpty()
        return if (s == 1 || type.endsWith("/jpeg") || type.endsWith("/jpg") || type.endsWith("/webp")) s else s / 2
    }

    /**
     * Roughly the memory (bytes) that decoding a [width] x [height] photo takes at its peak. JPEG, PNG
     * and WebP are decoded reduced as they are read. HEIC, AVIF and GIF are decoded whole before they
     * are reduced, and a RAW file is developed whole (estimated at 24 bytes a pixel): by this estimate
     * a 50-megapixel HEIC takes over 300 MB, a RAW of that size 1.2 GB. Unknown sizes count as 12
     * megapixels.
     */
    fun decodeBytes(mime: String, name: String, width: Int, height: Int): Long {
        val known = width > 0 && height > 0
        val px = if (known) width.toLong() * height else 12_000_000L
        val long = if (known) max(width, height) else 4000
        val sample = sampleFor(long, mime).toLong()
        val reduced = px / (sample * sample) * 4 + (4L shl 20)
        val type = mime.lowercase()
        return when {
            CopyNames.isRaw(name) || type.contains("raw") || type.endsWith("dng") -> px * 24
            type.endsWith("heic") || type.endsWith("heif") || type.endsWith("avif") -> px * 6 + reduced
            type.endsWith("gif") -> px * 4 + reduced
            else -> reduced
        }
    }
}

/**
 * Shares out memory among threads that each need much of it for a moment. A thread waits while the
 * others' needs and its own exceed the total; a need larger than the total runs alone. First come,
 * first served, so a big need isn't kept waiting forever by a stream of small ones.
 *
 * No wait lasts forever: whoever holds units gives them back, and for a holder that hangs (a decoder
 * stuck on a failing card) the scan calls [writeOff].
 */
class Budget(total: Long) {
    private var total = max(1, total)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var used = 0L
    private var tickets = 0L
    private var serving = 0L
    private val gaveUp = HashSet<Long>()

    /**
     * Waits for [need] units and returns how many to give back with [release]. Throws a
     * CancellationException once [stop] says so.
     */
    fun acquire(need: Long, stop: () -> Boolean = { false }): Long {
        lock.withLock {
            val ticket = tickets++
            var granted = 0L
            try {
                while (true) {
                    // The total can shrink while waiting (see writeOff): a need is cut to what there is.
                    val n = need.coerceIn(1, total)
                    if (ticket == serving && used + n <= total) {
                        used += n
                        granted = n
                        break
                    }
                    if (stop()) throw CancellationException()
                    changed.await(100, TimeUnit.MILLISECONDS)
                }
            } finally {
                // Whoever leaves the head of the queue lets the next one try; whoever leaves further
                // back is skipped when its turn comes.
                if (granted > 0 || ticket == serving) {
                    serving++
                    while (gaveUp.remove(serving)) serving++
                } else {
                    gaveUp.add(ticket)
                }
                changed.signalAll()
            }
            return granted
        }
    }

    fun release(units: Long) {
        if (units <= 0) return
        lock.withLock {
            used -= units
            changed.signalAll()
        }
    }

    /**
     * [units] held by a thread that hangs and will never give them back: that memory is lost to this
     * budget, and what is left is shared out among the others. Without this, everyone waiting
     * behind a need that no longer fits would wait forever.
     */
    fun writeOff(units: Long) {
        if (units <= 0) return
        lock.withLock {
            used -= units
            total = max(1, total - units)
            changed.signalAll()
        }
    }
}

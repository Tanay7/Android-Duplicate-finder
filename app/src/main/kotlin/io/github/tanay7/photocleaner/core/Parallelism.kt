package io.github.tanay7.photocleaner.core

import kotlin.math.max
import kotlin.math.min

/** How many threads a scan may run (Lean Reader's rule: one per core, as far as memory allows). */
object Parallelism {
    /** Native memory one reading thread may use at once: a decoder at work and its picture. */
    const val NATIVE_PER_THREAD = 96L shl 20
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
    fun stuckAfterMs(bytes: Long): Long = 60_000L + bytes / 10_000L

    /** Workers allowed to take new files at a thermal status (0 none ... 6 shutdown): all, half when severe, one when critical. */
    fun allowed(threads: Int, thermalStatus: Int): Int = when {
        thermalStatus >= 4 -> 1
        thermalStatus >= 3 -> max(1, threads / 2)
        else -> threads
    }
}

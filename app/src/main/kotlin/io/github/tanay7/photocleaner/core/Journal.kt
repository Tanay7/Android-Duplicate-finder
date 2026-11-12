package io.github.tanay7.photocleaner.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Notes which files are being read right now, in a small file that outlives the app.
 *
 * A crash inside a picture decoder can't be caught: the whole app dies. When that happens, the next
 * scan finds here which files were in flight. Those read alongside others are only suspects, and are
 * read again one at a time; a file that was being read on its own when the app died is the culprit,
 * and is left out from then on. So one bad file can stop a scan twice, and never a third time.
 *
 * Every reading thread has a slot. Writes go straight to the file (no buffering), where the system
 * keeps them even if the app dies the next instant.
 */
class Journal private constructor(private val raf: RandomAccessFile) : Closeable {
    private val channel: FileChannel = raf.channel

    /** Notes that the thread of [slot] starts reading [key], on its own ([alone]) or alongside others. */
    fun begin(slot: Int, key: String, alone: Boolean) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        if (bytes.size > SLOT - 2) return
        val buf = ByteBuffer.allocate(SLOT)
        buf.put(if (alone) ALONE else TOGETHER).put(bytes.size.toByte()).put(bytes)
        buf.position(0).limit(SLOT)
        write(buf, slot)
    }

    /** Notes that the thread of [slot] has finished its file. */
    fun end(slot: Int) = write(ByteBuffer.wrap(byteArrayOf(EMPTY)), slot)

    private fun write(buf: ByteBuffer, slot: Int) {
        try {
            var at = slot.toLong() * SLOT
            while (buf.hasRemaining()) at += channel.write(buf, at)
        } catch (_: Exception) {
            // the journal is a precaution: reading goes on without it
        }
    }

    override fun close() {
        try {
            raf.setLength(0)
            raf.close()
        } catch (_: Exception) {
        }
    }

    /** What a run that died left in flight. */
    class Left(val together: Set<String>, val alone: Set<String>) {
        val isEmpty: Boolean get() = together.isEmpty() && alone.isEmpty()
    }

    companion object {
        /** Bytes of a slot: a state, a length and the file's key. */
        const val SLOT = 128
        private const val EMPTY: Byte = 0
        private const val TOGETHER: Byte = 1
        private const val ALONE: Byte = 2

        /** Reads what the last run left; nothing if it ended in order (or there is no journal). */
        fun read(file: File): Left {
            val together = HashSet<String>()
            val alone = HashSet<String>()
            try {
                if (file.length() in 1..(1L shl 20)) {
                    val data = file.readBytes()
                    var at = 0
                    while (at + SLOT <= data.size) {
                        val state = data[at]
                        val len = data[at + 1].toInt() and 0xFF
                        if ((state == TOGETHER || state == ALONE) && len in 1..SLOT - 2) {
                            val key = String(data, at + 2, len, Charsets.UTF_8)
                            if (state == ALONE) alone.add(key) else together.add(key)
                        }
                        at += SLOT
                    }
                }
            } catch (_: Exception) {
            }
            return Left(together, alone)
        }

        /** Starts an empty journal in [file]; null if it can't be written (reading then goes on without). */
        fun open(file: File): Journal? = try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            Journal(raf)
        } catch (_: Exception) {
            null
        }
    }
}

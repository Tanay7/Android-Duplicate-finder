// The framework's ExifInterface is as good as AndroidX's on the Android versions this app needs (16+).
@file:Suppress("ExifInterface")

package io.github.tanay7.photocleaner.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import io.github.tanay7.photocleaner.core.ImageAnalysis
import io.github.tanay7.photocleaner.core.ImageFeatures
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Reads files: their SHA-256 and, for photos, what their pixels look like. */
object Analyzer {
    /** Photos larger than this are fingerprinted but not decoded. */
    private const val MAX_DECODE_BYTES = 256L * 1024 * 1024

    /**
     * Reads a photo once: hashes its bytes and, if [wantFeatures], decodes it reduced to at most
     * 1024 px (turned the way its EXIF data says) and measures it.
     */
    fun photo(cr: ContentResolver, item: MediaItem, wantFeatures: Boolean): FeatureRow {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes: Bytes?
        try {
            val stream = cr.openInputStream(item.uri) ?: throw IOException("no stream")
            bytes = stream.use {
                if (wantFeatures && item.size <= MAX_DECODE_BYTES) readAll(it, item.size, digest) else {
                    drain(it, digest)
                    null
                }
            }
        } catch (e: Exception) {
            return FeatureRow(item.key, item.size, item.modified, null, 0, 0, null, null, null, "Can't read: ${e.message ?: e.javaClass.simpleName}")
        }
        val sha = hex(digest.digest())
        if (bytes == null) {
            val err = if (wantFeatures) "Too large to decode" else null
            return FeatureRow(item.key, item.size, item.modified, sha, item.shownWidth, item.shownHeight, null, null, null, err)
        }
        var taken: Double? = null
        var camera: String? = null
        try {
            val exif = ExifInterface(ByteArrayInputStream(bytes.data, 0, bytes.length))
            val t = exif.dateTimeOriginal
            if (t > 0) taken = t / 1000.0
            camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                .joinToString(" ") { it.trim() }.trim().ifEmpty { null }
        } catch (_: Exception) {
            // no EXIF data (PNG, GIF) or unreadable: no capture time, so it can't be a burst frame
        }
        return try {
            val d = decode(ByteBuffer.wrap(bytes.data, 0, bytes.length))
            FeatureRow(item.key, item.size, item.modified, sha, d.width, d.height, taken, camera, d.features, null)
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            FeatureRow(item.key, item.size, item.modified, sha, item.shownWidth, item.shownHeight, taken, camera, null,
                "Can't decode: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    class Decoded(val width: Int, val height: Int, val features: ImageFeatures)

    fun decode(bytes: ByteBuffer): Decoded {
        var fullW = 0
        var fullH = 0
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, info, _ ->
            fullW = info.size.width
            fullH = info.size.height
            val long = max(fullW, fullH)
            if (long > ImageAnalysis.REDUCED_SIZE) {
                val s = ImageAnalysis.REDUCED_SIZE.toDouble() / long
                decoder.setTargetSize(max(1, (fullW * s).roundToInt()), max(1, (fullH * s).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            // The decoder turns the photo the way its EXIF data says; report the size it is shown at.
            if (bmp.width != bmp.height && (bmp.width > bmp.height) != (fullW > fullH)) {
                val t = fullW
                fullW = fullH
                fullH = t
            }
            val px = pixels(bmp)
            return Decoded(fullW, fullH, ImageAnalysis.analyze(px, bmp.width, bmp.height))
        } finally {
            bmp.recycle()
        }
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        if (src !== bmp) src.recycle()
        return px
    }

    /** SHA-256 of a whole file, read in 1 MB chunks. */
    fun sha256(cr: ContentResolver, item: MediaItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = cr.openInputStream(item.uri) ?: throw IOException("no stream")
        stream.use { drain(it, digest) }
        return hex(digest.digest())
    }

    /** The whole file in one array (no copies: decoding needs it all anyway), hashed on the way. */
    private class Bytes(val data: ByteArray, val length: Int)

    private fun readAll(input: InputStream, expected: Long, digest: MessageDigest): Bytes {
        var buf = ByteArray(expected.coerceIn(1, Int.MAX_VALUE.toLong() - 64).toInt())
        var len = 0
        while (true) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) break
            digest.update(buf, len, n)
            len += n
        }
        return Bytes(buf, len)
    }

    private fun drain(input: InputStream, digest: MessageDigest) {
        val buf = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
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

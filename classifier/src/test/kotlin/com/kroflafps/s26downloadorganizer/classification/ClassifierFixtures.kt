package com.kroflafps.s26downloadorganizer.classification

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class BytesSource(val bytes: ByteArray, name: String = "fixture.dat") : ProbeSource {
    var meta = SourceMetadata(name, bytes.size.toLong(), "fixture-identity", "fixture-stamp")
    var closed = false
    var actualBytes = 0
    var reads = 0
    var largestRequest = 0
    var chunk = Int.MAX_VALUE
    var onRead: () -> Unit = {}
    var failure: Exception? = null
    override fun metadata() = meta
    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, count: Int): Int {
        failure?.let { throw it }
        reads++
        largestRequest = maxOf(largestRequest, count)
        val length = minOf(count, chunk, bytes.size - offset.toInt())
        if (length <= 0) return -1
        bytes.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + length)
        actualBytes += length
        onRead()
        return length
    }
    override fun close() { closed = true }
}

internal fun zipFixture(vararg entries: Pair<String, ByteArray>, stored: Boolean = false, comment: String? = null): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        comment?.let(zip::setComment)
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name).apply {
                if (stored) {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().also { it.update(bytes) }.value
                }
            }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
    }
    return output.toByteArray()
}
internal fun ByteArray.set16(offset: Int, value: Int) { this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte() }
internal fun ByteArray.set32(offset: Int, value: Long) { repeat(4) { this[offset + it] = (value ushr (8 * it)).toByte() } }
internal fun pdfFixture() = "%PDF-1.7\nsynthetic fixture\n%%EOF\n".toByteArray()
internal fun pngFixture() = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82) + ByteArray(17)
internal fun riffFixture(type: String, chunk: String = "data") = ("RIFF".toByteArray() + ByteArray(4) + type.toByteArray() + chunk.toByteArray() + ByteArray(4)).also { it.set32(4, it.size.toLong() - 8) }

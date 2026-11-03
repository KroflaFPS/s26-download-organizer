package com.kroflafps.s26downloadorganizer.classification

/** Traditional single-disk ZIP only; never inflates payloads or trusts marker names alone. */
internal class ZipProbe(private val probe: BoundedProbe) {
    data class Entry(val name: String, val offset: Long, val compressed: Long, val size: Long, val flags: Int, val method: Int, val crc: Long)
    data class Evidence(val names: Set<String>, val epubMimetype: Boolean)

    fun inspect(): Evidence {
        val tailSize = minOf(probe.size, 65_557L).toInt()
        val tailOffset = probe.size - tailSize
        val tail = probe.read(tailOffset, tailSize)
        val ends = mutableListOf<Int>()
        for (i in tail.size - 22 downTo 0) {
            if (i % 256 == 0) probe.check()
            if (tail.matchesAt(i, 0x50, 0x4b, 5, 6) && i + 22 + tail.u16(i + 20) == tail.size) {
                ends += i
                if (ends.size > 1) fail(Reason.MALFORMED_OR_PARTIAL)
            }
        }
        if (ends.size != 1) fail(Reason.MALFORMED_OR_PARTIAL)
        val end = ends.single()
        val eocd = tailOffset + end
        if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0) fail(Reason.UNSUPPORTED)
        val count = tail.u16(end + 10)
        val bytes = tail.u32(end + 12)
        val offset = tail.u32(end + 16)
        if (count == 65535 || bytes == 0xffffffffL || offset == 0xffffffffL ||
            (end >= 20 && tail.matchesAt(end - 20, 0x50, 0x4b, 6, 7))) fail(Reason.UNSUPPORTED)
        if (count > 128 || bytes > 32 * 1024) fail(Reason.LIMIT)
        if (tail.u16(end + 8) != count || offset > eocd || bytes != eocd - offset) fail(Reason.MALFORMED_OR_PARTIAL)
        if (count == 0) {
            if (bytes != 0L || offset != 0L || eocd != 0L) fail(Reason.MALFORMED_OR_PARTIAL)
            return Evidence(emptySet(), false)
        }
        val directory = probe.read(offset, bytes.toInt())
        val entries = mutableListOf<Entry>()
        var cursor = 0
        repeat(count) {
            probe.check()
            if (!directory.matchesAt(cursor, 0x50, 0x4b, 1, 2) || cursor > directory.size - 46) fail(Reason.MALFORMED_OR_PARTIAL)
            val flags = directory.u16(cursor + 8)
            val method = directory.u16(cursor + 10)
            // Encryption, patching, masked headers, unknown flag meanings and ZIP64 are held.
            if (directory.u16(cursor + 6) > 20 || flags and (0x800 or 8 or 6).inv() != 0 || method !in listOf(0, 8)) fail(Reason.UNSUPPORTED)
            val nameLength = directory.u16(cursor + 28)
            val extra = directory.u16(cursor + 30)
            val comment = directory.u16(cursor + 32)
            val length = 46 + nameLength + extra + comment
            if (nameLength !in 1..512 || length > directory.size - cursor) fail(Reason.MALFORMED_OR_PARTIAL)
            val rawName = directory.copyOfRange(cursor + 46, cursor + 46 + nameLength)
            // Conservative ASCII ZIP names; Unicode archive entries remain reviewable, not invalid.
            if (rawName.any { (it.toInt() and 255) !in 32..126 }) fail(Reason.UNSUPPORTED)
            val name = rawName.toString(Charsets.US_ASCII)
            if (name.startsWith('/') || '\\' in name || name.split('/').any { it == "." || it == ".." } || ':' in name || entries.any { it.name == name }) fail(Reason.MALFORMED_OR_PARTIAL)
            checkExtras(directory, cursor + 46 + nameLength, extra)
            val compressed = directory.u32(cursor + 20)
            val size = directory.u32(cursor + 24)
            val local = directory.u32(cursor + 42)
            if (compressed == 0xffffffffL || size == 0xffffffffL || local == 0xffffffffL || directory.u16(cursor + 34) != 0) fail(Reason.UNSUPPORTED)
            if (local >= offset || compressed > offset - local || (method == 0 && size != compressed)) fail(Reason.MALFORMED_OR_PARTIAL)
            entries += Entry(name, local, compressed, size, flags, method, directory.u32(cursor + 16))
            cursor += length
        }
        if (cursor != directory.size) fail(Reason.MALFORMED_OR_PARTIAL)
        var priorEnd = 0L
        var epub = false
        for (entry in entries.sortedBy { it.offset }) {
            probe.check()
            if (entry.offset < priorEnd) fail(Reason.MALFORMED_OR_PARTIAL)
            val local = probe.read(entry.offset, 30)
            if (!local.matchesAt(0, 0x50, 0x4b, 3, 4) || local.u16(6) != entry.flags || local.u16(8) != entry.method || local.u16(4) > 20) fail(Reason.MALFORMED_OR_PARTIAL)
            val nameLength = local.u16(26)
            val extraLength = local.u16(28)
            if (nameLength != entry.name.length || extraLength > 4096) fail(Reason.LIMIT)
            val dataOffset = entry.offset + 30 + nameLength + extraLength
            if (dataOffset > offset || entry.compressed > offset - dataOffset) fail(Reason.MALFORMED_OR_PARTIAL)
            val fields = probe.read(entry.offset + 30, nameLength + extraLength)
            if (!fields.asciiAt(0, entry.name)) fail(Reason.MALFORMED_OR_PARTIAL)
            checkExtras(fields, nameLength, extraLength)
            if (entry.flags and 8 == 0 && (local.u32(14) != entry.crc || local.u32(18) != entry.compressed || local.u32(22) != entry.size)) fail(Reason.MALFORMED_OR_PARTIAL)
            priorEnd = dataOffset + entry.compressed
            // Data descriptor sizes/CRC must match; both traditional signed/unsigned forms.
            if (entry.flags and 8 != 0) {
                val prefix = probe.read(priorEnd, 4)
                val signed = prefix.matchesAt(0, 0x50, 0x4b, 7, 8)
                val descriptor = probe.read(priorEnd + if (signed) 4 else 0, 12)
                if (descriptor.u32(0) != entry.crc || descriptor.u32(4) != entry.compressed || descriptor.u32(8) != entry.size) fail(Reason.MALFORMED_OR_PARTIAL)
                priorEnd += if (signed) 16 else 12
                if (priorEnd > offset) fail(Reason.MALFORMED_OR_PARTIAL)
            }
            if (entry.name == "mimetype" && entry.offset == 0L && entry.method == 0 && entry.flags and 8 == 0 && entry.size == 20L && extraLength == 0) {
                epub = probe.read(dataOffset, 20).asciiAt(0, "application/epub+zip")
            }
        }
        if (entries.minOf { it.offset } != 0L) fail(Reason.UNSUPPORTED) // No self-extracting prefix/polyglot support.
        return Evidence(entries.map { it.name }.toSet(), epub)
    }

    private fun checkExtras(bytes: ByteArray, start: Int, length: Int) {
        var cursor = start
        val end = start + length
        while (cursor < end) {
            probe.check()
            if (end - cursor < 4 || bytes.u16(cursor + 2) > end - cursor - 4) fail(Reason.MALFORMED_OR_PARTIAL)
            if (bytes.u16(cursor) in listOf(1, 0x0017, 0x9901)) fail(Reason.UNSUPPORTED)
            cursor += 4 + bytes.u16(cursor + 2)
        }
    }

    private fun fail(reason: Reason): Nothing = throw ProbeFailure(reason)
}

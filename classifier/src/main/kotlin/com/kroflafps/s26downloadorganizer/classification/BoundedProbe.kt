package com.kroflafps.s26downloadorganizer.classification

import java.io.IOException

internal class ProbeFailure(val reason: Reason) : IOException()

/** Counts actual returned bytes (including repeated ranges) and bounds requested allocation/work. */
internal class BoundedProbe(
    private val source: ProbeSource, val size: Long, private val checkpoint: () -> Unit,
    private val nanoTime: () -> Long,
) {
    private val start = nanoTime()
    private var calls = 0
    var headerBytes = 0; private set
    var containerBytes = 0; private set

    fun check() {
        checkpoint()
        if (nanoTime() - start > MAX_NANOS) throw ProbeFailure(Reason.LIMIT)
    }

    fun read(offset: Long, count: Int, header: Boolean = false): ByteArray {
        check()
        val room = if (header) HEADER_LIMIT - headerBytes else CONTAINER_LIMIT - containerBytes
        if (count < 0 || count > room) throw ProbeFailure(Reason.LIMIT)
        if (offset < 0 || offset > size || count.toLong() > size - offset) throw ProbeFailure(Reason.MALFORMED_OR_PARTIAL)
        val result = ByteArray(count)
        var filled = 0
        while (filled < count) {
            check()
            if (++calls > MAX_CALLS) throw ProbeFailure(Reason.LIMIT)
            val n = source.readAt(offset + filled, result, filled, count - filled)
            if (n <= 0 || n > count - filled) throw ProbeFailure(Reason.READ_ERROR)
            filled += n
            if (header) headerBytes += n else containerBytes += n
            check()
        }
        return result
    }

    fun usage(outcome: BudgetOutcome) = ReadUsage(headerBytes, containerBytes, outcome)

    companion object {
        const val HEADER_LIMIT = 4096
        const val CONTAINER_LIMIT = 128 * 1024
        const val MAX_CALLS = 2048
        const val MAX_NANOS = 250_000_000L // Cooperative only; cannot interrupt a blocked kernel read.
    }
}

internal fun ByteArray.matchesAt(offset: Int, vararg bytes: Int): Boolean =
    offset >= 0 && offset <= size - bytes.size && bytes.indices.all { this[offset + it].toInt() and 255 == bytes[it] }
internal fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
    offset >= 0 && offset <= size - value.length && value.indices.all { this[offset + it].toInt() and 255 == value[it].code }
internal fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
internal fun ByteArray.u32(offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((this[offset + i].toLong() and 255) shl (8 * i)) }

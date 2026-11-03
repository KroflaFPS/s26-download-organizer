package com.kroflafps.s26downloadorganizer.operations

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

object OperationPolicy {
    // Deliberate compile-time safety gate. Signing alone must NEVER enable mutations.
    const val MUTATIONS_ENABLED = false
    const val GATE_REASON = "MOVE and Undo are disabled. The separate synthetic-fixture diagnostic can only copy approved test bytes; it never removes originals."

    fun eligibleName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) return false
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".part", ".partial", ".crdownload", ".download", ".tmp", ".temp", ".opdownload", ".filepart")
            .none(lower::endsWith) && !lower.startsWith(".pending-")
    }

    fun sourcePath(raw: String, root: Path): Path {
        val path = try { Paths.get(raw) } catch (_: InvalidPathException) {
            throw OperationFailure(OperationError.INVALID_REQUEST)
        }
        if (!path.isAbsolute || path != path.normalize() || path.parent != root.toAbsolutePath().normalize() ||
            !eligibleName(path.fileName?.toString().orEmpty())) {
            throw OperationFailure(OperationError.INVALID_REQUEST)
        }
        return path
    }

    /** UTF-8 NAME_MAX budget; preserve the full suffix, trim stem at code point boundaries. */
    fun candidate(original: String, number: Int, maximumBytes: Int = 255): String {
        if (!eligibleName(original) || number !in 0..9999) throw OperationFailure(OperationError.INVALID_REQUEST)
        val dot = original.lastIndexOf('.').takeIf { it > 0 && it < original.lastIndex }
        val extension = dot?.let { original.substring(it) }.orEmpty()
        var stem = dot?.let { original.substring(0, it) } ?: original
        val suffix = if (number == 0) "" else " ($number)"
        while ((stem + suffix + extension).toByteArray(Charsets.UTF_8).size > maximumBytes && stem.isNotEmpty()) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
        }
        if (stem.isEmpty()) throw OperationFailure(OperationError.INVALID_REQUEST)
        return stem + suffix + extension
    }
}

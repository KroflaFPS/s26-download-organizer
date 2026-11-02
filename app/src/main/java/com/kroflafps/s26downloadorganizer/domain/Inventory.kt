package com.kroflafps.s26downloadorganizer.domain

enum class EntryType { FILE, DIRECTORY, SYMBOLIC_LINK, OTHER, UNAVAILABLE }

data class InventoryEntry(
    val id: String,
    val name: String,
    val type: EntryType,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
) {
    val extension: String?
        get() = if (type == EntryType.FILE) fileExtension(name) else null
}

/** A leading dot alone is a hidden name, not an extension. Preserve actual spelling. */
fun fileExtension(name: String): String? {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.lastIndex) {
        name.substring(dot + 1).takeIf { it.isNotBlank() }
    } else {
        null
    }
}

val inventoryOrder: Comparator<InventoryEntry> =
    compareByDescending<InventoryEntry> { it.modifiedAtMillis ?: Long.MIN_VALUE }
        .thenBy { it.name }
        .thenBy { it.id }

data class InventorySnapshot(
    val entries: List<InventoryEntry>,
    val unavailableCount: Int = 0,
)

enum class InventoryError { ACCESS_DENIED, STORAGE_UNAVAILABLE, DOWNLOADS_MISSING, CANNOT_READ }

sealed interface InventoryResult {
    data class Success(val snapshot: InventorySnapshot) : InventoryResult
    data class Failure(val error: InventoryError) : InventoryResult
}

enum class StorageAccess { GRANTED, REQUIRED, UNSUPPORTED }

/** No mutation or content-reading operations are exposed to presentation code. */
interface DownloadsRepository {
    fun access(): StorageAccess
    suspend fun scan(): InventoryResult
}

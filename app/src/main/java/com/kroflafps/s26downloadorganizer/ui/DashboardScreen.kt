package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.R
import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.FileCategory
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.domain.InventoryError
import com.kroflafps.s26downloadorganizer.operations.OperationHistoryEntry
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: InventoryScreenState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    operations: OperationsScreenState,
    onToggleAutoSort: (Boolean) -> Unit,
    onSortNow: () -> Unit,
    onReviewMove: (InventoryEntry) -> Unit,
    onReviewUndo: (OperationHistoryEntry) -> Unit,
    onRecovery: () -> Unit,
    onDismissOperation: () -> Unit,
    onConfirmOperation: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.semantics { heading() },
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.build_identifier),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    )
                                }
                                Text(
                                    text = "On-device · Organized/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(FileCategory.entries) { category ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = category.folderName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "operations-header") {
                OperationsHeader(
                    state = operations,
                    canSort = state is InventoryScreenState.Loaded,
                    onToggleAutoSort = onToggleAutoSort,
                    onSortNow = onSortNow,
                    onRecovery = onRecovery,
                )
            }

            item(key = "status") {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (state) {
                        InventoryScreenState.Checking, InventoryScreenState.Loading -> {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                    Text(stringResource(R.string.loading_inventory), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        is InventoryScreenState.PermissionRequired -> {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.permission_title),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(stringResource(R.string.permission_explanation), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(R.string.permission_navigation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (state.settingsUnavailable) {
                                        Text(
                                            stringResource(R.string.settings_unavailable),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Button(
                                        onClick = onOpenSettings,
                                        shape = RoundedCornerShape(14.dp),
                                    ) { Text(stringResource(R.string.open_settings)) }
                                }
                            }
                        }
                        InventoryScreenState.Unsupported -> {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.unsupported_android),
                                    modifier = Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        is InventoryScreenState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        stringResource(errorMessage(state.reason)),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Button(onClick = onRefresh, shape = RoundedCornerShape(14.dp)) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                        is InventoryScreenState.Loaded -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        pluralStringResource(R.plurals.inventory_count, state.inventory.entries.size, state.inventory.entries.size),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    if (state.inventory.unavailableCount > 0) {
                                        Text(
                                            pluralStringResource(R.plurals.partial_inventory, state.inventory.unavailableCount, state.inventory.unavailableCount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = onRefresh,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(stringResource(R.string.refresh))
                                }
                            }
                            if (state.inventory.entries.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(if (state.inventory.unavailableCount == 0) R.string.empty_inventory else R.string.no_available_entries),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state is InventoryScreenState.Loaded) {
                items(state.inventory.entries, key = { it.id }) { entry ->
                    InventoryRow(entry, operations.busy, onReviewMove)
                }
            }

            if (operations.history.isNotEmpty() || !operations.loading) {
                item(key = "history-section-header") {
                    Column(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Activity history",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            if (operations.history.isEmpty() && !operations.journalError) {
                                "No files have been organized yet."
                            } else {
                                "Up to 100 recent records, unresolved first."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(operations.history, key = { "operation-${it.record.id}" }) { item ->
                OperationHistoryRow(item, operations.busy, onReviewUndo)
            }
        }
    }
    operations.confirmation?.let { OperationConfirmationDialog(it, operations.busy, onDismissOperation, onConfirmOperation) }
}

@Composable
private fun InventoryRow(entry: InventoryEntry, busy: Boolean, onReview: (InventoryEntry) -> Unit) {
    val typeLabel = stringResource(when (entry.type) {
        EntryType.FILE -> R.string.type_file
        EntryType.DIRECTORY -> R.string.type_directory
        EntryType.SYMBOLIC_LINK -> R.string.type_link
        EntryType.OTHER -> R.string.type_other
        EntryType.UNAVAILABLE -> R.string.type_unavailable
    })
    val unknown = stringResource(R.string.metadata_unavailable)
    val sizeText = remember(entry.sizeBytes) {
        entry.sizeBytes?.let(::formatBytes)
    }
    val modified = remember(entry.modifiedAtMillis) {
        entry.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    }
    val subtitle = remember(entry.type, entry.extension, sizeText, modified, typeLabel, unknown) {
        buildList {
            if (entry.type == EntryType.FILE) {
                entry.extension?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
                add(sizeText ?: unknown)
            } else {
                add(typeLabel)
            }
            add(modified ?: unknown)
        }.joinToString(" · ")
    }
    val isFolder = entry.type == EntryType.DIRECTORY
    val canMove = entry.type == EntryType.FILE &&
        entry.sizeBytes != null &&
        entry.modifiedAtMillis != null &&
        OperationPolicy.eligibleName(entry.name)

    Surface(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFolder) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (isFolder) R.drawable.ic_folder else R.drawable.ic_file),
                    contentDescription = null,
                    tint = if (isFolder) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canMove) {
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = { onReview(entry) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Move")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun errorMessage(error: InventoryError): Int = when (error) {
    InventoryError.ACCESS_DENIED -> R.string.error_access
    InventoryError.STORAGE_UNAVAILABLE -> R.string.error_storage
    InventoryError.DOWNLOADS_MISSING -> R.string.error_missing
    InventoryError.CANNOT_READ -> R.string.error_read
}

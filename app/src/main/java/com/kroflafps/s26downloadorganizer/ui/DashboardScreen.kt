package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import com.kroflafps.s26downloadorganizer.operations.OperationHistoryEntry
import com.kroflafps.s26downloadorganizer.BuildConfig
import com.kroflafps.s26downloadorganizer.R
import com.kroflafps.s26downloadorganizer.domain.EntryType
import com.kroflafps.s26downloadorganizer.domain.InventoryEntry
import com.kroflafps.s26downloadorganizer.domain.InventoryError
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun DashboardScreen(
    state: InventoryScreenState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    operations: OperationsScreenState,
    onReviewMove: (InventoryEntry) -> Unit,
    onReviewUndo: (OperationHistoryEntry) -> Unit,
    onRecovery: () -> Unit,
    onDismissOperation: () -> Unit,
    onConfirmOperation: (Boolean, Boolean) -> Unit,
    diagnostic: DiagnosticState,
    onInspectDiagnostic: () -> Unit,
    onReviewDiagnostic: (com.kroflafps.s26downloadorganizer.operations.FileEvidence) -> Unit,
    onRecoverDiagnostic: () -> Unit,
    onDismissDiagnostic: () -> Unit,
    onConfirmDiagnostic: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One bounded lazy list handles both the header and rows, including large fonts.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.build_identifier),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.foundation_status),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.foundation_description),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = stringResource(R.string.development_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "status") {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state) {
                        InventoryScreenState.Checking, InventoryScreenState.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(stringResource(R.string.loading_inventory))
                        }
                        is InventoryScreenState.PermissionRequired -> {
                            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.permission_explanation))
                            Text(stringResource(R.string.permission_navigation))
                            if (state.settingsUnavailable) {
                                Text(stringResource(R.string.settings_unavailable), color = MaterialTheme.colorScheme.error)
                            }
                            Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                        }
                        InventoryScreenState.Unsupported -> Text(stringResource(R.string.unsupported_android))
                        is InventoryScreenState.Error -> {
                            Text(stringResource(errorMessage(state.reason)), color = MaterialTheme.colorScheme.error)
                            Button(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
                        }
                        is InventoryScreenState.Loaded -> {
                            Text(
                                pluralStringResource(R.plurals.inventory_count, state.inventory.entries.size, state.inventory.entries.size),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (state.inventory.unavailableCount > 0) {
                                Text(pluralStringResource(R.plurals.partial_inventory, state.inventory.unavailableCount, state.inventory.unavailableCount))
                            }
                            if (state.inventory.entries.isEmpty()) {
                                Text(stringResource(if (state.inventory.unavailableCount == 0) R.string.empty_inventory else R.string.no_available_entries))
                            }
                            Button(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                        }
                    }
                }
            }
            if (BuildConfig.DIAGNOSTIC_COPY_ENABLED) {
                item(key = "diagnostic") { DiagnosticCopyPanel(diagnostic, onInspectDiagnostic, onReviewDiagnostic, onRecoverDiagnostic) }
                items(diagnostic.history, key = { "diagnostic-${it.id}" }) { DiagnosticHistoryRow(it) }
            }
            item(key = "operations-header") { OperationsHeader(operations, onRecovery) }
            items(operations.history, key = { "operation-${it.record.id}" }) { item ->
                OperationHistoryRow(item, operations.busy, onReviewUndo)
            }
            if (state is InventoryScreenState.Loaded) {
                items(state.inventory.entries, key = { it.id }) { entry -> InventoryRow(entry, operations.busy, onReviewMove) }
            }
        }
    }
    if (BuildConfig.DIAGNOSTIC_COPY_ENABLED) DiagnosticCopyDialog(diagnostic, onDismissDiagnostic, onConfirmDiagnostic)
    operations.confirmation?.let { OperationConfirmationDialog(it, operations.busy, onDismissOperation, onConfirmOperation) }
}

@Composable
private fun InventoryRow(entry: InventoryEntry, busy: Boolean, onReview: (InventoryEntry) -> Unit) {
    val type = stringResource(when (entry.type) {
        EntryType.FILE -> R.string.type_file
        EntryType.DIRECTORY -> R.string.type_directory
        EntryType.SYMBOLIC_LINK -> R.string.type_link
        EntryType.OTHER -> R.string.type_other
        EntryType.UNAVAILABLE -> R.string.type_unavailable
    })
    val unknown = stringResource(R.string.metadata_unavailable)
    val bytes = remember(entry.sizeBytes) {
        entry.sizeBytes?.let { NumberFormat.getIntegerInstance().format(it) }
    }
    val modified = remember(entry.modifiedAtMillis) {
        entry.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    }
    Card(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(if (entry.type == EntryType.DIRECTORY) R.drawable.ic_folder else R.drawable.ic_file),
                contentDescription = null, // The adjacent type text provides the accessible label.
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(type, style = MaterialTheme.typography.bodyMedium)
                entry.extension?.let { Text(stringResource(R.string.extension_value, it)) }
                if (entry.type == EntryType.FILE) {
                    Text(if (bytes != null) stringResource(R.string.size_value, bytes) else stringResource(R.string.size_unavailable))
                }
                Text(stringResource(R.string.modified_value, modified ?: unknown))
                if (entry.type == EntryType.FILE && entry.sizeBytes != null && entry.modifiedAtMillis != null && OperationPolicy.eligibleName(entry.name)) {
                    Button(onClick = { onReview(entry) }, enabled = !busy) { Text("Review move") }
                }
            }
        }
    }
}

private fun errorMessage(error: InventoryError): Int = when (error) {
    InventoryError.ACCESS_DENIED -> R.string.error_access
    InventoryError.STORAGE_UNAVAILABLE -> R.string.error_storage
    InventoryError.DOWNLOADS_MISSING -> R.string.error_missing
    InventoryError.CANNOT_READ -> R.string.error_read
}

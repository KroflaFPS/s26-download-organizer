package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.operations.*
import java.text.DateFormat
import java.util.Date

@Composable
fun OperationsHeader(
    state: OperationsScreenState,
    canSort: Boolean,
    onToggleAutoSort: (Boolean) -> Unit,
    onSortNow: () -> Unit,
    onRecovery: () -> Unit,
) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Automatic background sorting", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.autoSortEnabled) "Watches Downloads and sorts finished files into Organized/."
                            else "Paused. Manual moves and Undo remain available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.autoSortEnabled,
                        onCheckedChange = onToggleAutoSort,
                        enabled = !state.busy && !state.journalError,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onSortNow,
                        enabled = canSort && !state.busy && !state.journalError && OperationPolicy.MUTATIONS_ENABLED,
                    ) {
                        Text("Sort now")
                    }
                    OutlinedButton(
                        onClick = onRecovery,
                        enabled = !state.busy && !state.journalError,
                    ) {
                        Text("Check recovery")
                    }
                }
            }
        }
        if (state.busy || state.loading) CircularProgressIndicator(Modifier.size(28.dp))
        state.message?.let { Text(it) }
        if (state.unresolved > 0) {
            Text(
                "${state.unresolved} operation(s) need attention. Review recovery before retrying.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("Operation history", style = MaterialTheme.typography.titleLarge)
        if (!state.loading && !state.journalError && state.history.isEmpty()) {
            Text("No files have been organized yet.")
        }
        if (state.history.isNotEmpty()) {
            Text(
                "Up to 100 recent records, with unresolved operations first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun OperationHistoryRow(item: OperationHistoryEntry, busy: Boolean, onUndo: (OperationHistoryEntry) -> Unit) {
    val record = item.record
    val started = remember(record.startedAt) { DateFormat.getDateTimeInstance().format(Date(record.startedAt)) }
    Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.originalName, style = MaterialTheme.typography.titleMedium)
            Text(operationLabel(record), color = if (record.recoveryNeeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("${if (record.type == OperationType.MOVE) "Move" else "Undo"} · $started")
            Text("From: ${record.sourcePath}", style = MaterialTheme.typography.bodySmall)
            Text("To: ${record.destinationPath ?: record.destinationDirectory}", style = MaterialTheme.typography.bodySmall)
            if (record.error != null) Text("${errorLabel(record.error)} Files need manual review.", color = MaterialTheme.colorScheme.error)
            if (item.canUndo) {
                Button(onClick = { onUndo(item) }, enabled = OperationPolicy.MUTATIONS_ENABLED && !busy) { Text("Undo") }
            }
        }
    }
}

@Composable
fun OperationConfirmationDialog(request: OperationConfirmation, busy: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit) {
    var finished by remember(request.id) { mutableStateOf(true) }
    val undo = request.originalId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (undo) "Undo move" else "Move to ${request.category?.folderName ?: "Organized"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source: ${request.source}")
                Text("Target: ${request.target}")
                Text("Existing files are never overwritten; duplicate names get a numbered suffix.")
                if (!undo) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = finished, onCheckedChange = { finished = it })
                        Text("This download has finished.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(finished, true) }, enabled = OperationPolicy.MUTATIONS_ENABLED && !busy && (undo || finished)) {
                Text(if (undo) "Restore to Downloads" else "Move file")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun operationLabel(record: OperationRecord): String = when (record.phase) {
    Phase.COMPLETED -> if (record.type == OperationType.UNDO) "Restoration completed" else "Moved"
    Phase.COPY_ONLY -> "Copy only — original not removed"
    Phase.FAILED_SAFE -> "Stopped safely — original preserved"
    Phase.RECOVERY_REQUIRED -> "Recovery required — preserve all files"
    else -> "Incomplete operation — recovery check required"
}

private fun errorLabel(error: OperationError): String = when (error) {
    OperationError.SOURCE_CHANGED -> "The source changed."
    OperationError.CONFLICT -> "A file no longer matches the recorded evidence."
    OperationError.NO_SAFE_REMOVAL -> "Safe removal could not be established."
    OperationError.DURABILITY_UNCONFIRMED -> "Storage durability could not be confirmed."
    OperationError.UNTRUSTED_EVIDENCE -> "Filesystem identity could not be established safely."
    OperationError.MISSING_BOTH -> "Both recorded paths are missing; this is unresolved."
    OperationError.PARTIAL_OUTPUT -> "An incomplete or uncommitted output may remain."
    OperationError.REMOVAL_UNCONFIRMED -> "Source disappearance does not prove the app safely removed it."
    OperationError.RECOVERY_BUDGET -> "Large-file verification is deferred by the recovery limit; manual review is required."
    OperationError.PERMISSION -> "Storage access is unavailable."
    else -> "The operation could not be finalized safely."
}

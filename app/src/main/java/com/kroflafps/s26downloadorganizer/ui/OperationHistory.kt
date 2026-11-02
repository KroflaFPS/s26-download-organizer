package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.operations.*
import java.text.DateFormat
import java.util.Date

@Composable
fun OperationsHeader(state: OperationsScreenState, onRecovery: () -> Unit) {
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Manual operations · engineering build", style = MaterialTheme.typography.titleLarge)
        Text(OperationPolicy.GATE_REASON, color = MaterialTheme.colorScheme.error)
        Text("Automatic organizing is not active. Do not uninstall or clear app data: this erases the recovery journal.")
        if (state.busy || state.loading) CircularProgressIndicator(Modifier.size(28.dp))
        state.message?.let { Text(it) }
        if (state.unresolved > 0) Text("${state.unresolved} operation(s) need attention. Do not repeat unresolved operations.", color = MaterialTheme.colorScheme.error)
        Button(onClick = onRecovery, enabled = !state.busy && !state.journalError) { Text("Check recovery") }
        Text("Operation history", style = MaterialTheme.typography.titleLarge)
        if (!state.loading && !state.journalError && state.history.isEmpty()) Text("No operations recorded. This build cannot change user files.")
        if (state.history.isNotEmpty()) Text("Up to 100 records, with unresolved operations first. Earlier records remain in the journal.")
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
            Text("From: ${record.sourcePath}")
            Text("To: ${record.destinationPath ?: record.destinationDirectory}")
            Text("Tracked staging path: ${record.stagingPath}. Do not delete tracked or ambiguous files automatically.", style = MaterialTheme.typography.bodySmall)
            if (record.error != null) Text("${errorLabel(record.error)} Files need manual review.")
            if (item.canUndo) {
                Button(onClick = { onUndo(item) }, enabled = OperationPolicy.MUTATIONS_ENABLED && !busy) { Text("Undo") }
                if (!OperationPolicy.MUTATIONS_ENABLED) Text("Undo is disabled by this build’s safety gate.")
            }
        }
    }
}

@Composable
fun OperationConfirmationDialog(request: OperationConfirmation, busy: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit) {
    var finished by remember(request.id) { mutableStateOf(false) }
    val undo = request.originalId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (undo) "Review Undo" else "Review manual move") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source: ${request.source}")
                Text("Target: ${request.target}")
                Text("Existing files are never replaced. If the name is occupied, restoration or movement requires a unique numbered name.")
                if (!undo) {
                    Text("Use only finished downloads. Build 3 cannot independently guarantee that downloading has stopped.")
                    Row { Checkbox(checked = finished, onCheckedChange = { finished = it }); Text("I confirm this file is no longer downloading.") }
                }
                Text(OperationPolicy.GATE_REASON, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(finished, true) }, enabled = OperationPolicy.MUTATIONS_ENABLED && !busy && (undo || finished)) {
                Text(if (undo) "Restore with unique name if needed" else "Confirm move")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun operationLabel(record: OperationRecord): String = when (record.phase) {
    Phase.COMPLETED -> if (record.type == OperationType.UNDO) "Restoration completed" else "Move completed"
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
    OperationError.PERMISSION -> "Storage access is unavailable."
    else -> "The operation could not be finalized safely."
}

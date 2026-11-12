package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.operations.OperationError
import com.kroflafps.s26downloadorganizer.operations.OperationHistoryEntry
import com.kroflafps.s26downloadorganizer.operations.OperationPolicy
import com.kroflafps.s26downloadorganizer.operations.OperationRecord
import com.kroflafps.s26downloadorganizer.operations.OperationType
import com.kroflafps.s26downloadorganizer.operations.Phase
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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (state.autoSortEnabled) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
            ),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.autoSortEnabled) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    ),
                            )
                            Text(
                                "Automatic sorting",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        Text(
                            if (state.autoSortEnabled) {
                                "Active · watches Downloads and reconciles every 15 min."
                            } else {
                                "Paused · manual Move, Sort now, and Undo remain available."
                            },
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onSortNow,
                        enabled = canSort && !state.busy && !state.journalError && OperationPolicy.MUTATIONS_ENABLED,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        Text("Sort now", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onRecovery,
                        enabled = !state.busy && !state.journalError,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text("Check recovery")
                    }
                }
            }
        }

        if (state.busy || state.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Text("Updating…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        if (state.unresolved > 0) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${state.unresolved} operation(s) need attention. Review recovery before retrying.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun OperationHistoryRow(item: OperationHistoryEntry, busy: Boolean, onUndo: (OperationHistoryEntry) -> Unit) {
    val record = item.record
    val started = remember(record.startedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startedAt))
    }
    val badgeBg = when {
        record.recoveryNeeded -> MaterialTheme.colorScheme.errorContainer
        record.phase == Phase.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeFg = when {
        record.recoveryNeeded -> MaterialTheme.colorScheme.onErrorContainer
        record.phase == Phase.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.originalName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = shortPhaseBadge(record),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = "${if (record.type == OperationType.MOVE) "Move" else "Undo"} · $started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "From: ${record.sourcePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "To: ${record.destinationPath ?: record.destinationDirectory}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (record.error != null) {
                Text(
                    text = "${errorLabel(record.error)} Files need manual review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.canUndo) {
                FilledTonalButton(
                    onClick = { onUndo(item) },
                    enabled = OperationPolicy.MUTATIONS_ENABLED && !busy,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("Undo")
                }
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
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (undo) "Undo move" else "Move to ${request.category?.folderName ?: "Organized"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Source: ${request.source}", style = MaterialTheme.typography.bodySmall)
                Text("Target: ${request.target}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Existing files are never overwritten; duplicate names get a numbered suffix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!undo) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = finished, onCheckedChange = { finished = it })
                        Text("This download has finished.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(finished, true) },
                enabled = OperationPolicy.MUTATIONS_ENABLED && !busy && (undo || finished),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (undo) "Restore to Downloads" else "Move file")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun shortPhaseBadge(record: OperationRecord): String = when (record.phase) {
    Phase.COMPLETED -> if (record.type == OperationType.UNDO) "Restored" else "Moved"
    Phase.COPY_ONLY -> "Copy only"
    Phase.FAILED_SAFE -> "Stopped safely"
    Phase.RECOVERY_REQUIRED -> "Needs review"
    else -> "In progress"
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

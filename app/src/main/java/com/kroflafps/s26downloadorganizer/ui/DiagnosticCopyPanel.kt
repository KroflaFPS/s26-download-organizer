package com.kroflafps.s26downloadorganizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kroflafps.s26downloadorganizer.diagnostic.*
import com.kroflafps.s26downloadorganizer.operations.FileEvidence

@Composable
fun DiagnosticCopyPanel(state: DiagnosticState, inspect: () -> Unit, review: (FileEvidence) -> Unit, recover: () -> Unit) {
    Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Disposable-file copy test", style = MaterialTheme.typography.titleLarge)
            Text("Maximum 1 MiB; only the two documented synthetic fixtures. MOVE and Undo remain disabled. Automatic organizing is inactive.")
            Text("Input: ${DiagnosticPolicy.INPUT}/\nOutput: ${DiagnosticPolicy.OUTPUT}/")
            Text("Create these exact folders in My Files first. This app does not create folders. Partial final files and stages are retained if interrupted; no cleanup or deletion is provided.")
            Text("Audit this artifact and compare its certificate report before installing an upgrade. Do not uninstall or clear app data for a signature mismatch.")
            if (state.busy) CircularProgressIndicator()
            state.message?.let { Text(it) }
            Button(onClick = inspect, enabled = !state.busy && !state.journalError) { Text("Inspect test fixtures") }
            state.entries.forEach { entry ->
                Text("${java.nio.file.Paths.get(entry.path).fileName} · ${entry.size} bytes\n${entry.path}")
                Button(onClick = { review(entry) }, enabled = !state.busy && !state.journalError) { Text("Review test copy") }
            }
            Button(onClick = recover, enabled = !state.busy && !state.journalError) { Text("Check diagnostic recovery") }
            Text("Diagnostic history (${state.history.size} most recent / unresolved first)", style = MaterialTheme.typography.titleMedium)
            if (state.history.isEmpty()) Text("No diagnostic copies recorded.")
        }
    }
}
@Composable
fun DiagnosticHistoryRow(record: CopyRecord) {
    Card(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.name, style = MaterialTheme.typography.titleMedium)
            Text(if (record.phase == CopyPhase.COPY_ONLY) DiagnosticPolicy.SUCCESS else "Recovery required · ${record.phase}")
            Text("Source: ${record.sourcePath}\nStage: ${record.stagePath}\nFinal: ${record.finalPath ?: "not allocated"}")
            Text("Stage verified: ${record.stageVerified}; final verified: ${record.finalVerified}; file sync: ${record.fileSynced}; directory sync: ${record.directorySynced}")
            record.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("ID: ${record.id}\nUndo and file cleanup are unavailable.")
        }
    }
}
@Composable
fun DiagnosticCopyDialog(state: DiagnosticState, dismiss: () -> Unit, confirm: (Boolean) -> Unit) {
    val selection = state.selected ?: return
    val id = state.requestId ?: return
    var acknowledged by remember(id) { mutableStateOf(false) }
    val name = java.nio.file.Paths.get(selection.path).fileName.toString()
    val primary = java.nio.file.Paths.get(selection.path).parent.parent.parent
    AlertDialog(onDismissRequest = dismiss, title = { Text("Copy disposable fixture only?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Source: ${selection.path}\nDestination: ${primary.resolve(DiagnosticPolicy.OUTPUT).resolve(name)}")
            Text("Copy only — original kept in Downloads. Collisions receive a numbered name; nothing is replaced. Maximum 1 MiB. An incomplete final file can be visible during copying and remains if interrupted. A staging copy also remains.")
            Row { Checkbox(acknowledged, { acknowledged = it }); Text("I deliberately created this synthetic test file and reviewed this build's boundaries.", Modifier.weight(1f)) }
        }
    }, confirmButton = { Button(onClick = { confirm(acknowledged) }, enabled = acknowledged && !state.busy && !state.journalError) { Text("Copy test fixture") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

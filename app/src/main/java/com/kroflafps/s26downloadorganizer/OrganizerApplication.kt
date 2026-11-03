package com.kroflafps.s26downloadorganizer

import android.app.Application
import com.kroflafps.s26downloadorganizer.history.JournalDatabase
import com.kroflafps.s26downloadorganizer.history.RoomOperationJournal
import com.kroflafps.s26downloadorganizer.operations.OperationCoordinator
import com.kroflafps.s26downloadorganizer.operations.OperationRecovery
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.storage.AndroidOperationFiles
import com.kroflafps.s26downloadorganizer.storage.StorageAccessAdapter

class OrganizerApplication : Application() {
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    private val database by lazy { JournalDatabase.open(this) }
    val diagnosticJournal by lazy { database.diagnostic() }
    val diagnostic by lazy {
        com.kroflafps.s26downloadorganizer.diagnostic.DiagnosticCopyCoordinator(
            com.kroflafps.s26downloadorganizer.storage.AndroidDiagnosticCopies::open,
            diagnosticJournal,
            com.kroflafps.s26downloadorganizer.storage.AndroidDiagnosticCopies::allowed,
            com.kroflafps.s26downloadorganizer.storage.AndroidDiagnosticCopies::permission,
            mutex = operationMutex,
        )
    }
    val journal by lazy { RoomOperationJournal(database.journal()) }
    val operationFiles by lazy { AndroidOperationFiles() }
    val coordinator by lazy { OperationCoordinator(operationFiles, journal, operationMutex = operationMutex) }
    val recovery by lazy {
        OperationRecovery(operationFiles, journal, { StorageAccessAdapter().check() == StorageAccess.GRANTED }, operationMutex = operationMutex)
    }
}

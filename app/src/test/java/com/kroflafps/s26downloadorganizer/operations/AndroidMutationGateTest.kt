package com.kroflafps.s26downloadorganizer.operations

import com.kroflafps.s26downloadorganizer.storage.AndroidOperationFiles
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidMutationGateTest {
    @Test fun invalidOrUntrustedPathsAreRejectedBeforeJournalOrFileMutation() = runBlocking {
        val files = AndroidOperationFiles()
        val journal = MemoryJournal()
        assertTrue(files.canConditionallyRemove)
        val result = OperationCoordinator(files, journal, { true }).move(UUID.randomUUID().toString(), "/not-a-real-source/file", true)
        assertTrue(result is OperationResult.Rejected)
        assertTrue(journal.records.isEmpty())
    }
}

package com.kroflafps.s26downloadorganizer.operations

import com.kroflafps.s26downloadorganizer.storage.AndroidOperationFiles
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidMutationGateTest {
    @Test fun grantingCoordinatorAuthorizationCannotEnableTheShippingAndroidAdapter() = runBlocking {
        val files = AndroidOperationFiles()
        val journal = MemoryJournal()
        assertFalse(files.canConditionallyRemove)
        val result = OperationCoordinator(files, journal, { true }).move(UUID.randomUUID().toString(), "/not-a-real-source/file", true)
        assertEquals(OperationResult.Rejected(OperationError.UNTRUSTED_EVIDENCE), result)
        assertTrue(journal.records.isEmpty())
    }
}

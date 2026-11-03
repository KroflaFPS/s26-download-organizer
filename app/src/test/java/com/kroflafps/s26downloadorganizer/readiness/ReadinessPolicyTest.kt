package com.kroflafps.s26downloadorganizer.readiness

import org.junit.Assert.*
import org.junit.Test

class ReadinessPolicyTest {
    private val file = MetadataEvidence("salted-key", "dev:ino", 10, 1000, false, null)
    private fun sample(time: Long, evidence: MetadataEvidence = file, previous: ReadinessObservation? = null, boot: String = "boot-1", elapsed: Long = time) = ReadinessPolicy.observe(time, boot, evidence, previous, elapsed)
    @Test fun metadataQuiescenceIsOnlyPossiblyReadyAndNeverMoveAuthorization() {
        var previous: ReadinessObservation? = null
        for (t in listOf(1000L, 16_000L, 31_000L)) previous = sample(t, previous = previous)
        assertEquals(Readiness.POSSIBLY_READY, previous!!.state); assertFalse(previous.authorizesMutation)
    }
    @Test fun providerPublishedAndQuietIsEvidenceButNeverProofOfWriterClosure() {
        var previous: ReadinessObservation? = null
        for (t in listOf(1000L, 16_000L, 31_000L)) previous = sample(t, file.copy(pending = false), previous)
        assertEquals(Readiness.READY_WITH_EVIDENCE, previous!!.state); assertFalse(previous.authorizesMutation)
    }
    @Test fun pendingAndTemporaryNamesAreHardNegativesAndClearResetsObservationWindow() {
        var previous = sample(1000, file.copy(pending = true))
        previous = sample(60_000, file.copy(pending = true), previous)
        assertEquals(Readiness.NOT_READY, previous.state)
        val published = sample(61_000, file.copy(pending = false), previous)
        assertEquals(1, published.samples); assertNotEquals(Readiness.READY_WITH_EVIDENCE, published.state)
        for (name in listOf("DATA.CRDOWNLOAD", ".pending-one", "x.part", "x.tmp")) assertTrue(ReadinessPolicy.temporary(name))
        assertEquals(Readiness.NOT_READY, sample(1000, file.copy(temporary = true)).state)
        assertEquals(Readiness.NOT_READY, sample(1000, file.copy(identity = null, pending = true)).state)
    }
    @Test fun changedIdentitySizeOrMtimeRestartsQuiescenceEvenIfPausedFileWasQuiet() {
        val first = sample(1000); val second = sample(16_000, previous = first); val quiet = sample(31_000, previous = second)
        for (changed in listOf(file.copy(identity = "replacement"), file.copy(size = 11), file.copy(modified = 2000))) {
            val next = sample(50_000, changed, quiet); assertEquals(Readiness.NOT_READY, next.state); assertEquals(1, next.samples)
        }
    }
    @Test fun zeroBytesAndStableHalfSecondAreNotProofOfCompletion() {
        val zero = file.copy(size = 0)
        val first = sample(1000, zero); val fast = sample(1500, zero, first)
        assertEquals(first, fast); assertEquals(Readiness.UNKNOWN, fast.state)
    }
    @Test fun unavailableMetadataAndFutureMtimeStayUnknown() {
        for (e in listOf(file.copy(identity = null), file.copy(size = null), file.copy(modified = null), file.copy(modified = 900_000))) assertEquals(Readiness.UNKNOWN, sample(1000, e).state)
    }
    @Test fun rebootAndClockRollbackResetHistoryAndWallJumpCannotFakeElapsedQuiescence() {
        val first = sample(1000); val second = sample(16_000, previous = first)
        assertEquals(1, sample(31_000, previous = second, boot = "new-boot").samples)
        assertEquals(Readiness.UNKNOWN, sample(500, previous = second).state)
        assertEquals(1, sample(1_000_000, previous = first, elapsed = 1100).samples)
    }
    @Test fun pausedOrCorruptContentWithoutProviderEvidenceRemainsOnlyMetadataEvidence() {
        // There is deliberately no footer/content parameter: format tails cannot establish writer closure.
        var previous: ReadinessObservation? = null
        for (time in listOf(1000L, 16_000L, 31_000L, 100_000L)) previous = sample(time, previous = previous)
        assertEquals(Readiness.POSSIBLY_READY, previous!!.state); assertFalse(previous.authorizesMutation)
        assertTrue(previous.nextCheck > previous.sampledAt)
    }
}

package com.kroflafps.s26downloadorganizer.storage

import android.os.Environment
import com.kroflafps.s26downloadorganizer.operations.*

/** Fail-closed shipping adapter. No shared-storage writer or path-delete primitive is exposed.
 * A future adapter needs independently audited no-clobber publication, anchored no-follow I/O,
 * conditional unlink semantics and durability evidence. Signing alone cannot supply those.
 */
@Suppress("DEPRECATION")
class AndroidOperationFiles : OperationFiles {
    override val roots get() = OperationRoots(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath(),
        Environment.getExternalStorageDirectory().toPath().resolve("Organized").resolve("Other"),
    )
    override val canConditionallyRemove = false
    private fun unavailable(): Nothing = throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
    override fun requireWritableAccess() = unavailable()
    override fun isOccupied(path: String): Boolean = unavailable()
    override fun prepare(path: String, type: OperationType): FileEvidence = unavailable()
    override fun ensureDestination(type: OperationType) = unavailable()
    override fun reserveStage(path: String): String = unavailable()
    override fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long = unavailable()
    override fun verify(path: String, identity: String): FileEvidence = unavailable()
    override fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String = unavailable()
    override fun syncDirectory(path: String): Boolean = false
    override fun removeConditionally(source: FileEvidence, destination: FileEvidence) = Removal.UNSUPPORTED
    // No weak path-only observation may silently certify an operation as completed.
    override fun observe(path: String, maximumBytes: Long) = Observation.Unverifiable
}

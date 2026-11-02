package com.kroflafps.s26downloadorganizer.storage

import android.os.Build
import android.os.Environment
import com.kroflafps.s26downloadorganizer.domain.StorageAccess
import com.kroflafps.s26downloadorganizer.operations.*
import kotlinx.coroutines.CancellationException
import java.nio.file.NoSuchFileException

/** Actual bounded read/verification support; all mutation capabilities remain independently blocked.
 * No Android no-clobber publisher or conditional unlink is certified by this implementation.
 */
@Suppress("DEPRECATION")
class AndroidOperationFiles private constructor(private val readerProvider: () -> RootedReadInspector) : OperationFiles {
    constructor() : this({
        if (Build.VERSION.SDK_INT < 30) throw OperationFailure(OperationError.PERMISSION)
        RootedReadInspector(Environment.getExternalStorageDirectory().toPath(), AndroidReadHandles()) {
            StorageAccessAdapter().check() == StorageAccess.GRANTED && Environment.getExternalStorageState() in
                listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
        }
    })
    internal constructor(reader: RootedReadInspector) : this({ reader })
    private val reader by lazy(readerProvider)
    override val roots get() = reader.roots
    override val canConditionallyRemove = false
    private fun unavailable(): Nothing = throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
    override fun requireWritableAccess() = unavailable()
    override fun isOccupied(path: String): Boolean = unavailable()
    override fun prepare(path: String, type: OperationType): FileEvidence {
        OperationPolicy.sourcePath(path, if (type == OperationType.MOVE) roots.downloads else roots.other)
        return evidence(path, 64L * 1024 * 1024)
    }
    override fun verify(path: String, identity: String): FileEvidence = evidence(path, 64L * 1024 * 1024).also {
        if (it.identity != identity) throw OperationFailure(OperationError.CONFLICT)
    }
    private fun evidence(path: String, maximumBytes: Long): FileEvidence = when (val observed = reader.inspect(path, maximumBytes)) {
        is Observation.Present -> observed.evidence
        Observation.Missing -> throw NoSuchFileException("Journaled file missing")
        Observation.Unverifiable -> throw OperationFailure(OperationError.UNTRUSTED_EVIDENCE)
    }
    override fun observe(path: String, maximumBytes: Long, checkpoint: () -> Unit): Observation = try {
        reader.inspect(path, minOf(maximumBytes, 64L * 1024 * 1024), checkpoint)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: OperationFailure) {
        if (failure.category == OperationError.PERMISSION) throw failure
        Observation.Unverifiable
    } catch (_: Exception) { Observation.Unverifiable }
    override fun ensureDestination(type: OperationType) = unavailable()
    override fun reserveStage(path: String): String = unavailable()
    override fun copyAndSync(source: FileEvidence, stage: String, stageIdentity: String, checkpoint: () -> Unit): Long = unavailable()
    override fun publishNoClobber(stage: String, stageIdentity: String, destination: String): String = unavailable()
    override fun syncDirectory(path: String): Boolean = false
    override fun removeConditionally(source: FileEvidence, destination: FileEvidence) = Removal.UNSUPPORTED
}

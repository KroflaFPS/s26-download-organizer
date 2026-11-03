package com.kroflafps.s26downloadorganizer.history

import androidx.room.*
import com.kroflafps.s26downloadorganizer.diagnostic.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "diagnostic_copies", indices = [Index(value = ["sourceClaim"], unique = true)])
data class CopyEntity(@PrimaryKey val operationId: String, @Embedded val record: CopyRecord, val sourceClaim: String?)
@Entity(tableName = "diagnostic_events", primaryKeys = ["operationId", "revision"])
data class CopyEvent(val operationId: String, val revision: Int, val phase: CopyPhase, val at: Long)

@Dao
abstract class DiagnosticDao : DiagnosticJournal {
    @Query("SELECT * FROM diagnostic_copies WHERE operationId=:id")
    protected abstract suspend fun entity(id: String): CopyEntity?
    @Query("SELECT * FROM diagnostic_copies ORDER BY (phase != 'COPY_ONLY') DESC, startedAt DESC, id DESC LIMIT 100")
    abstract fun history(): Flow<List<CopyEntity>>
    @Query("SELECT * FROM diagnostic_events WHERE operationId=:id ORDER BY revision")
    abstract suspend fun events(id: String): List<CopyEvent>
    @Query("SELECT * FROM diagnostic_events WHERE operationId=:id ORDER BY revision DESC LIMIT 1")
    protected abstract suspend fun lastEvent(id: String): CopyEvent?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(value: CopyEntity): Long
    @Update
    protected abstract suspend fun update(value: CopyEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun event(value: CopyEvent)

    override suspend fun get(id: String) = entity(id)?.record?.also { it.validate() }
    @Transaction
    override suspend fun create(record: CopyRecord): Boolean {
        record.validate()
        require(record.phase == CopyPhase.STAGE_ALLOCATING && record.revision == 0 && record.startedAt == record.updatedAt &&
            record.finalPath == null && record.stageIdentity == null && record.finalIdentity == null && !record.stageVerified &&
            !record.finalVerified && !record.fileSynced && !record.directorySynced && record.error == null)
        if (insert(CopyEntity(record.id, record, record.sourcePath)) == -1L) return false
        event(CopyEvent(record.id, 0, record.phase, record.updatedAt)); return true
    }
    @Transaction
    override suspend fun save(record: CopyRecord) {
        val previous = requireNotNull(entity(record.id)).record
        record.successor(previous)
        require(lastEvent(record.id) == CopyEvent(previous.id, previous.revision, previous.phase, previous.updatedAt))
        update(CopyEntity(record.id, record, if (record.phase == CopyPhase.COPY_ONLY) null else record.sourcePath))
        event(CopyEvent(record.id, record.revision, record.phase, record.updatedAt))
    }
    @Query("SELECT * FROM recovery_cursor WHERE slot=1")
    protected abstract suspend fun cursor(): RecoveryCursor?
    @Upsert protected abstract suspend fun putCursor(cursor: RecoveryCursor)
    @Query("SELECT MAX(id) FROM diagnostic_copies WHERE phase != 'COPY_ONLY'")
    protected abstract suspend fun ceiling(): String?
    @Query("SELECT * FROM diagnostic_copies WHERE phase != 'COPY_ONLY' AND (:after IS NULL OR id > :after) AND id <= :ceiling ORDER BY id LIMIT 20")
    protected abstract suspend fun rows(after: String?, ceiling: String): List<CopyEntity>
    @Transaction override suspend fun page(): List<CopyRecord> {
        val position = cursor()
        var upper = position?.ceilingId ?: ceiling() ?: return emptyList()
        var result = rows(position?.lastId, upper)
        if (result.isEmpty()) {
            upper = ceiling() ?: return emptyList()
            result = rows(null, upper)
            putCursor(RecoveryCursor(1, null, upper))
        } else if (position?.ceilingId == null) putCursor(RecoveryCursor(1, position?.lastId, upper))
        return result.map { it.record }
    }
    @Transaction override suspend fun advance(id: String) {
        val position = requireNotNull(cursor()); require(id <= requireNotNull(position.ceilingId))
        putCursor(position.copy(lastId = id))
    }
}

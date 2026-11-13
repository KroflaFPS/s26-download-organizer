package com.kroflafps.s26downloadorganizer.history

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kroflafps.s26downloadorganizer.operations.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "operations", primaryKeys = ["id"], indices = [Index(value = ["sourceToken"], unique = true), Index(value = ["relatedId"], unique = true)])
data class JournalEntity(@Embedded val record: OperationRecord)

@Entity(tableName = "operation_events", primaryKeys = ["operationId", "revision"])
data class JournalEvent(val operationId: String, val revision: Int, val phase: Phase, val at: Long, val error: OperationError?)

data class JournalHistoryItem(@Embedded val record: OperationRecord, val undoRequested: Boolean)

@Entity(tableName = "recovery_cursor")
data class RecoveryCursor(@PrimaryKey val slot: Int = 0, val lastId: String?, val ceilingId: String?)

@Entity(tableName = "observed_candidates")
data class ObservedCandidate(
    @PrimaryKey val path: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val firstStableAtMillis: Long,
    val lastSeenAtMillis: Long,
)

@Dao
abstract class JournalDao {
    @Query("SELECT * FROM operations WHERE id = :id")
    abstract suspend fun get(id: String): JournalEntity?
    @Query("SELECT * FROM operations WHERE relatedId = :id LIMIT 1")
    abstract suspend fun undoFor(id: String): JournalEntity?
    @Query("SELECT operations.*, EXISTS(SELECT 1 FROM operations AS reverse WHERE reverse.relatedId = operations.id) AS undoRequested FROM operations ORDER BY (phase NOT IN ('COMPLETED', 'FAILED_SAFE')) DESC, startedAt DESC, id DESC LIMIT 100")
    abstract fun history(): Flow<List<JournalHistoryItem>>
    @Query("SELECT COUNT(*) FROM operations WHERE recoveryNeeded = 1 OR phase NOT IN ('COMPLETED', 'FAILED_SAFE')")
    abstract fun unresolvedCount(): Flow<Int>
    @Query("SELECT * FROM operations WHERE phase NOT IN ('COMPLETED', 'FAILED_SAFE') ORDER BY updatedAt, id LIMIT :limit")
    abstract suspend fun incomplete(limit: Int): List<JournalEntity>
    @Query("SELECT * FROM recovery_cursor WHERE slot = 0")
    protected abstract suspend fun cursor(): RecoveryCursor?
    @Query("SELECT MAX(id) FROM operations WHERE phase NOT IN ('COMPLETED', 'FAILED_SAFE')")
    protected abstract suspend fun ceiling(): String?
    @Query("SELECT * FROM operations WHERE phase NOT IN ('COMPLETED', 'FAILED_SAFE') AND (:after IS NULL OR id > :after) AND id <= :ceiling ORDER BY id LIMIT :limit")
    protected abstract suspend fun page(after: String?, ceiling: String, limit: Int): List<JournalEntity>
    @Upsert
    protected abstract suspend fun putCursor(value: RecoveryCursor)

    @Transaction
    open suspend fun nextRecoveryPage(limit: Int): RecoveryPage {
        require(limit in 1..20)
        val position = cursor()
        var upper = position?.ceilingId ?: ceiling() ?: return RecoveryPage(emptyList(), null)
        var rows = page(position?.lastId, upper, limit)
        if (rows.isEmpty()) {
            upper = ceiling() ?: return RecoveryPage(emptyList(), null)
            rows = page(null, upper, limit)
        }
        return RecoveryPage(rows.map { it.record }, upper)
    }

    @Transaction
    open suspend fun advanceRecoveryCursor(lastId: String, ceilingId: String) {
        require(lastId <= ceilingId)
        putCursor(RecoveryCursor(lastId = lastId, ceilingId = ceilingId))
    }

    @Query("SELECT * FROM operation_events WHERE operationId = :id ORDER BY revision DESC LIMIT 1")
    protected abstract suspend fun lastEvent(id: String): JournalEvent?
    @Query("SELECT * FROM operation_events WHERE operationId = :id ORDER BY revision")
    abstract suspend fun events(id: String): List<JournalEvent>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: JournalEntity): Long
    @Update
    protected abstract suspend fun update(entity: JournalEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun event(event: JournalEvent)

    @Transaction
    open suspend fun create(record: OperationRecord): Boolean {
        record.requireNewIntent()
        if (record.relatedId != null) require(get(record.relatedId)?.record?.undoEligible == true) { "Undo parent is not completed" }
        if (insert(JournalEntity(record)) == -1L) return false
        event(JournalEvent(record.id, record.revision, record.phase, record.updatedAt, record.error))
        return true
    }

    @Transaction
    open suspend fun save(record: OperationRecord) {
        val previous = requireNotNull(get(record.id)).record
        record.requireSuccessorOf(previous)
        require(lastEvent(record.id) == JournalEvent(previous.id, previous.revision, previous.phase, previous.updatedAt, previous.error)) { "Journal event/record mismatch" }
        update(JournalEntity(record))
        event(JournalEvent(record.id, record.revision, record.phase, record.updatedAt, record.error))
    }

    @Query("SELECT * FROM observed_candidates WHERE path = :path")
    abstract suspend fun getCandidate(path: String): ObservedCandidate?

    @Query("SELECT * FROM observed_candidates")
    abstract suspend fun allCandidates(): List<ObservedCandidate>

    @Upsert
    abstract suspend fun upsertCandidate(candidate: ObservedCandidate)

    @Query("DELETE FROM observed_candidates WHERE path = :path")
    abstract suspend fun deleteCandidate(path: String)
}

@Database(entities = [JournalEntity::class, JournalEvent::class, RecoveryCursor::class, ObservedCandidate::class], version = 3, exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recovery_cursor (slot INTEGER NOT NULL, lastId TEXT, ceilingId TEXT, PRIMARY KEY(slot))")
                // Legacy recovery inferred success from disappearance. Quarantine those rows; retain claims/history.
                db.execSQL("INSERT INTO operation_events (operationId, revision, phase, at, error) SELECT id, revision + 1, 'RECOVERY_REQUIRED', updatedAt, 'REMOVAL_UNCONFIRMED' FROM operations WHERE phase = 'COMPLETED' AND (removalEvidence IS NULL OR removalEvidence != 'adapter-confirmed')")
                db.execSQL("UPDATE operations SET phase = 'RECOVERY_REQUIRED', error = 'REMOVAL_UNCONFIRMED', recoveryNeeded = 1, completedAt = NULL, revision = revision + 1 WHERE phase = 'COMPLETED' AND (removalEvidence IS NULL OR removalEvidence != 'adapter-confirmed')")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `observed_candidates` (`path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `modifiedAtMillis` INTEGER NOT NULL, `firstStableAtMillis` INTEGER NOT NULL, `lastSeenAtMillis` INTEGER NOT NULL, PRIMARY KEY(`path`))")
            }
        }

        fun open(context: Context, name: String = "operation-journal.db"): JournalDatabase = Room.databaseBuilder(
            context.applicationContext, JournalDatabase::class.java, name,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(object : Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // SQLite FULL requests stronger sync, but is not a power-loss guarantee.
                db.execSQL("PRAGMA synchronous=FULL")
            }
        }).build() // Missing migrations FAIL CLOSED. Never erase/recreate a journal.
    }
}

class RoomOperationJournal(private val dao: JournalDao) : OperationJournal {
    val history: Flow<List<OperationHistoryEntry>> = dao.history().map { rows -> rows.map { it.record.requireConsistentEvidence(); OperationHistoryEntry(it.record, it.undoRequested) } }
    val unresolvedCount = dao.unresolvedCount()
    override suspend fun get(id: String) = dao.get(id)?.record?.also { it.requireConsistentEvidence() }
    override suspend fun undoFor(id: String) = dao.undoFor(id)?.record?.also { it.requireConsistentEvidence() }
    override suspend fun create(record: OperationRecord) = dao.create(record)
    override suspend fun save(record: OperationRecord) = dao.save(record)
    override suspend fun nextRecoveryPage(limit: Int) = dao.nextRecoveryPage(limit)
    override suspend fun advanceRecoveryCursor(lastId: String, ceilingId: String) = dao.advanceRecoveryCursor(lastId, ceilingId)
    override suspend fun incomplete(limit: Int) = dao.incomplete(limit).map { it.record.also { record -> record.requireConsistentEvidence() } }
}

interface CandidateStore {
    suspend fun get(path: String): ObservedCandidate?
    suspend fun all(): List<ObservedCandidate>
    suspend fun upsert(candidate: ObservedCandidate)
    suspend fun delete(path: String)
}

class RoomCandidateStore(private val dao: JournalDao) : CandidateStore {
    override suspend fun get(path: String) = dao.getCandidate(path)
    override suspend fun all() = dao.allCandidates()
    override suspend fun upsert(candidate: ObservedCandidate) = dao.upsertCandidate(candidate)
    override suspend fun delete(path: String) = dao.deleteCandidate(path)
}

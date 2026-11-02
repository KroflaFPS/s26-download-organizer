package com.kroflafps.s26downloadorganizer.history

import android.content.Context
import androidx.room.*
import com.kroflafps.s26downloadorganizer.operations.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "operations", primaryKeys = ["id"], indices = [Index(value = ["sourceToken"], unique = true), Index(value = ["relatedId"], unique = true)])
data class JournalEntity(@Embedded val record: OperationRecord)

@Entity(tableName = "operation_events", primaryKeys = ["operationId", "revision"])
data class JournalEvent(val operationId: String, val revision: Int, val phase: Phase, val at: Long, val error: OperationError?)

data class JournalHistoryItem(@Embedded val record: OperationRecord, val undoRequested: Boolean)

@Dao
abstract class JournalDao {
    @Query("SELECT * FROM operations WHERE id = :id")
    abstract suspend fun get(id: String): JournalEntity?
    @Query("SELECT * FROM operations WHERE relatedId = :id LIMIT 1")
    abstract suspend fun undoFor(id: String): JournalEntity?
    @Query("SELECT operations.*, EXISTS(SELECT 1 FROM operations AS reverse WHERE reverse.relatedId = operations.id) AS undoRequested FROM operations ORDER BY recoveryNeeded DESC, startedAt DESC, id DESC LIMIT 100")
    abstract fun history(): Flow<List<JournalHistoryItem>>
    @Query("SELECT COUNT(*) FROM operations WHERE recoveryNeeded = 1")
    abstract fun unresolvedCount(): Flow<Int>
    @Query("SELECT * FROM operations WHERE phase NOT IN ('COMPLETED', 'FAILED_SAFE') ORDER BY updatedAt, id LIMIT :limit")
    abstract suspend fun incomplete(limit: Int): List<JournalEntity>
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
        record.requireConsistentEvidence()
        if (insert(JournalEntity(record)) == -1L) return false
        event(JournalEvent(record.id, record.revision, record.phase, record.updatedAt, record.error))
        return true
    }

    @Transaction
    open suspend fun save(record: OperationRecord) {
        record.requireConsistentEvidence()
        val previous = requireNotNull(get(record.id)).record
        require(record.type == previous.type && record.relatedId == previous.relatedId && record.sourcePath == previous.sourcePath &&
            record.destinationDirectory == previous.destinationDirectory && record.originalName == previous.originalName &&
            record.sourceIdentity == previous.sourceIdentity && record.sourceSize == previous.sourceSize &&
            record.sourceStamp == previous.sourceStamp && record.contentHash == previous.contentHash && record.stagingPath == previous.stagingPath)
        require(record.revision == previous.revision + 1) { "Journal revision conflict" }
        require(record.startedAt == previous.startedAt && record.updatedAt >= previous.updatedAt)
        update(JournalEntity(record))
        event(JournalEvent(record.id, record.revision, record.phase, record.updatedAt, record.error))
    }
}

@Database(entities = [JournalEntity::class, JournalEvent::class], version = 1, exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao

    companion object {
        fun open(context: Context, name: String = "operation-journal.db"): JournalDatabase = Room.databaseBuilder(
            context.applicationContext, JournalDatabase::class.java, name,
        ).addCallback(object : Callback() {
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
    override suspend fun incomplete(limit: Int) = dao.incomplete(limit).map { it.record.also { record -> record.requireConsistentEvidence() } }
}

package com.kroflafps.s26downloadorganizer.telemetry

import android.content.Context
import androidx.room.*
import com.kroflafps.s26downloadorganizer.readiness.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "telemetry_state")
data class TelemetryState(
    @PrimaryKey val slot: Int = 0, val requested: Long = 0, val completed: Long = 0, val cycle: Long = 0,
    val owner: String? = null, val boot: String = "", val leaseUntil: Long = 0,
    val lastWake: Long = 0, val lastSource: String = "NONE", val lastScan: Long = 0,
    val pages: Long = 0, val warnings: Int = 0, val coverage: String = "NOT_SCANNED",
)
@Entity(tableName = "telemetry_dirs")
data class DirectoryTask(@PrimaryKey val relative: String, val depth: Int = 0, val after: String? = null)
@Entity(tableName = "telemetry_observations", primaryKeys = ["key"])
data class ObservationRow(@Embedded val value: ReadinessObservation)
@Entity(tableName = "telemetry_events")
data class TelemetryEvent(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val source: String,
    val code: String, val entries: Int = 0, val durationMs: Long = 0, val uriCount: Int = 0, val roughMtimeAge: Long? = null, val policy: String = "")

@Dao
abstract class TelemetryDao {
    @Query("SELECT * FROM telemetry_state WHERE slot=0") abstract suspend fun state(): TelemetryState?
    @Query("SELECT * FROM telemetry_state WHERE slot=0") abstract fun observeState(): Flow<TelemetryState?>
    @Query("SELECT * FROM telemetry_events ORDER BY id DESC LIMIT 100") abstract fun eventsFlow(): Flow<List<TelemetryEvent>>
    @Query("SELECT * FROM telemetry_events ORDER BY id DESC LIMIT 400") abstract suspend fun events(): List<TelemetryEvent>
    @Query("SELECT * FROM telemetry_observations LIMIT 4000") abstract suspend fun observations(): List<ObservationRow>
    @Query("SELECT * FROM telemetry_dirs ORDER BY depth, relative LIMIT 1") abstract suspend fun nextDirectory(): DirectoryTask?
    @Query("SELECT COUNT(*) FROM telemetry_dirs") abstract suspend fun directoryCount(): Int
    @Upsert protected abstract suspend fun state(value: TelemetryState)
    @Insert protected abstract suspend fun event(value: TelemetryEvent)
    @Upsert protected abstract suspend fun directory(value: DirectoryTask)
    @Delete protected abstract suspend fun remove(value: DirectoryTask)
    @Upsert protected abstract suspend fun observations(value: List<ObservationRow>)
    @Query("DELETE FROM telemetry_events WHERE at < :before OR id NOT IN (SELECT id FROM telemetry_events ORDER BY id DESC LIMIT 400)") protected abstract suspend fun pruneEvents(before: Long)
    @Query("DELETE FROM telemetry_observations WHERE sampledAt < :before OR key NOT IN (SELECT key FROM telemetry_observations ORDER BY sampledAt DESC LIMIT 4000)") protected abstract suspend fun pruneObservations(before: Long)
    @Transaction open suspend fun log(event: TelemetryEvent) { event(event); pruneEvents(event.at - 7 * 86_400_000L) }
    @Transaction open suspend fun request(source: String, at: Long, uriCount: Int = 0, hint: String = "SIGNAL", policy: String = "") {
        val old = state() ?: TelemetryState()
        check(old.requested < Long.MAX_VALUE)
        state(old.copy(requested = old.requested + 1, lastWake = at, lastSource = source))
        log(TelemetryEvent(at = at, source = source, code = hint, uriCount = uriCount.coerceIn(0, 1000), policy = policy))
    }
    @Transaction open suspend fun acquire(owner: String, boot: String, elapsed: Long): Boolean {
        val old = state() ?: TelemetryState()
        if (old.owner != null && old.boot == boot && old.leaseUntil > elapsed) return false
        var next = old.copy(owner = owner, boot = boot, leaseUntil = elapsed + 30_000)
        if (directoryCount() == 0 && old.requested > old.completed) {
            directory(DirectoryTask("")); next = next.copy(cycle = old.requested, warnings = 0, coverage = "CONTINUING")
        }
        state(next); return true
    }
    @Transaction open suspend fun commit(owner: String, boot: String, elapsed: Long, task: DirectoryTask,
        next: DirectoryTask?, children: List<DirectoryTask>, values: List<ObservationRow>, event: TelemetryEvent, warnings: Int): Boolean {
        val old = requireNotNull(state())
        if (old.owner != owner || old.boot != boot || old.leaseUntil < elapsed) return false // Fencing rejects expired/replaced writers.
        remove(task)
        next?.let { directory(it) }
        var dropped = warnings
        for (child in children) {
            if (directoryCount() < 128) directory(child) else dropped++
        }
        observations(values)
        pruneObservations(event.at - 7 * 86_400_000L)
        val empty = directoryCount() == 0
        val count = (old.warnings.toLong() + dropped).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        state(old.copy(owner = null, leaseUntil = 0, completed = if (empty) old.cycle else old.completed,
            lastScan = event.at, pages = old.pages + 1, warnings = count,
            coverage = if (!empty) "CONTINUING" else if (count > 0) "LIMITED_OR_UNREADABLE" else "CYCLE_COMPLETE_NOT_ATOMIC"))
        log(event); return true
    }
    @Transaction open suspend fun release(owner: String) {
        state()?.takeIf { it.owner == owner }?.let { state(it.copy(owner = null, leaseUntil = 0)) }
    }
    suspend fun pending(): Boolean = (state()?.let { it.requested > it.completed } == true) || directoryCount() > 0
}

/** Separate database/schema: no migration/version collision with PR #4's journal v3. */
@Database(entities = [TelemetryState::class, DirectoryTask::class, ObservationRow::class, TelemetryEvent::class], version = 1, exportSchema = true)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetry(): TelemetryDao
    companion object {
        fun open(context: Context, name: String = "readonly-telemetry.db") = Room.databaseBuilder(context.applicationContext, TelemetryDatabase::class.java, name).build()
        // Missing future migrations fail closed. No destructive fallback.
    }
}

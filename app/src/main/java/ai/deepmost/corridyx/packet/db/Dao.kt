package ai.deepmost.corridyx.packet.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: PacketEntity)

    @Update
    suspend fun update(p: PacketEntity)

    @Query("SELECT * FROM packets WHERE packetId = :id")
    suspend fun byId(id: String): PacketEntity?

    @Query("SELECT * FROM packets WHERE status IN ('PENDING','FAILED') ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingBatch(limit: Int): List<PacketEntity>

    @Query("UPDATE packets SET status = :status, attempts = attempts + :attemptsInc, uploadedAt = :uploadedAt WHERE packetId = :id")
    suspend fun setStatus(id: String, status: PacketStatus, attemptsInc: Int, uploadedAt: Long?)

    @Query("SELECT * FROM packets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PacketEntity>>

    @Query("SELECT * FROM packets WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun forSession(sessionId: String): List<PacketEntity>

    @Query("SELECT COUNT(*) FROM packets WHERE status = :status")
    fun countByStatus(status: PacketStatus): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM packets")
    suspend fun totalBytes(): Long

    // oldest-uploaded-first eviction candidates: DONE packets with evidence still present
    @Query("SELECT * FROM packets WHERE status = 'DONE' AND evidenceEvicted = 0 ORDER BY uploadedAt ASC LIMIT :limit")
    suspend fun evictionCandidates(limit: Int): List<PacketEntity>

    @Query("SELECT COUNT(*) FROM packets")
    suspend fun count(): Int
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startTs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("UPDATE sessions SET endTs = :endTs, distanceM = :distanceM, segmentsVisited = :segments, alertsRaised = :alerts WHERE sessionId = :id")
    suspend fun finalizeSession(id: String, endTs: Long, distanceM: Float, segments: Int, alerts: Int)
}

class Converters {
    @TypeConverter fun statusToString(s: PacketStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): PacketStatus = PacketStatus.valueOf(s)
}

@Database(entities = [PacketEntity::class, SessionEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CorridyxDb : RoomDatabase() {
    abstract fun packets(): PacketDao
    abstract fun sessions(): SessionDao
}

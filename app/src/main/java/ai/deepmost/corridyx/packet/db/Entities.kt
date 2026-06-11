package ai.deepmost.corridyx.packet.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PacketStatus { PENDING, UPLOADING, DONE, FAILED }

/**
 * One finalized segment-visit packet. The full manifest.json is stored both as [manifestJson]
 * (never evicted) and on disk under [dir] alongside blurred evidence thumbnails. Storage-cap
 * eviction deletes evidence files of the oldest DONE packets first; this row + manifest survive.
 */
@Entity(tableName = "packets", indices = [Index("status"), Index("sessionId"), Index("createdAt")])
data class PacketEntity(
    @PrimaryKey val packetId: String,
    val sessionId: String,
    val geohash7: String,
    val headingBucket: Int,
    val timeBucket: String,
    val weatherContext: String,
    val oddScore: Float,
    val enterTs: Long,
    val exitTs: Long,
    val centroidLat: Double,
    val centroidLon: Double,
    val dir: String,                 // absolute packet directory path
    val manifestJson: String,        // canonical manifest (retained even after evidence eviction)
    val evidenceCount: Int,
    val evidenceEvicted: Boolean,
    val status: PacketStatus,
    val attempts: Int,
    val sizeBytes: Long,
    val createdAt: Long,
    val uploadedAt: Long?,
)

/** Per-shift session summary. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val nodeId: String,
    val vehicleLabel: String,
    val startTs: Long,
    val endTs: Long?,
    val distanceM: Float,
    val segmentsVisited: Int,
    val alertsRaised: Int,
)

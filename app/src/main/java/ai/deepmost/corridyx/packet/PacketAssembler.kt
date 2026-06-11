package ai.deepmost.corridyx.packet

import ai.deepmost.corridyx.BuildConfig
import ai.deepmost.corridyx.packet.db.PacketDao
import ai.deepmost.corridyx.packet.db.PacketEntity
import ai.deepmost.corridyx.packet.db.PacketStatus
import ai.deepmost.corridyx.privacy.RegionBlurrer
import ai.deepmost.corridyx.segment.CapabilitySnapshot
import ai.deepmost.corridyx.segment.SegmentResult
import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Off-hot-path assembler: turns a finalized [SegmentResult] into a stored, upload-ready
 * SegmentScorePacket. Blurs every evidence thumbnail (privacy, always-on) before it touches disk,
 * writes manifest.json + JPEGs into the packet dir, inserts the Room row (PENDING), and triggers
 * storage-cap enforcement. Never persists continuous video — only the capped, blurred thumbnails.
 */
class PacketAssembler(
    private val context: Context,
    private val dao: PacketDao,
    private val blurrer: RegionBlurrer,
    private val storage: StorageManager,
    private val nodeId: () -> String,
    private val modelVersions: () -> Map<String, String>,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun assemble(result: SegmentResult, sessionId: String): PacketEntity {
        val packetId = UUID.randomUUID().toString()
        val dir = File(File(context.filesDir, "packets"), packetId).apply { mkdirs() }

        val evidenceRefs = ArrayList<EvidenceRef>()
        var evicted = false
        result.evidence.forEachIndexed { i, cand ->
            runCatching {
                val blurred = blurrer.blur(cand)
                val file = File(dir, "evidence_$i.jpg")
                file.outputStream().use { blurred.bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                blurred.bitmap.recycle()
                evidenceRefs.add(EvidenceRef("evidence_$i.jpg", cand.dimension, cand.reason, blurred.regions))
            }.onFailure { Timber.w(it, "evidence assemble failed (dropping thumbnail $i)") }
        }

        val packet = SegmentScorePacket(
            packetId = packetId,
            nodeId = nodeId(),
            appVersion = BuildConfig.VERSION_NAME,
            schemaVersion = BuildConfig.PACKET_SCHEMA_VERSION,
            sessionId = sessionId,
            segment = result.segmentRef,
            window = result.window,
            timeBucket = result.timeBucket.name,
            weatherContext = result.weatherContext,
            oddScore = result.oddScore,
            scores = result.scores,
            gates = result.gates,
            capability = capability(result.capability),
            evidence = evidenceRefs,
        )

        val manifestJson = json.encodeToString(SegmentScorePacket.serializer(), packet)
        File(dir, "manifest.json").writeText(manifestJson)

        val sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val entity = PacketEntity(
            packetId = packetId,
            sessionId = sessionId,
            geohash7 = result.segmentRef.geohash7,
            headingBucket = result.segmentRef.headingBucket,
            timeBucket = result.timeBucket.name,
            weatherContext = result.weatherContext,
            oddScore = result.oddScore,
            enterTs = result.window.enterTs,
            exitTs = result.window.exitTs,
            centroidLat = result.segmentRef.centroidLat,
            centroidLon = result.segmentRef.centroidLon,
            dir = dir.absolutePath,
            manifestJson = manifestJson,
            evidenceCount = evidenceRefs.size,
            evidenceEvicted = evicted,
            status = PacketStatus.PENDING,
            attempts = 0,
            sizeBytes = sizeBytes,
            createdAt = System.currentTimeMillis(),
            uploadedAt = null,
        )
        dao.insert(entity)
        Timber.i("Assembled packet %s odd=%.1f evidence=%d bytes=%d", packetId, result.oddScore, evidenceRefs.size, sizeBytes)

        storage.enforceCap()
        return entity
    }

    private fun capability(c: CapabilitySnapshot): Capability = Capability(
        rawGnssSupported = c.rawGnssSupported,
        gnssCapability = c.gnssCapability.name,
        lightSensorPresent = c.lightSensorPresent,
        modelVersions = modelVersions(),
    )
}

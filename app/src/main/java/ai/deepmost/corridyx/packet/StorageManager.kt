package ai.deepmost.corridyx.packet

import ai.deepmost.corridyx.config.Settings
import ai.deepmost.corridyx.packet.db.PacketDao
import timber.log.Timber
import java.io.File

/**
 * Enforces the global storage cap with oldest-uploaded-first eviction. Evidence thumbnails of the
 * oldest DONE packets are deleted first; the packet row and its manifest.json are NEVER evicted
 * (manifests are the durable record). If still over cap after evicting all uploaded evidence, we
 * stop — pending packets and all manifests are preserved.
 */
class StorageManager(
    private val dao: PacketDao,
    private val settingsProvider: () -> Settings,
) {
    suspend fun enforceCap() {
        val capBytes = settingsProvider().storageCapMb.toLong() * 1024L * 1024L
        var total = dao.totalBytes()
        if (total <= capBytes) return
        Timber.i("Storage over cap (%d > %d) — evicting oldest uploaded evidence", total, capBytes)

        var guard = 0
        while (total > capBytes && guard < 1000) {
            guard++
            val candidates = dao.evictionCandidates(16)
            if (candidates.isEmpty()) {
                Timber.w("No more evictable evidence; manifests retained, still %d bytes", total)
                break
            }
            for (p in candidates) {
                if (total <= capBytes) break
                val dir = File(p.dir)
                var freed = 0L
                dir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".jpg")) { freed += f.length(); f.delete() }
                }
                val newSize = (dir.listFiles()?.sumOf { it.length() } ?: 0L)
                dao.update(
                    p.copy(evidenceEvicted = true, evidenceCount = 0, sizeBytes = newSize)
                )
                total -= freed
                if (freed > 0) Timber.i("Evicted %d bytes evidence from %s", freed, p.packetId)
            }
        }
    }
}

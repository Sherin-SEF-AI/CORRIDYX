package ai.deepmost.corridyx.upload

import ai.deepmost.corridyx.AppGraph
import ai.deepmost.corridyx.packet.db.PacketEntity
import ai.deepmost.corridyx.packet.db.PacketStatus
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads PENDING/FAILED packets to the configured fleet endpoint as a zip (manifest.json + blurred
 * evidence) with bearer-token auth. Network is the ONLY use; analysis works fully offline and this
 * worker just drains the queue when connectivity (wifi-only by default) is available. Survives
 * restarts via WorkManager; per-packet status PENDING -> UPLOADING -> DONE/FAILED with backoff.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = AppGraph.get(applicationContext)
        val settings = graph.settingsRepository.flow.first()
        val dao = graph.db.packets()

        if (settings.uploadEndpoint.isBlank()) {
            Timber.i("Upload: no endpoint configured — leaving %d packets queued", dao.count())
            return@withContext Result.success()
        }

        val batch = dao.pendingBatch(20)
        if (batch.isEmpty()) return@withContext Result.success()

        var anyFailed = false
        for (p in batch) {
            dao.setStatus(p.packetId, PacketStatus.UPLOADING, attemptsInc = 0, uploadedAt = null)
            val ok = runCatching { uploadOne(p, settings.uploadEndpoint, settings.uploadToken) }
                .getOrElse { Timber.w(it, "upload failed for %s", p.packetId); false }
            if (ok) {
                dao.setStatus(p.packetId, PacketStatus.DONE, attemptsInc = 1, uploadedAt = System.currentTimeMillis())
                Timber.i("Uploaded packet %s", p.packetId)
            } else {
                dao.setStatus(p.packetId, PacketStatus.FAILED, attemptsInc = 1, uploadedAt = null)
                anyFailed = true
            }
        }
        if (anyFailed) Result.retry() else Result.success()
    }

    private fun uploadOne(p: PacketEntity, endpoint: String, token: String): Boolean {
        val dir = File(p.dir)
        if (!dir.exists()) {
            Timber.w("Packet dir missing for %s — marking done (nothing to send)", p.packetId)
            return true
        }
        val zip = File.createTempFile("pkt_", ".zip", applicationContext.cacheDir)
        try {
            Zip.zipDir(dir, zip)
            val boundary = "----corridyx" + UUID.randomUUID().toString().replace("-", "")
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Corridyx-Packet-Id", p.packetId)
                setRequestProperty("X-Corridyx-Node-Id", p.sessionId)
            }
            conn.outputStream.buffered().use { os ->
                val header = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"packet\"; filename=\"${p.packetId}.zip\"\r\n" +
                    "Content-Type: application/zip\r\n\r\n"
                os.write(header.toByteArray())
                zip.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            conn.disconnect()
            return code in 200..299
        } finally {
            zip.delete()
        }
    }
}

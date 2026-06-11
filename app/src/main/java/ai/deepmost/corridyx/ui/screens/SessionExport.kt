package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.packet.db.PacketEntity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles a session's packets (manifest.json + blurred evidence) into one zip and shares it. */
object SessionExport {
    fun export(context: Context, sessionId: String, packets: List<PacketEntity>) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(sharedDir, "corridyx_session_${sessionId.take(8)}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            for (p in packets) {
                val dir = File(p.dir)
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    zos.putNextEntry(ZipEntry("${p.packetId}/${f.name}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export CORRIDYX session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

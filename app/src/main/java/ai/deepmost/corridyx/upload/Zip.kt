package ai.deepmost.corridyx.upload

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips a packet directory (manifest.json + blurred evidence jpgs) into [out]. */
object Zip {
    fun zipDir(dir: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (!f.isFile) return@forEach
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}

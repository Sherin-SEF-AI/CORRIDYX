package ai.deepmost.corridyx.segment

import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Persists the single in-flight [AccumulatorSnapshot] to one small file so a segment-visit survives
 * process death (atomic write via temp-then-rename). Bounded: exactly one file, overwritten.
 */
class AccumulatorSnapshotStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "active_segment.json")
    private val tmp = File(context.filesDir, "active_segment.json.tmp")

    fun save(snapshot: AccumulatorSnapshot) {
        runCatching {
            tmp.writeText(json.encodeToString(AccumulatorSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { Timber.w(it, "snapshot save failed") }
    }

    fun load(): AccumulatorSnapshot? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(AccumulatorSnapshot.serializer(), file.readText())
        }.getOrNull()
    }

    fun clear() { runCatching { file.delete(); tmp.delete() } }
}

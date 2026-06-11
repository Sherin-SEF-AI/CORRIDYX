package ai.deepmost.corridyx.registry

import ai.deepmost.corridyx.dimensions.Dim
import android.content.Context
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Engine status row shown on the FLEET NODE > model registry screen and recorded in packets. */
data class EngineStatus(
    val head: String,
    val engineTag: String,    // "<modelId>@<version>" or "classical"
    val accelerator: Accelerator,
    val loaded: Boolean,
)

/**
 * Parses assets/model_registry.json, attempts to load each declared .tflite via [LiteRtEngine],
 * and exposes per-head engines. Day-one: the manifest can be empty (or files absent) and every head
 * falls back to classical CV — never a prerequisite. Loading is best-effort and lazy-safe.
 */
class ModelRegistry(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val engines = HashMap<String, LiteRtEngine>()
    private var manifest: ModelRegistryManifest = ModelRegistryManifest()

    /** Load manifest + engines. Safe to call once at startup; failures are logged, never fatal. */
    fun initialize() {
        manifest = runCatching {
            context.assets.open("model_registry.json").bufferedReader().use {
                json.decodeFromString(ModelRegistryManifest.serializer(), it.readText())
            }
        }.getOrElse {
            Timber.i("No model_registry.json or parse failed (%s) — all heads classical", it.message)
            ModelRegistryManifest()
        }

        for (entry in manifest.models) {
            val engine = LiteRtEngine.tryLoad(context, entry)
            if (engine != null) {
                engines[entry.head] = engine
                Timber.i("Registry: head=%s engine=%s accel=%s", entry.head, entry.engineTag, engine.accelerator)
            } else {
                Timber.i("Registry: head=%s model %s unavailable -> classical", entry.head, entry.engineTag)
            }
        }
    }

    fun engineFor(head: String): LiteRtEngine? = engines[head]

    fun entryFor(head: String): ModelEntry? = manifest.models.firstOrNull { it.head == head }

    /** Map of head -> "<modelId>@<version>" for the packet capability block. */
    fun versionMap(): Map<String, String> =
        manifest.models.associate { it.head to it.engineTag }

    fun statuses(): List<EngineStatus> = Dim.ALL.map { head ->
        val e = engines[head]
        EngineStatus(
            head = head,
            engineTag = e?.entry?.engineTag ?: "classical",
            accelerator = e?.accelerator ?: Accelerator.NONE,
            loaded = e != null,
        )
    }

    fun close() {
        engines.values.forEach { it.close() }
        engines.clear()
    }
}

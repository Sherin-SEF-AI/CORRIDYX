package ai.deepmost.corridyx.registry

import kotlinx.serialization.Serializable

/** Kind of model, governs how its head consumes outputs. */
enum class ModelKind { DETECTOR, SEGMENTATION, CLASSIFIER }

/**
 * One model-registry manifest entry (assets/model_registry.json). A fine-tuned .tflite is a
 * drop-in: add/replace an entry pointing at the new asset and bump [version]; the head picks it up
 * and stamps "<modelId>@<version>" as the producing engine. Absent file => head stays classical.
 */
@Serializable
data class ModelEntry(
    val head: String,            // Dim.* id this model serves
    val modelId: String,         // stable id, e.g. "coco_ssd_mobilenet_v1"
    val version: String,         // semver-ish, e.g. "1.0.0"
    val assetPath: String,       // path under assets/, e.g. "models/coco_ssd.tflite"
    val kind: ModelKind,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputChannels: Int = 3,
    val quantized: Boolean = true,
    val labels: List<String> = emptyList(),
) {
    val engineTag: String get() = "$modelId@$version"
}

@Serializable
data class ModelRegistryManifest(
    val schemaVersion: Int = 1,
    val models: List<ModelEntry> = emptyList(),
)

package ai.deepmost.corridyx.registry

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Which delegate actually backed an interpreter — recorded in the registry/engine status UI. */
enum class Accelerator { GPU, NNAPI, CPU, NONE }

/**
 * A loaded LiteRT interpreter for one [ModelEntry], with GPU -> NNAPI -> CPU fallback chosen at
 * load time. Inference runs off the main thread (the analysis executor). Thread-confined: callers
 * must not invoke [run] concurrently for one engine (heads are single-writer per analysis tick).
 */
class LiteRtEngine private constructor(
    val entry: ModelEntry,
    private val interpreter: Interpreter,
    val accelerator: Accelerator,
    private val gpuDelegate: GpuDelegate?,
    private val nnApiDelegate: NnApiDelegate?,
) {
    fun run(input: ByteBuffer, outputs: Map<Int, Any>) {
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
    }

    fun runSingle(input: ByteBuffer, output: Any) {
        interpreter.run(input, output)
    }

    fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
        runCatching { nnApiDelegate?.close() }
    }

    companion object {
        /** Try to build an engine for [entry]; returns null if the asset is missing/unloadable. */
        fun tryLoad(context: Context, entry: ModelEntry): LiteRtEngine? {
            val model = loadModel(context, entry.assetPath) ?: return null

            // 1) GPU delegate
            runCatching {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    val gpu = GpuDelegate()
                    val opts = Interpreter.Options().addDelegate(gpu)
                    val interp = Interpreter(model, opts)
                    Timber.i("LiteRT %s loaded on GPU", entry.engineTag)
                    return LiteRtEngine(entry, interp, Accelerator.GPU, gpu, null)
                }
            }.onFailure { Timber.w(it, "GPU delegate failed for %s, trying NNAPI", entry.engineTag) }

            // 2) NNAPI delegate
            runCatching {
                val nn = NnApiDelegate()
                val opts = Interpreter.Options().addDelegate(nn)
                val interp = Interpreter(model, opts)
                Timber.i("LiteRT %s loaded on NNAPI", entry.engineTag)
                return LiteRtEngine(entry, interp, Accelerator.NNAPI, null, nn)
            }.onFailure { Timber.w(it, "NNAPI delegate failed for %s, trying CPU", entry.engineTag) }

            // 3) CPU
            return runCatching {
                val opts = Interpreter.Options().apply { numThreads = 2 }
                val interp = Interpreter(model, opts)
                Timber.i("LiteRT %s loaded on CPU", entry.engineTag)
                LiteRtEngine(entry, interp, Accelerator.CPU, null, null)
            }.getOrNull()
        }

        private fun loadModel(context: Context, assetPath: String): MappedByteBuffer? {
            // Asset first, then absolute path (sideloaded models in app files dir).
            return runCatching {
                context.assets.openFd(assetPath).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                    )
                }
            }.getOrElse {
                val f = java.io.File(assetPath)
                if (!f.exists()) return null
                runCatching {
                    FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
                }.getOrNull()
            }
        }
    }
}

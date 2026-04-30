package com.echoai.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite YAMNet wrapper. Loads the model from `assets/yamnet.tflite` and the class map
 * from `assets/yamnet_class_map.csv`. Tries the NNAPI delegate first (Hexagon NPU on
 * Snapdragon devices); falls back to multi-threaded CPU on init failure.
 *
 * Input: 1D float waveform, [-1, 1], length [inputSampleCount] (15600 = 0.975 s @ 16 kHz).
 * Output: 521-class scores; we surface the top [topK] by confidence.
 *
 * Single-threaded use only — Interpreter is not thread-safe. The pipeline calls this
 * from one Dispatchers.Default coroutine per window, so no synchronization needed.
 */
class YamnetClassifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    override val inputSampleCount: Int,
    private val outputClassCount: Int,
    private val outputFramesCount: Int,
    private val topK: Int,
    private val delegateLabel: String,
) : SoundClassifier {

    /** "NNAPI", "CPU", etc. — handy to surface in the UI. */
    val backend: String get() = delegateLabel

    private val outputBuffer = Array(outputFramesCount) { FloatArray(outputClassCount) }

    override fun classify(monoNormalized: FloatArray): List<LabeledScore> {
        require(monoNormalized.size == inputSampleCount) {
            "expected $inputSampleCount samples, got ${monoNormalized.size}"
        }
        // Many YAMNet variants accept a 1-D float array directly; some MediaPipe-wrapped
        // variants want a 2-D [1, N] tensor. Both succeed via the run(input, output)
        // overload as long as the underlying buffer is the right total size.
        interpreter.run(monoNormalized, outputBuffer)

        // For multi-frame outputs, mean-pool over time before ranking.
        val pooled = FloatArray(outputClassCount)
        if (outputFramesCount == 1) {
            System.arraycopy(outputBuffer[0], 0, pooled, 0, outputClassCount)
        } else {
            for (frame in 0 until outputFramesCount) {
                val row = outputBuffer[frame]
                for (i in 0 until outputClassCount) pooled[i] += row[i]
            }
            val invN = 1f / outputFramesCount
            for (i in 0 until outputClassCount) pooled[i] *= invN
        }

        // Top-K selection without sorting all 521 entries.
        val heap = ArrayList<IndexedScore>(topK + 1)
        for (i in 0 until outputClassCount) {
            val s = pooled[i]
            if (heap.size < topK) {
                heap += IndexedScore(i, s)
                heap.sortBy { it.score }
            } else if (s > heap[0].score) {
                heap[0] = IndexedScore(i, s)
                heap.sortBy { it.score }
            }
        }
        return heap.sortedByDescending { it.score }.map { entry ->
            LabeledScore(
                label = labels.getOrElse(entry.index) { "class_${entry.index}" },
                confidence = entry.score,
            )
        }
    }

    fun close() {
        interpreter.close()
    }

    private data class IndexedScore(val index: Int, val score: Float)

    companion object {
        private const val TAG = "YamnetClassifier"

        /**
         * Build a classifier. Returns null only if the assets are missing — never throws.
         * Delegate selection: NNAPI → CPU. Failures during NNAPI init log and fall back
         * silently so the prototype keeps running.
         */
        fun create(
            context: Context,
            modelAsset: String = "yamnet.tflite",
            classMapAsset: String = "yamnet_class_map.csv",
            topK: Int = 5,
            preferNnapi: Boolean = true,
        ): YamnetClassifier? {
            val modelBuffer = try {
                loadAssetAsMappedBuffer(context, modelAsset)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $modelAsset", e); return null
            }
            val labels = try {
                loadClassMap(context, classMapAsset)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $classMapAsset", e); return null
            }

            // Try NNAPI first.
            if (preferNnapi) {
                runCatching {
                    val delegate = NnApiDelegate()
                    val opts = Interpreter.Options().apply { addDelegate(delegate) }
                    val interp = Interpreter(modelBuffer, opts)
                    val (inLen, frames, classes) = readShapes(interp)
                    Log.i(TAG, "YAMNet on NNAPI  input=$inLen out=[$frames,$classes]")
                    return YamnetClassifier(interp, labels, inLen, classes, frames, topK, "NNAPI")
                }.onFailure { Log.w(TAG, "NNAPI delegate failed, falling back to CPU: ${it.message}") }
            }

            // CPU fallback.
            return runCatching {
                val opts = Interpreter.Options().apply { setNumThreads(2) }
                val interp = Interpreter(modelBuffer, opts)
                val (inLen, frames, classes) = readShapes(interp)
                Log.i(TAG, "YAMNet on CPU  input=$inLen out=[$frames,$classes]")
                YamnetClassifier(interp, labels, inLen, classes, frames, topK, "CPU")
            }.getOrElse {
                Log.e(TAG, "Failed to construct CPU interpreter", it); null
            }
        }

        private fun readShapes(interp: Interpreter): Triple<Int, Int, Int> {
            val inputShape = interp.getInputTensor(0).shape() // e.g. [15600] or [1, 15600]
            val inputLen = inputShape.fold(1) { acc, d -> acc * d }
            val outShape = interp.getOutputTensor(0).shape() // commonly [1, 521] or [N, 521]
            val (frames, classes) = when (outShape.size) {
                1 -> 1 to outShape[0]
                2 -> outShape[0] to outShape[1]
                else -> {
                    Log.w(TAG, "Unexpected output rank ${outShape.size}: ${outShape.toList()}")
                    1 to outShape.last()
                }
            }
            return Triple(inputLen, frames, classes)
        }

        private fun loadAssetAsMappedBuffer(context: Context, assetName: String): MappedByteBuffer {
            val afd = context.assets.openFd(assetName)
            FileInputStream(afd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength,
                )
            }
        }

        private fun loadClassMap(context: Context, assetName: String): List<String> {
            val labels = mutableListOf<String>()
            BufferedReader(InputStreamReader(context.assets.open(assetName))).use { reader ->
                var first = true
                reader.forEachLine { line ->
                    if (first) { first = false; return@forEachLine } // skip header
                    if (line.isBlank()) return@forEachLine
                    labels += parseDisplayName(line)
                }
            }
            return labels
        }

        /** Parse the third CSV column, handling quoted fields with internal commas. */
        private fun parseDisplayName(csvLine: String): String {
            // Format: index,mid,display_name where display_name may be quoted "..." with commas inside.
            val first = csvLine.indexOf(',')
            if (first < 0) return csvLine
            val second = csvLine.indexOf(',', first + 1)
            if (second < 0) return csvLine.substring(first + 1)
            var name = csvLine.substring(second + 1).trim()
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length - 1)
            }
            return name
        }

    }
}

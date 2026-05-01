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
 * Output: top [topK] [LabeledScore]s.
 *
 * If `assets/yamnet_consolidation_map.json` is present (default), the raw 521-class
 * sigmoid output is consolidated via top-k noisy-OR (see [YamnetConsolidationMap])
 * into the 42 application groups defined there before top-K selection. Each output
 * stays an independent score in [0, 1]; ranking and downstream label matching use
 * the group names. If the map fails to load the classifier silently falls back to
 * raw 521-class output.
 *
 * Single-threaded use only — Interpreter is not thread-safe. The pipeline calls this
 * from one Dispatchers.Default coroutine per window, so no synchronization needed.
 */
class YamnetClassifier private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    override val inputSampleCount: Int,
    private val rawClassCount: Int,
    private val outputFramesCount: Int,
    private val topK: Int,
    private val delegateLabel: String,
    private val consolidation: YamnetConsolidationMap?,
) : SoundClassifier {

    /** "NNAPI", "CPU", etc. — handy to surface in the UI. */
    val backend: String get() = delegateLabel

    /** True when consolidation post-processing is active. */
    val isConsolidated: Boolean get() = consolidation != null

    /** Effective output class count after consolidation (or [rawClassCount] if disabled). */
    val effectiveClassCount: Int get() = consolidation?.groupCount ?: rawClassCount

    private val outputBuffer = Array(outputFramesCount) { FloatArray(rawClassCount) }
    private val pooled = FloatArray(rawClassCount)
    private val groupScores: FloatArray? = consolidation?.let { FloatArray(it.groupCount) }

    override fun classify(monoNormalized: FloatArray): List<LabeledScore> {
        require(monoNormalized.size == inputSampleCount) {
            "expected $inputSampleCount samples, got ${monoNormalized.size}"
        }
        // Many YAMNet variants accept a 1-D float array directly; some MediaPipe-wrapped
        // variants want a 2-D [1, N] tensor. Both succeed via the run(input, output)
        // overload as long as the underlying buffer is the right total size.
        interpreter.run(monoNormalized, outputBuffer)

        // Mean-pool over time frames to a single 521-vector of sigmoid scores.
        if (outputFramesCount == 1) {
            System.arraycopy(outputBuffer[0], 0, pooled, 0, rawClassCount)
        } else {
            for (i in 0 until rawClassCount) pooled[i] = 0f
            for (frame in 0 until outputFramesCount) {
                val row = outputBuffer[frame]
                for (i in 0 until rawClassCount) pooled[i] += row[i]
            }
            val invN = 1f / outputFramesCount
            for (i in 0 until rawClassCount) pooled[i] *= invN
        }

        // Consolidate to group scores (max-pool, sigmoid-preserving) or use raw 521.
        val scores: FloatArray
        val n: Int
        if (consolidation != null && groupScores != null) {
            consolidation.consolidate(pooled, groupScores)
            scores = groupScores
            n = consolidation.groupCount
        } else {
            scores = pooled
            n = rawClassCount
        }

        // Top-K selection without sorting all entries.
        val heap = ArrayList<IndexedScore>(topK + 1)
        for (i in 0 until n) {
            val s = scores[i]
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
         * Build a classifier. Returns null only if the model assets are missing — never
         * throws. Delegate selection: NNAPI → CPU. Failures during NNAPI init log and
         * fall back silently so the prototype keeps running.
         *
         * If [useConsolidation] is true (default), the 42-group consolidation map is
         * loaded from [consolidationMapAsset] and applied to the sigmoid output. If the
         * map file is missing or malformed, falls back to raw 521-class output.
         */
        fun create(
            context: Context,
            modelAsset: String = "yamnet.tflite",
            classMapAsset: String = "yamnet_class_map.csv",
            consolidationMapAsset: String = "yamnet_consolidation_map.json",
            useConsolidation: Boolean = true,
            topK: Int = 5,
            preferNnapi: Boolean = true,
        ): YamnetClassifier? {
            val modelBuffer = try {
                loadAssetAsMappedBuffer(context, modelAsset)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $modelAsset", e); return null
            }
            val rawLabels = try {
                loadClassMap(context, classMapAsset)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $classMapAsset", e); return null
            }

            val consolidation = if (useConsolidation) {
                YamnetConsolidationMap.load(context, consolidationMapAsset, rawLabels.size)
            } else null
            val effectiveLabels = consolidation?.groupNames ?: rawLabels

            // Try NNAPI first.
            if (preferNnapi) {
                runCatching {
                    val delegate = NnApiDelegate()
                    val opts = Interpreter.Options().apply { addDelegate(delegate) }
                    val interp = Interpreter(modelBuffer, opts)
                    val (inLen, frames, classes) = readShapes(interp)
                    Log.i(
                        TAG,
                        "YAMNet on NNAPI  input=$inLen out=[$frames,$classes]" +
                            (consolidation?.let { "  consolidated → ${it.groupCount}" } ?: "  raw"),
                    )
                    return YamnetClassifier(
                        interp, effectiveLabels, inLen, classes, frames, topK, "NNAPI", consolidation,
                    )
                }.onFailure { Log.w(TAG, "NNAPI delegate failed, falling back to CPU: ${it.message}") }
            }

            // CPU fallback.
            return runCatching {
                val opts = Interpreter.Options().apply { setNumThreads(2) }
                val interp = Interpreter(modelBuffer, opts)
                val (inLen, frames, classes) = readShapes(interp)
                Log.i(
                    TAG,
                    "YAMNet on CPU  input=$inLen out=[$frames,$classes]" +
                        (consolidation?.let { "  consolidated → ${it.groupCount}" } ?: "  raw"),
                )
                YamnetClassifier(
                    interp, effectiveLabels, inLen, classes, frames, topK, "CPU", consolidation,
                )
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

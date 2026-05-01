package com.echoai.pipeline

import com.echoai.audio.AudioWindow
import com.echoai.domain.ClassificationResult
import com.echoai.ml.SoundClassifier

/**
 * Wraps a [SoundClassifier] and runs it against a 4-channel-to-mono downmix of the window.
 * The teammate's YAMNet runner plugs in via the [SoundClassifier] interface.
 */
class ClassificationStage(private val classifier: SoundClassifier) {

    fun close() {
        (classifier as? com.echoai.ml.YamnetClassifier)?.close()
    }

    fun classify(window: AudioWindow): ClassificationResult {
        val mono = downmix4(
            window.bottomLeft, window.bottomRight,
            window.backLeft, window.backRight,
        )
        val normalized = toFloatNormalized(mono, classifier.inputSampleCount)
        val topK = classifier.classify(normalized)
        return ClassificationResult(window.frameNumber, topK)
    }

    private fun downmix4(a: ShortArray, b: ShortArray, c: ShortArray, d: ShortArray): ShortArray {
        val n = minOf(a.size, b.size, c.size, d.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val sum = a[i].toInt() + b[i].toInt() + c[i].toInt() + d[i].toInt()
            out[i] = (sum / 4).toShort()
        }
        return out
    }

    /**
     * Convert s16 → float [-1, 1] and resize to exactly [targetLen]. Trims if longer,
     * right-pads with zeros if shorter.
     */
    private fun toFloatNormalized(samples: ShortArray, targetLen: Int): FloatArray {
        val out = FloatArray(targetLen)
        val copyLen = minOf(samples.size, targetLen)
        for (i in 0 until copyLen) out[i] = samples[i] / 32768f
        return out
    }
}

package com.echoai.ml

/**
 * Classification interface that the YAMNet runner plugs into. Decoupled from the
 * pipeline so the teammate's existing LiteRT YAMNet wrapper can implement this directly
 * without taking a dependency on the rest of echoAI.
 */
interface SoundClassifier {

    /** Required input length in samples (YAMNet: 15_600 = 0.975 s @ 16 kHz). */
    val inputSampleCount: Int

    /**
     * Classify a mono float waveform normalized to [-1, 1].
     * Caller pads/trims to [inputSampleCount] before calling.
     */
    fun classify(monoNormalized: FloatArray): List<LabeledScore>
}

data class LabeledScore(val label: String, val confidence: Float)

/**
 * Stub classifier for end-to-end smoke tests before the real YAMNet runner is wired.
 * Reports a synthetic top-3 keyed off the input RMS so the UI shows live values.
 */
class StubSoundClassifier(
    override val inputSampleCount: Int = 15_600,
) : SoundClassifier {

    override fun classify(monoNormalized: FloatArray): List<LabeledScore> {
        var sumSq = 0.0
        for (v in monoNormalized) sumSq += v * v
        val rms = Math.sqrt(sumSq / monoNormalized.size.coerceAtLeast(1)).toFloat()
        val active = (rms * 8f).coerceIn(0f, 1f)
        return when {
            active < 0.05f -> listOf(LabeledScore("Silence", 0.95f))
            active < 0.20f -> listOf(
                LabeledScore("Ambient", (0.4f + active).coerceAtMost(0.9f)),
                LabeledScore("Speech",  (0.2f + active * 0.5f)),
                LabeledScore("Vehicle", (0.1f + active * 0.3f)),
            )
            else -> listOf(
                LabeledScore("LoudSound", active),
                LabeledScore("Vehicle",   active * 0.6f),
                LabeledScore("Alarm",     active * 0.4f),
            )
        }
    }
}

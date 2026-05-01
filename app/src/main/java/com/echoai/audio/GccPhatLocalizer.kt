package com.echoai.audio

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.max
import kotlin.math.sqrt

/**
 * GCC-PHAT (Generalized Cross-Correlation with Phase Transform) lag estimator.
 *
 * Computes:
 *   1. X(f) = FFT(a),  Y(f) = FFT(b)            both zero-padded to [fftSize]
 *   2. G(f) = X(f) * conj(Y(f))                 cross-spectrum
 *   3. G_phat(f) = G(f) / max(|G(f)|, ε·peak)   PHAT weighting (phase-only),
 *                                                ε regularization prevents noise
 *                                                amplification at near-silent bins
 *   4. r(τ) = IFFT(G_phat)                       correlation peak indicates lag
 *   5. argmax over τ ∈ [−maxLag, +maxLag]
 *
 * Why PHAT over plain time-domain cross-correlation: the phase transform discards
 * spectral magnitude entirely, leaving only phase. Phase is what the geometry of the
 * source determines — magnitude is what reverberation and amplitude variation
 * dominate. For indoor sources past ~3 ft, the direct-path arrival is usually dwarfed
 * by reverb energy in plain CC; PHAT recovers the direct path's phase signature
 * because reflections add as separate phase contributors that don't reinforce a
 * single lag.
 *
 * Sign convention: positive lag means signal `b` is delayed relative to `a` —
 * the source reached `a` first. (Same as the time-domain implementation.)
 *
 * Confidence: peak / (3 × RMS of correlations across the [−maxLag, +maxLag]
 * search range), clamped to [0, 1]. Same heuristic as the previous
 * implementation, kept for CSV-comparison continuity.
 *
 * Buffers are pre-allocated to [fftSize] (default 32768, max input 16000 + zero-pad).
 * Input arrays smaller than [fftSize] are zero-padded; inputs larger are truncated
 * (shouldn't happen given current pipeline window sizes).
 *
 * Single-threaded use only — buffers are reused across calls.
 */
class GccPhatLocalizer(
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    private val phatEpsilon: Double = 0.01,
) {

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "fftSize must be a positive power of two, got $fftSize"
        }
    }

    private val fft = DoubleFFT_1D(fftSize.toLong())
    // Interleaved complex buffers: [re0, im0, re1, im1, ...]
    private val bufA = DoubleArray(2 * fftSize)
    private val bufB = DoubleArray(2 * fftSize)

    fun localize(a: ShortArray, b: ShortArray, maxLagSamples: Int): LagResult {
        val n = minOf(a.size, b.size, fftSize)
        if (n == 0 || maxLagSamples < 0) return LagResult(0, 0f)
        if (2 * maxLagSamples + 1 > fftSize) return LagResult(0, 0f)

        // Energy check (also serves as confidence denominator sanity guard).
        var sumA2 = 0.0
        var sumB2 = 0.0
        for (i in 0 until n) {
            sumA2 += a[i].toDouble() * a[i].toDouble()
            sumB2 += b[i].toDouble() * b[i].toDouble()
        }
        if (sumA2 * sumB2 < 1e-12) return LagResult(0, 0f)

        // Zero-pad both signals as interleaved complex (im = 0). Zero whole buffer
        // first so any tail from a previous call doesn't leak through.
        zeroFill(bufA)
        zeroFill(bufB)
        for (i in 0 until n) {
            bufA[2 * i] = a[i].toDouble()
            bufB[2 * i] = b[i].toDouble()
        }

        fft.complexForward(bufA)
        fft.complexForward(bufB)

        // Cross-spectrum G = X * conj(Y), with running peak |G| for the PHAT floor.
        // Stored back into bufA in-place.
        var peakMag = 0.0
        for (k in 0 until fftSize) {
            val ar = bufA[2 * k]
            val ai = bufA[2 * k + 1]
            val br = bufB[2 * k]
            val bi = bufB[2 * k + 1]
            // X * conj(Y) = (ar + i ai)(br − i bi) = (ar br + ai bi) + i (ai br − ar bi)
            val gr = ar * br + ai * bi
            val gi = ai * br - ar * bi
            bufA[2 * k] = gr
            bufA[2 * k + 1] = gi
            val mag = sqrt(gr * gr + gi * gi)
            if (mag > peakMag) peakMag = mag
        }
        if (peakMag < 1e-12) return LagResult(0, 0f)

        // PHAT weighting: divide each bin by its magnitude, with an ε·peak floor to
        // keep near-silent bins from being amplified into noise dominance.
        val floor = peakMag * phatEpsilon
        for (k in 0 until fftSize) {
            val gr = bufA[2 * k]
            val gi = bufA[2 * k + 1]
            val mag = sqrt(gr * gr + gi * gi)
            val scale = 1.0 / max(mag, floor)
            bufA[2 * k] = gr * scale
            bufA[2 * k + 1] = gi * scale
        }

        // IFFT (with 1/N scaling) → real-valued cross-correlation in bufA real parts.
        fft.complexInverse(bufA, true)

        // Peak search across [−maxLag, +maxLag]. Lag k ≥ 0 lives at index k;
        // lag −k lives at index fftSize − k (circular layout).
        var bestLag = 0
        var bestCorr = bufA[0]
        var sumC2 = bestCorr * bestCorr
        var lagCount = 1
        for (k in 1..maxLagSamples) {
            val pos = bufA[2 * k]
            sumC2 += pos * pos
            lagCount++
            if (pos > bestCorr) {
                bestCorr = pos
                bestLag = k
            }
            val neg = bufA[2 * (fftSize - k)]
            sumC2 += neg * neg
            lagCount++
            if (neg > bestCorr) {
                bestCorr = neg
                bestLag = -k
            }
        }

        val rmsCorr = sqrt(sumC2 / lagCount)
        val confidence = if (rmsCorr > 1e-12 && bestCorr > 0.0) {
            (bestCorr / (3.0 * rmsCorr)).coerceIn(0.0, 1.0).toFloat()
        } else 0f

        return LagResult(bestLag, confidence)
    }

    private fun zeroFill(buf: DoubleArray) {
        for (i in buf.indices) buf[i] = 0.0
    }

    companion object {
        /** 32768 covers up to 16384 real samples zero-padded with no wrap-around. */
        const val DEFAULT_FFT_SIZE = 32768
    }
}

data class LagResult(val lagSamples: Int, val confidence: Float)

# IMU-Aided Rotational Localization — Challenges & Open Issues

This document summarizes both attempts at using IMU rotation to recover 2D world-frame source direction from the phone's 1D mic-pair geometry, the empirical findings from each, and concrete debugging starting points for picking either approach back up.

The two approaches live on separate branches:
- **`imu-bayesian-belief`** — the cos-Bayesian belief distribution approach
- **`imu-vector-accumulator`** — the vector-summation approach

Both use the same upstream pipeline (dual-CAMCORDER capture, within-pair ILD as the long-axis signal, `RotationVectorProvider` for IMU yaw). They differ only in how per-window bias measurements are integrated over rotation into a world-frame source direction estimate.

---

## Why we're doing this

The S25 Ultra's mic geometry (1 mic at the bottom edge, 2 mics at the top edge ~2 mm apart) yields meaningful directional info on **only one axis** — the phone's long axis (top↔bottom). The short axis (left↔right) is geometrically unrecoverable from per-window mic-pair signals because there's no usable mic-pair baseline along that direction.

The rotational-aperture trick: as the user rotates the phone, the device's 1D-sensitive long axis sweeps through different world-frame headings. Each window's bias measurement combined with the phone's current yaw is one observation of `bias = f(sourceWorldAngle − phoneYaw)`. With enough rotation arc, multiple measurements can be combined to recover the source's world-frame direction.

Both approaches share the same input:
- **Bias signal**: `bot_ild` = within-pair RMS asymmetry of the bottom CAMCORDER stereo. Empirically captures long-axis source direction with ±0.6 dynamic range. Strong, AGC-resistant.
- **Yaw signal**: `Sensor.TYPE_ROTATION_VECTOR` → fused gyro+accel+mag yaw in [0, 360). Stable on this device, ~50 Hz update.

They differ only in how to fuse these into a single world-frame direction estimate.

---

## Empirical findings shared by both approaches

These hold regardless of which integrator you use:

1. **Long-axis signal is real and strong.** `bot_ild` swings 3–6× channel asymmetry with clear directional sources. Within-pair RMS asymmetry is the dominant useful directional signal on this hardware.

2. **The HAL sign convention is unknown a priori.** Samsung's CAMCORDER virtual stereo maps `bot_L`/`bot_R` to physically-distant mics, but the documentation doesn't specify which channel is the top mic vs the bottom mic. **Empirical testing in both approaches shows the sign is flipped relative to the natural assumption.** The fix in either branch is a single sign toggle (the cos-coefficient sign in Bayesian, or `flipSign = true` in VectorAccumulator). Validate on day one.

3. **Cos sensitivity model is approximately correct.** Single-source measurements roughly track `bias ≈ K · cos(sourceWorldAngle − phoneYaw)` for some K with unknown sign. Both approaches assume this; both could potentially benefit from a more sophisticated model (sin² weighting, asymmetric lobes from phone-body shadow, etc.) but cos is the right starting point.

4. **Per-window bias signal is sparse.** During real speech / music, only a fraction of windows carry strong directional info. Pauses, breath gaps, off-axis moments all produce weak `bot_ild`. Whatever integrator we use must handle this — silent windows shouldn't drag estimates around.

5. **Phone yaw measurement is reliable.** The fused rotation vector tracks phone heading well even during active rotation. Latency between IMU sample and audio window is small (yaw is captured at window emit time). This is *not* the source of localization error.

6. **Two-source antipodal scenarios fundamentally produce ambiguity.** When two sources alternate at opposite ends of the phone (like the user/friend test: user at BOT, friend at TOP), the bias signal honestly contains evidence for both directions. Any model has to make a choice: track the dominant source, blend, or report uncertainty. We never resolved this cleanly in either approach.

---

## Approach A: Cos-Bayesian Belief Distribution

**Branch:** `imu-bayesian-belief`
**Key class:** `app/src/main/java/com/echoai/domain/BeliefDistribution.kt`

### How it works

A 36-bin probability distribution over world-frame azimuth (10° per bin). Each window:

1. **Decay**: `bins ← bins · (1 − decayRate) + uniform · decayRate`. Forgets old evidence at configurable rate.
2. **Bayesian update**: for each bin's candidate world angle θ, compute expected bias = `±biasScale · cos(θ − phoneYaw)`. Likelihood = Gaussian over the residual `(measured − expected)`. Multiply bins by likelihoods.
3. **Renormalize**: divide by sum so distribution stays a probability.

Display: render as a halo around the radar perimeter (one arc segment per bin, opacity proportional to belief), with a brighter peak marker at the argmax.

### What worked

- Mathematically principled — the cos-Bayesian update is the textbook way to combine angular measurements.
- Halo visualization is information-rich: shows full distribution, ambiguities, multimodality.
- Works for verifying the rotational-aperture concept in principle.

### What didn't work / open issues

1. **Magnitude clipping.** The cos sensitivity model says `expected_bias ∈ [-biasScale, +biasScale]`. When `|bot_ild| > biasScale` (which happens in real test sessions — measurements up to 0.8 with `biasScale = 0.5`), no candidate fits well, every direction gets large residual, and the belief barely updates. **The strongest measurements — which should be most informative — contribute the least.** Possible fix: dynamically grow `biasScale` based on observed bias range, or switch to an asymmetric model that handles magnitudes outside the predicted range gracefully.

2. **Antipodal twin persistence.** Even with strong per-window updates, the belief tends to maintain two peaks 180° apart. Theoretically the Bayesian update *should* distinguish antipodal candidates because they predict opposite-sign bias. In practice the distributions stay double-peaked through entire sessions. Possible explanations:
   - **Decay too fast**: each update gets diluted before evidence accumulates. Counterargument: faster decay was supposed to *help* track the current dominant source, but it dilutes single-source evidence too.
   - **Decay too slow**: stale evidence from previous source positions lingers. Counterargument: the current decay rate (0.20/window = ~1 s half-life) is already aggressive.
   - **Reflection ambiguity at edges of rotation arc**: when the phone hasn't rotated through enough yaw range to disambiguate cos's reflection symmetry, multiple peaks coexist legitimately.
   - **Two sources alternating** (user/friend test) genuinely produces evidence for two directions.
   Hard to attribute without controlled single-source rotation tests.

3. **Sign convention ambiguity.** The cos coefficient sign was flipped once during debugging — empirical CSV histograms suggested `+biasScale × cos(...)` instead of the original `-biasScale × cos(...)`. The current branch state uses `+biasScale`. This may need to flip again depending on test conditions.

4. **Bin resolution tradeoff.** 36 bins (10° each) is a compromise. Finer (e.g., 5° = 72 bins) is more precise but slower to update and shows more noise in low-confidence regions. Coarser is more stable but limits angular resolution. Untested at different resolutions.

5. **Visualization complexity.** The halo correctly displays uncertainty and multimodality, but users found it confusing — "two opposite peaks" was reported even when the model was working as designed. The information density doesn't match user expectations of a "directional pointer."

### Specific debug starting points for this branch

- **Run a controlled single-source rotation test.** A speaker emitting a continuous tone at a known fixed world position. User stands still, holds phone flat, rotates slowly through 180°. CSV will show whether the belief converges on the source direction or stays multimodal. This isolates the ambiguity question from the two-source confusion.
- **Verify sign convention against ground truth.** With known source position, check `belief_peak_deg` direction. If 180° off, flip the sign.
- **Experiment with `biasScale` and `measurementSigma`.** Bigger sigma → softer updates, less collapse on noise; bigger biasScale → fits larger measurements but compresses sensitivity to small ones. Try grid search via CSV analysis: `biasScale ∈ {0.4, 0.6, 0.8, 1.0}` × `sigma ∈ {0.15, 0.25, 0.40}`.
- **Try clipping bias before update**: `bias.coerceIn(-biasScale, +biasScale)` before computing residual. Loses information about extreme measurements but might stabilize updates.
- **Try asymmetric model**: replace cos with a saturating function like `sign(x) · sqrt(|x|)` to handle extreme magnitudes more gracefully.

---

## Approach B: Vector Accumulator

**Branch:** `imu-vector-accumulator`
**Key class:** `app/src/main/java/com/echoai/domain/VectorAccumulator.kt`

### How it works

A 2D vector that accumulates per-window votes. Each window:

1. **Decay**: `(vx, vy) ← (vx, vy) · (1 − decayPerWindow)`.
2. **Vote**: `(vx, vy) += bias · (cos(phoneYaw), sin(phoneYaw))`.

Output: direction = `atan2(vy, vx)`, magnitude = `sqrt(vx² + vy²)`. Magnitude below a floor → return null direction (no estimate).

Math sanity: for a single source at world α with `bias = K · cos(α − yaw)`, integrating over a rotation, the accumulated vector points at α (or α+180° if K is negative — sign post-hoc calibration via `flipSign`).

Display: a single arrow from radar center pointing at the estimated world-frame direction, transformed into device frame using current yaw. Arrow length and opacity scale with magnitude. Below threshold: no arrow.

### What worked

- **No magnitude clipping.** Strong measurements contribute proportionally strong votes. Weak measurements contribute weak votes. No `biasScale` ceiling.
- **Silent windows auto-discount.** `bias ≈ 0` → `vote ≈ 0`. Doesn't drag the estimate.
- **Single output number** (magnitude) is cleaner UX than rendering a probability halo. Hide the arrow below threshold = honest "scanning..." display.
- **Mathematically simpler** — ~30 lines vs the Bayesian approach's ~100, easier to reason about.
- **Two-opposite-sources case naturally produces low magnitude**, not two competing peaks. Cleaner UX (system honestly says "uncertain") rather than ambiguously visualizing two candidates.

### What didn't work / open issues

1. **Sign is empirically flipped.** Last test session's `(pointer_dir - phone_yaw) mod 360` histogram showed strong clusters at 150° and 210° — pointer pointing at antipodal of true source consistently. **Fix is `flipSign = true` in `VectorAccumulator` constructor in MainActivity.** This was identified but not tested in the current branch state. **First action when picking up this branch: flip the sign, re-test.**

2. **Magnitude stays low.** In the latest test session, raw magnitude peaked at 0.81 (current floor: 0.30). Most frames hovered at 0.10–0.40. With normalization to [0, 1] using `pointerMagnitudeMax = 2.5`, the displayed arrow length stayed in the bottom third of its range. Possible causes:
   - **Decay too aggressive** (0.10/window = ~1.5 s half-life). The vector is decaying faster than measurements are being added. Try `decayPerWindow = 0.05`.
   - **Bias signal is weak in real speech.** Mostly silence and breath gaps, only occasional strong measurements. The accumulator can't reach high magnitude without sustained directional input. Real-world demo conditions might be improved with a sustained source (music, white noise from speaker) for testing.
   - **Sign-flip is also flipping votes.** If the bias signal is sign-flipped (per #1), the votes are pointing in the wrong direction — but they should still accumulate to *some* direction (the wrong one). Magnitude shouldn't drop, but the alternation between user/friend talking *would* make votes partially cancel.

3. **No graceful handling of source changes.** When the source moves or alternates with another at a different position, the accumulator integrates evidence from both. Magnitude drops, direction becomes unstable. The decay-based forgetting is the only mechanism. Compare to a state-space model (Kalman filter, particle filter) that could explicitly model "dominant source might switch."

4. **No confidence interval.** Magnitude is a single number; we don't know how concentrated the votes were vs how broadly distributed. A direction with magnitude 0.5 from votes spread across all yaws is less reliable than the same magnitude from votes concentrated on a 60° arc — but the math doesn't distinguish these. Could add a "concentration" metric (e.g., `1 - variance of vote angles weighted by magnitude`).

5. **Magnitude scaling is empirical.** The `pointerMagnitudeMax = 2.5` constant was chosen by intuition. Real range varies by source strength, rotation speed, decay rate. Could be auto-calibrated from rolling max-magnitude in the session.

### Specific debug starting points for this branch

- **First, flip the sign.** `flipSign = true` in `VectorAccumulator()` constructor in `MainActivity.kt`. Re-test. This single change should resolve "pointer points at wrong side" observation.
- **Slower decay.** Try `decayPerWindow = 0.05` (about 3 s half-life) so the accumulator has more time to build up evidence per session.
- **Magnitude calibration.** Run a controlled rotation with known sustained source (your phone's own speaker playing a tone), record the resulting raw magnitude. Use that as the empirical max for `pointerMagnitudeMax` rather than guessing.
- **Add a concentration metric.** When you compute the accumulated vector, also track the second-moment direction spread. Display both magnitude and concentration to better evaluate convergence quality.
- **Try a Kalman-style state model.** Track `(α, σ_α)` as estimated direction + uncertainty, update on each measurement with the appropriate likelihood. More principled than naive vector summation.

---

## Recommended sequence for whichever branch you pick up

1. **Run the same controlled test in both branches** for fair comparison: one fixed source (phone speaker on a table playing music), user holding phone flat, rotating ~180°. Capture CSV.

2. **Verify sign convention.** Check whether the inferred direction matches the known source position. If 180° off, flip the sign. Both branches have a one-place-to-flip. **Always do this first.**

3. **Identify failure modes.** Where does the model break? Magnitude too low? Direction too noisy? Too slow to converge? Different branches will have different weaknesses; pick the one where the failures look most fixable.

4. **Calibrate constants from real data.** Don't tune by intuition — pull CSV, look at distributions, set thresholds against actual signal range.

5. **Compare against ground truth.** A speaker at known position is the only honest evaluation. User-perceived quality of "left/right" is too dependent on sign convention and visual scaling to be diagnostic alone.

---

## Things both approaches don't address

- **Source moving in world frame.** Both approaches assume stationary source. A moving source produces stale evidence at old positions. Could be addressed with a Kalman filter or by explicitly modeling source velocity.
- **Multiple simultaneous sources.** Both produce confused output (dual peaks in Bayesian, low magnitude in vector). True multi-source localization needs source separation upstream — the team's deep-learning approach is the right place for this, not the GCC-PHAT-style baseline.
- **Front-back ambiguity for sources outside the rotation arc.** If you only rotate 90°, the model can't distinguish a source at +45° from one at -45° from your starting heading. Wider rotation always helps but is user-effort-dependent.
- **Drift between IMU and audio.** We snapshot yaw at window emit time. If the phone is rotating fast within a window, the bias measurement is averaged over multiple yaws but attributed to one yaw value. Slow rotation mitigates this; fast rotation degrades both approaches equally.

---

## Pipeline state common to both branches

Files outside the IMU integrator that are unchanged between branches:
- `audio/AudioCaptureManager.kt` — dual CAMCORDER capture
- `audio/GccPhatLocalizer.kt` — within-pair ILD via cross-correlation
- `pipeline/LocalizationStage.kt` — multi-scale localization, fb_bias, ILD computation
- `pipeline/ClassificationStage.kt` — single-pass YAMNet on 4-channel mono downmix
- `pipeline/FusionStage.kt` — top-1 attribution + EventTracker
- `ml/YamnetClassifier.kt` — TFLite + NNAPI delegate
- `sensor/RotationVectorProvider.kt` — IMU listener, yaw extraction
- `sensor/WorldOrientationProvider.kt` — interface + null impl
- `diagnostics/DiagnosticsLogger.kt` — CSV logger (column names differ slightly between branches)

The `RadarView`, `MainActivity`, and the integrator class differ between branches. Everything else is identical.

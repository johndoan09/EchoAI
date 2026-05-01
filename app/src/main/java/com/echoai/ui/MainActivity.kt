package com.echoai.ui

import android.Manifest
import android.app.ActivityOptions
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.echoai.R
import com.echoai.audio.AudioCaptureManager
import com.echoai.audio.AudioWindow
import com.echoai.databinding.ActivityMainBinding
import com.echoai.databinding.SheetProfileMessageBinding
import com.echoai.databinding.SheetRenameProfileBinding
import com.echoai.databinding.SheetProfileOptionsBinding
import com.echoai.diagnostics.DiagnosticsLogger
import com.echoai.domain.BeliefDistribution
import com.echoai.domain.ClassificationResult
import com.echoai.domain.EventTracker
import com.echoai.domain.PinnedAlertTracker
import com.echoai.domain.ProfileManager
import com.echoai.domain.SoundEvent
import com.echoai.domain.SoundProfile
import com.echoai.domain.SoundHistoryManager
import com.echoai.domain.UrgencyClassifier
import com.echoai.ml.SoundClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.pipeline.FusionStage
import com.echoai.pipeline.LocalizationStage
import com.echoai.sensor.RotationVectorProvider
import com.echoai.util.HapticManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pinnedAdapter: PinnedAlertAdapter
    private lateinit var sceneChipAdapter: SceneChipAdapter

    private var pendingStart = false
    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startLive()
        else if (!granted) binding.statusText.text = getString(R.string.mic_permission_denied)
        pendingStart = false
    }

    // --- Pipeline (localization + IMU stack from imu-bayesian-belief, profile-aware fusion from main) ---

    private val orientationProvider by lazy { RotationVectorProvider(applicationContext) }
    private val captureManager by lazy {
        AudioCaptureManager(applicationContext, orientationProvider)
    }
    private val classifier: SoundClassifier by lazy {
        YamnetClassifier.create(applicationContext) ?: StubSoundClassifier()
    }
    private val classificationStage by lazy { ClassificationStage(classifier) }
    private val localizationStage = LocalizationStage()
    private val urgencyClassifier by lazy { UrgencyClassifier(applicationContext) }
    private val hapticManager by lazy { HapticManager(applicationContext) }
    private val fusionStage by lazy {
        FusionStage(EventTracker(urgencyClassifier = urgencyClassifier))
    }
    private val profileManager by lazy { ProfileManager(applicationContext) }
    private val historyManager by lazy { SoundHistoryManager(applicationContext) }
    private val pinnedAlertTracker by lazy { PinnedAlertTracker(applicationContext) }

    // One BeliefDistribution per YAMNet label. Each window's `bot_ild` + yaw is routed to
    // the top-1 label's belief; everyone else decays slightly. Over time each label
    // converges independently to its source's world-frame direction. Positive bot_ild =
    // source toward BOTTOM of phone (deviceAngle ≈ 180°), so biasScale is negative so
    // that cos(180°)*biasScale > 0. decayRate = 0.01 per update at 8 Hz ≈ 0.08/s.
    private val beliefMap = mutableMapOf<String, BeliefDistribution>()

    private fun newBelief() = BeliefDistribution(
        biasScale = -0.5f,
        measurementSigma = 0.25f,
        decayRate = 0.01f,
    )

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var pinnedSectionVisible = false
    private var rotateHintVisible = false
    private var diagnosticsLogger: DiagnosticsLogger? = null

    // Classification cache: at 8 Hz localization / 2 Hz classification, every 4th window
    // runs YAMNet and the others reuse the most-recent classification result. The reused
    // result is at most 3 hops (~375 ms) old — well within EventTracker.staleAfterNanos (3 s).
    private var lastClassification: ClassificationResult? = null
    private var classificationFrameCounter = 0

    // Yaw history for rotation-rate tracking (recent samples, capped by time window).
    private val yawHistory = ArrayDeque<Pair<Long, Float>>(24)
    private var rotateHintFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSystemBarInsets()

        renderWordmark()

        pinnedAdapter = PinnedAlertAdapter(
            onDismiss = { alert ->
                pinnedAlertTracker.acknowledge(alert.label, alert.urgency)
                refreshPinnedSection()
            },
            onTap = { label ->
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra(HistoryActivity.EXTRA_HIGHLIGHT_LABEL, label)
                })
            },
        )
        binding.pinnedAlertsList.layoutManager = LinearLayoutManager(this)
        binding.pinnedAlertsList.adapter = pinnedAdapter

        binding.liveToggle.setOnClickListener { onLiveToggle() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.clearAllButton.setOnClickListener {
            pinnedAlertTracker.acknowledgeAll()
            refreshPinnedSection()
        }
        binding.tabListening.setOnClickListener { /* already here */ }
        binding.tabProfile.setOnClickListener { openProfileWithoutAnimation() }

        setupSceneChips()

        // No alerts on launch — start with the expanded button state immediately (no animation)
        applyLiveToggleState(expanded = true, animate = false)
        refreshPinnedSection(animate = false)

        observeProfiles()
    }

    private fun setupSceneChips() {
        sceneChipAdapter = SceneChipAdapter(
            onChipClick = { showProfileOptionsSheet(it) },
            onAddClick = {
                CreateProfileSheet.show(this) { name ->
                    val profile = profileManager.createProfile(name)
                    profileManager.setActiveProfileId(profile.id)
                }
            },
            onDragStart = { holder -> chipTouchHelper.startDrag(holder) },
        )

        binding.sceneChipRow.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(
                this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
            )
        binding.sceneChipRow.adapter = sceneChipAdapter

        chipTouchHelper.attachToRecyclerView(binding.sceneChipRow)
    }

    private val chipTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(
        object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) = if (holder.itemViewType == SceneChipAdapter.TYPE_ADD) 0
                else makeMovementFlags(
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                        androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
                )

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ): Boolean {
                if (to.itemViewType == SceneChipAdapter.TYPE_ADD) return false
                sceneChipAdapter.moveItem(from.adapterPosition, to.adapterPosition)
                return true
            }

            override fun onSwiped(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int
            ) {}

            override fun onSelectedChanged(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    holder?.itemView?.alpha = 0.75f
                }
            }

            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) {
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
                profileManager.reorderProfiles(sceneChipAdapter.orderedIds())
            }
        }
    )

    private fun installSystemBarInsets() {
        val rootStartTop = binding.root.paddingTop
        val rootStartLeft = binding.root.paddingLeft
        val rootStartRight = binding.root.paddingRight
        val tabStartBottom = binding.bottomTabBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootStartLeft + systemBars.left,
                top = rootStartTop + systemBars.top,
                right = rootStartRight + systemBars.right,
            )
            binding.bottomTabBar.updatePadding(
                bottom = tabStartBottom + ((systemBars.bottom * 3) / 4)
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStop() {
        super.onStop()
        if (liveActive) stopLive()
    }

    override fun onResume() {
        super.onResume()
        profileManager.refreshFromStorage()
    }

    private fun renderWordmark() {
        val raw = getString(R.string.app_name_html)
        binding.wordmark.text = if (Build.VERSION.SDK_INT >= 24)
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
        else
            @Suppress("DEPRECATION") Html.fromHtml(raw)
    }

    private fun openProfileWithoutAnimation() {
        val intent = Intent(this, ProfileActivity::class.java).apply {
            putExtra(ProfileActivity.EXTRA_PROFILE_ID, profileManager.activeProfile.value.id)
        }
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
    }

    // --- Pinned alerts ---

    private fun refreshPinnedSection(animate: Boolean = true) {
        val alerts = pinnedAlertTracker.snapshot()
        updatePinnedAlertListHeight(alerts.size)
        pinnedAdapter.submitList(alerts)
        val nowVisible = alerts.isNotEmpty()
        binding.pinnedAlertsSection.visibility = if (nowVisible) View.VISIBLE else View.GONE
        if (nowVisible != pinnedSectionVisible) {
            pinnedSectionVisible = nowVisible
            applyLiveToggleState(expanded = !nowVisible, animate = animate)
        }
    }

    private fun updatePinnedAlertListHeight(alertCount: Int) {
        val targetHeight = if (alertCount > PINNED_ALERT_SCROLL_THRESHOLD) {
            (PINNED_ALERT_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val params = binding.pinnedAlertsList.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.pinnedAlertsList.layoutParams = params
        }
        binding.pinnedAlertsList.isNestedScrollingEnabled = alertCount > PINNED_ALERT_SCROLL_THRESHOLD
    }

    private fun setRotateHintVisible(visible: Boolean, animate: Boolean = true) {
        if (rotateHintVisible == visible && binding.rotateHint.visibility == if (visible) View.VISIBLE else View.GONE) {
            return
        }
        rotateHintVisible = visible
        binding.rotateHint.visibility = if (visible) View.VISIBLE else View.GONE
        applyLiveToggleState(expanded = !pinnedSectionVisible, animate = animate)
    }

    private fun applyLiveToggleState(expanded: Boolean, animate: Boolean) {
        val dp = resources.displayMetrics.density
        val targetBtnPadH = ((if (rotateHintVisible) 16 else if (expanded) 34 else 24) * dp).toInt()
        val targetBtnPadV = ((if (rotateHintVisible) 4 else 12) * dp).toInt()
        val targetContainerPadBottom = ((if (rotateHintVisible) 0 else if (expanded) 8 else 2) * dp).toInt()
        val targetTextSize = if (rotateHintVisible) 13f else 15f
        binding.liveToggle.textSize = targetTextSize

        if (animate) {
            val fromPadH = binding.liveToggle.paddingLeft
            val fromPadV = binding.liveToggle.paddingTop
            val fromContainerPad = binding.liveToggleContainer.paddingBottom

            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedFraction
                    val padH = (fromPadH + (targetBtnPadH - fromPadH) * t).toInt()
                    val padV = (fromPadV + (targetBtnPadV - fromPadV) * t).toInt()
                    val contPad = (fromContainerPad + (targetContainerPadBottom - fromContainerPad) * t).toInt()
                    binding.liveToggle.setPadding(padH, padV, padH, padV)
                    binding.liveToggleContainer.setPadding(
                        binding.liveToggleContainer.paddingLeft,
                        binding.liveToggleContainer.paddingTop,
                        binding.liveToggleContainer.paddingRight,
                        contPad,
                    )
                }
                start()
            }
        } else {
            binding.liveToggle.setPadding(targetBtnPadH, targetBtnPadV, targetBtnPadH, targetBtnPadV)
            binding.liveToggleContainer.setPadding(
                binding.liveToggleContainer.paddingLeft,
                binding.liveToggleContainer.paddingTop,
                binding.liveToggleContainer.paddingRight,
                targetContainerPadBottom,
            )
        }
    }

    // --- Profile chips ---

    private fun observeProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.allProfiles.collect { profiles ->
                    sceneChipAdapter.submitProfiles(profiles, profileManager.activeProfile.value.id)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.activeProfile.collect { profile ->
                    fusionStage.applyProfile(profile)
                    sceneChipAdapter.setActiveId(profile.id)
                }
            }
        }
    }

    private fun showProfileOptionsSheet(profile: SoundProfile) {
        val dialog = BottomSheetDialog(this, R.style.Theme_EchoAI_BottomSheet)
        val sheet = SheetProfileOptionsBinding.inflate(LayoutInflater.from(this))
        val active = profile.id == profileManager.activeProfile.value.id

        sheet.profileNameText.text = profile.name
        sheet.profileStatusText.text = if (active) {
            getString(R.string.current_profile)
        } else {
            getString(R.string.tap_hold_to_reorder)
        }
        sheet.useProfileButton.visibility = if (active) View.GONE else View.VISIBLE
        sheet.useProfileButton.setOnClickListener {
            profileManager.setActiveProfileId(profile.id)
            dialog.dismiss()
        }
        sheet.renameButton.setOnClickListener {
            dialog.dismiss()
            showRenameProfileDialog(profile)
        }
        sheet.deleteButton.setOnClickListener {
            dialog.dismiss()
            showDeleteProfileDialog(profile)
        }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun showRenameProfileDialog(profile: SoundProfile) {
        val dialog = BottomSheetDialog(this, R.style.Theme_EchoAI_BottomSheet)
        val sheet = SheetRenameProfileBinding.inflate(LayoutInflater.from(this))

        sheet.currentNameText.text = profile.name
        sheet.profileNameInput.setText(profile.name)
        sheet.profileNameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        sheet.profileNameInput.selectAll()

        sheet.cancelButton.setOnClickListener { dialog.dismiss() }
        sheet.renameButton.setOnClickListener {
            val name = sheet.profileNameInput.text.toString().trim()
            if (name.isNotEmpty()) {
                profileManager.renameProfile(profile.id, name)
                dialog.dismiss()
            }
        }

        dialog.setContentView(sheet.root)
        dialog.show()

        sheet.profileNameInput.requestFocus()
        sheet.profileNameInput.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(sheet.profileNameInput, InputMethodManager.SHOW_IMPLICIT)
            sheet.profileNameInput.selectAll()
        }
    }

    private fun showDeleteProfileDialog(profile: SoundProfile) {
        if (!profileManager.canDeleteProfile(profile.id)) {
            showProfileMessageSheet(
                title = getString(R.string.delete_last_profile_title),
                message = getString(R.string.delete_last_profile_message),
            )
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_profile_title, profile.name))
            .setMessage(getString(R.string.delete_profile_message))
            .setPositiveButton(getString(R.string.delete_profile)) { _, _ ->
                profileManager.deleteProfile(profile.id)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showProfileMessageSheet(title: String, message: String) {
        val dialog = BottomSheetDialog(this, R.style.Theme_EchoAI_BottomSheet)
        val sheet = SheetProfileMessageBinding.inflate(LayoutInflater.from(this))

        sheet.messageTitleText.text = title
        sheet.messageBodyText.text = message
        sheet.doneButton.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    // --- Live pipeline ---

    private fun onLiveToggle() {
        if (liveActive) {
            stopLive()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startLive()
            else { pendingStart = true; requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLive() {
        if (liveActive) return
        liveActive = true

        binding.liveToggle.text = getString(R.string.stop_live)
        binding.liveToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_pause, 0, 0, 0
        )
        binding.statusText.text = getString(R.string.live_starting)
        binding.radarView.setListening(true)

        diagnosticsLogger = DiagnosticsLogger.start(applicationContext)
        beliefMap.clear()
        lastClassification = null
        classificationFrameCounter = 0
        yawHistory.clear()
        rotateHintFrames = 0
        setRotateHintVisible(false, animate = false)
        orientationProvider.start()
        binding.radarView.setOrientationProvider(orientationProvider)

        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val frame = processWindow(window)
                    val halos = frame.events.mapNotNull { event ->
                        val b = frame.beliefsByLabel[event.label] ?: return@mapNotNull null
                        RadarView.LabelHalo(
                            label = event.label,
                            snapshot = b.snapshot,
                            peakWorldAngle = b.peakWorldAngle,
                            urgencyColor = RadarView.urgencyColor(event.urgency),
                        )
                    }
                    binding.radarView.setData(
                        events = frame.events,
                        halos = halos,
                        phoneYawDegrees = frame.phoneYaw,
                    )
                    setRotateHintVisible(frame.showRotateHint)
                    hapticManager.vibrateForHighest(frame.events)
                    updateStatus(frame.events)
                    pinnedAlertTracker.onEvents(frame.events)
                    historyManager.logHighUrgencyEvents(
                        frame.events, profileManager.activeProfile.value.name
                    )
                    withContext(Dispatchers.Main) { refreshPinnedSection() }
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
        binding.radarView.setOrientationProvider(null)
        orientationProvider.stop()
        diagnosticsLogger?.close()
        diagnosticsLogger = null
        pipelineJob?.cancel()
        pipelineJob = null

        binding.liveToggle.text = getString(R.string.start_live)
        binding.liveToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_mic, 0, 0, 0
        )
        binding.statusText.text = getString(R.string.live_idle)
        binding.radarView.setListening(false)
        binding.radarView.setEvents(emptyList())
        setRotateHintVisible(false, animate = false)

        val err = captureManager.lastErrorMessage()
        if (err != null) binding.statusText.text = "Stopped — error: $err"
    }

    private data class LabelBelief(
        val snapshot: FloatArray,
        val peakWorldAngle: Float,
        val intensity: Float,
    )

    private data class Frame(
        val events: List<SoundEvent>,
        val beliefsByLabel: Map<String, LabelBelief>,
        val phoneYaw: Float,
        val showRotateHint: Boolean,
    )

    private suspend fun processWindow(window: AudioWindow): Frame = coroutineScope {
        // YAMNet runs every Nth window; localization runs every window. Between
        // classifications we reuse the cached result (the dominant source rarely
        // changes within ~250 ms, so this is a safe assumption for stage-(a) attribution).
        val localizeJob = async(Dispatchers.Default) { localizationStage.localizeMultiScale(window) }
        val shouldClassify = (classificationFrameCounter % CLASSIFY_EVERY_N) == 0
        classificationFrameCounter++
        val classification = if (shouldClassify) {
            val fresh = withContext(Dispatchers.Default) { classificationStage.classify(window) }
            lastClassification = fresh
            fresh
        } else {
            lastClassification ?: ClassificationResult(window.frameNumber, emptyList())
        }
        val multi = localizeJob.await()
        val events = withContext(Dispatchers.Default) {
            fusionStage.process(classification, multi.full)
        }
        val devicePos = events.firstOrNull()?.devicePosition
        val azX = devicePos?.azimuthFromBottomIld()
        val azLag = devicePos?.azimuthFromLag()

        // Rotational-aperture localizer update. Only feed the belief when (a) the IMU has
        // produced a sample and (b) YAMNet detected a sound with reasonable confidence —
        // updating during silence pushes phantom peaks toward the broadside cone.
        // Use the 250 ms peak-energy sub-window's ILD: it changes more between consecutive
        // emissions than the full-window ILD (consecutive 1 s windows share 87.5% of audio
        // at 8 Hz, so their full-window ILDs are highly correlated).
        val yaw = orientationProvider.yawDegrees()
        // YAMNet's 42 consolidated groups are independent sigmoids (top-k noisy-OR, see
        // yamnet_consolidation_map.json) — one real source often fires several related
        // labels (Speech + Shouting/Yelling, Dog + Animal, …). Feeding this window's ILD
        // + yaw to every confident label pools evidence across siblings instead of
        // splitting it top-1-only, which the single-belief version effectively did for
        // free. Silence/no-IMU windows fall through to the shared silence decay.
        val activeLabels = classification.topK.filter {
            it.confidence > BELIEF_UPDATE_THRESHOLD &&
                !it.label.equals(SILENCE_LABEL, ignoreCase = true)
        }
        val hasConfidentAudio = activeLabels.isNotEmpty()
        if (yaw != null && hasConfidentAudio) {
            for (entry in activeLabels) {
                val bd = beliefMap.getOrPut(entry.label) { newBelief() }
                bd.update(multi.sub.bottomIld, yaw)
            }
        } else {
            // Silence (or no IMU sample yet) — fade every belief toward uniform so stale
            // peaks don't linger. Non-winner decay during audio-active windows is handled
            // naturally by BeliefDistribution.update's internal decay step the next time
            // the label fires, plus the prune pass below.
            for ((_, bd) in beliefMap) bd.decayOnly(SILENCE_DECAY_RATE)
        }

        // Evict beliefs that have effectively returned to uniform so the map stays bounded.
        beliefMap.entries.removeAll { (_, bd) ->
            val uniform = 1f / bd.snapshot().size
            bd.maxBelief() <= uniform * BELIEF_PRUNE_THRESHOLD
        }

        // Rotation hint: only nudges the user when there's an active sound but the phone
        // isn't moving — without rotation the rotational-aperture localizer can't pin down
        // a world-frame direction. Stays hidden during silence.
        recordYaw(window.captureTimestampNanos, yaw)
        val lowRotation = cumulativeRotationDegrees() < LOW_ROTATION_DEG
        if (hasConfidentAudio && lowRotation) rotateHintFrames++ else rotateHintFrames = 0
        val showRotateHint = rotateHintFrames >= ROTATE_HINT_DEBOUNCE_FRAMES

        val beliefsByLabel = beliefMap.mapValues { (_, bd) ->
            LabelBelief(bd.snapshot(), bd.smoothedPeakDegrees(), bd.maxBelief())
        }
        val topEventBelief = events.firstOrNull()?.let { beliefsByLabel[it.label] }

        diagnosticsLogger?.log(
            window = window,
            topLabel = classification.topK.firstOrNull(),
            full = multi.full,
            sub = multi.sub,
            azimuthIldDeg = azX,
            azimuthLagDeg = azLag,
            phoneYawDeg = yaw,
            beliefPeakDeg = topEventBelief?.peakWorldAngle,
            beliefIntensity = topEventBelief?.intensity,
        )

        Frame(
            events = events,
            beliefsByLabel = beliefsByLabel,
            phoneYaw = yaw ?: 0f,
            showRotateHint = showRotateHint,
        )
    }

    private fun recordYaw(timestampNanos: Long, yawDeg: Float?) {
        if (yawDeg == null) return
        yawHistory.addLast(timestampNanos to yawDeg)
        val cutoff = timestampNanos - YAW_WINDOW_NANOS
        while (yawHistory.isNotEmpty() && yawHistory.first().first < cutoff) yawHistory.removeFirst()
    }

    /** Sum of absolute yaw deltas across the retained history, with shortest-arc wrapping. */
    private fun cumulativeRotationDegrees(): Float {
        if (yawHistory.size < 2) return 0f
        var total = 0f
        val iter = yawHistory.iterator()
        var prev = iter.next().second
        while (iter.hasNext()) {
            val cur = iter.next().second
            var d = cur - prev
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            total += kotlin.math.abs(d)
            prev = cur
        }
        return total
    }

    private fun updateStatus(events: List<SoundEvent>) {
        binding.statusText.text = when {
            events.isEmpty() -> getString(R.string.listening_no_sounds)
            events.size == 1 -> getString(R.string.listening_one_sound, events.first().label)
            else -> getString(R.string.listening_n_sounds, events.size)
        }
    }

    companion object {
        /** Run YAMNet on every Nth pipeline window. With HOP_FRAMES = 2000 (8 Hz), N=4
         *  gives 2 Hz classification while localization + belief update runs at full 8 Hz. */
        private const val CLASSIFY_EVERY_N = 4
        /** Decay rate applied per silent window. 10× the normal 0.01 in BeliefDistribution
         *  → ~57%/s convergence to uniform at 8 Hz, so the halo fades within ~2 s of silence. */
        private const val SILENCE_DECAY_RATE = 0.10f
        /** YAMNet class 494 / stub classifier label that means "no sound to localize". */
        private const val SILENCE_LABEL = "Silence"
        /** Prune a belief once `maxBelief <= uniform * THRESHOLD` — it's effectively flat.
         *  Mirrors the staleness criterion from EventTracker.evictStale. */
        private const val BELIEF_PRUNE_THRESHOLD = 1.10f
        /** Minimum confidence for a YAMNet label to contribute a belief update. Applied
         *  to every entry in `classification.topK`, not just the top-1, so siblings that
         *  fire together (Speech + Shouting/Yelling, …) pool evidence for the same source. */
        private const val BELIEF_UPDATE_THRESHOLD = 0.3f
        /** Sliding window over which cumulative yaw change is summed for the rotate hint. */
        private const val YAW_WINDOW_NANOS = 2_000_000_000L
        /** Below this cumulative rotation over [YAW_WINDOW_NANOS], the phone counts as "still". */
        private const val LOW_ROTATION_DEG = 15f
        /** Consecutive frames the (audio + still) condition must hold before showing the hint
         *  (~1.5 s at 8 Hz). Hides immediately when either condition flips. */
        private const val ROTATE_HINT_DEBOUNCE_FRAMES = 12
        private const val PINNED_ALERT_SCROLL_THRESHOLD = 3
        private const val PINNED_ALERT_MAX_HEIGHT_DP = 220
    }
}

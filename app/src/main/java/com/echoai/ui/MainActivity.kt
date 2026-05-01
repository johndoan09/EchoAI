package com.echoai.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.animation.DecelerateInterpolator
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
import com.echoai.util.HapticManager
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

    private var pendingStart = false
    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startLive()
        else if (!granted) binding.statusText.text = getString(R.string.mic_permission_denied)
        pendingStart = false
    }

    private val captureManager by lazy { AudioCaptureManager(applicationContext) }
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

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var pinnedSectionVisible = false

    private lateinit var sceneChipAdapter: SceneChipAdapter

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
        binding.tabProfile.setOnClickListener {
            startActivity(
                Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_PROFILE_ID, profileManager.activeProfile.value.id)
                }
            )
        }

        setupSceneChips()

        // No alerts on launch — start with the expanded button state immediately (no animation)
        applyLiveToggleState(expanded = true, animate = false)
        refreshPinnedSection(animate = false)

        observeProfiles()
    }

    private fun setupSceneChips() {
        sceneChipAdapter = SceneChipAdapter(
            onChipClick = { profileManager.setActiveProfileId(it.id) },
            onChipLongClick = { showDeleteProfileDialog(it) },
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
                bottom = tabStartBottom + systemBars.bottom
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

    // --- Pinned alerts ---

    private fun refreshPinnedSection(animate: Boolean = true) {
        val alerts = pinnedAlertTracker.snapshot()
        pinnedAdapter.submitList(alerts)
        val nowVisible = alerts.isNotEmpty()
        binding.pinnedAlertsSection.visibility = if (nowVisible) View.VISIBLE else View.GONE
        if (nowVisible != pinnedSectionVisible) {
            pinnedSectionVisible = nowVisible
            applyLiveToggleState(expanded = !nowVisible, animate = animate)
        }
    }

    private fun applyLiveToggleState(expanded: Boolean, animate: Boolean) {
        val dp = resources.displayMetrics.density
        val targetBtnPadH = ((if (expanded) 46 else 24) * dp).toInt()
        val targetBtnPadV = ((if (expanded) 21 else 12) * dp).toInt()
        val targetContainerPadBottom = ((if (expanded) 32 else 2) * dp).toInt()

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

    private fun showDeleteProfileDialog(profile: SoundProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This will remove the profile and all its custom settings.")
            .setPositiveButton("Delete") { _, _ -> profileManager.deleteProfile(profile.id) }
            .setNegativeButton("Cancel", null)
            .show()
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
        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val events = processWindow(window)
                    binding.radarView.setEvents(events)
                    hapticManager.vibrateForHighest(events)
                    updateStatus(events)
                    pinnedAlertTracker.onEvents(events)
                    historyManager.logHighUrgencyEvents(
                        events, profileManager.activeProfile.value.name
                    )
                    withContext(Dispatchers.Main) { refreshPinnedSection() }
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        binding.liveToggle.text = getString(R.string.start_live)
        binding.liveToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_mic, 0, 0, 0
        )
        binding.statusText.text = getString(R.string.live_idle)
        binding.radarView.setListening(false)
        binding.radarView.setEvents(emptyList())
        val err = captureManager.lastErrorMessage()
        if (err != null) binding.statusText.text = "Stopped — error: $err"
    }

    private suspend fun processWindow(window: AudioWindow): List<SoundEvent> = coroutineScope {
        val classifyJob = async(Dispatchers.Default) { classificationStage.classify(window) }
        val localizeJob = async(Dispatchers.Default) { localizationStage.localizeMultiScale(window) }
        val classification = classifyJob.await()
        val localization = localizeJob.await()
        withContext(Dispatchers.Default) {
            fusionStage.process(classification, localization.full)
        }
    }

    private fun updateStatus(events: List<SoundEvent>) {
        binding.statusText.text = when {
            events.isEmpty() -> getString(R.string.listening_no_sounds)
            events.size == 1 -> getString(R.string.listening_one_sound, events.first().label)
            else -> getString(R.string.listening_n_sounds, events.size)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

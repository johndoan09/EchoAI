package com.echoai.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.echoai.domain.UrgencyClassifier
import com.echoai.ml.SoundClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.pipeline.FusionStage
import com.echoai.pipeline.LocalizationStage
import com.echoai.util.HapticManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var eventAdapter: SoundEventAdapter
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
    private val pinnedAlertTracker = PinnedAlertTracker()

    private var pipelineJob: Job? = null
    private var liveActive = false

    private val chipMap = mutableMapOf<String, Chip>()
    private var suppressChipListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eventAdapter = SoundEventAdapter()
        binding.eventsList.layoutManager = LinearLayoutManager(this)
        binding.eventsList.adapter = eventAdapter

        pinnedAdapter = PinnedAlertAdapter(onDismiss = { label ->
            pinnedAlertTracker.acknowledge(label)
            refreshPinnedSection()
        })
        binding.pinnedAlertsList.layoutManager = LinearLayoutManager(this)
        binding.pinnedAlertsList.adapter = pinnedAdapter

        binding.liveToggle.setOnClickListener { onLiveToggle() }
        binding.editProfileButton.setOnClickListener { openProfileEditor() }

        binding.profileChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipListener) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val profileId = chipMap.entries.firstOrNull { it.value.id == chipId }?.key
                ?: return@setOnCheckedStateChangeListener
            profileManager.setActiveProfileId(profileId)
        }

        observeProfiles()
    }

    override fun onStop() {
        super.onStop()
        if (liveActive) stopLive()
    }

    // --- Missed alerts ---

    private fun refreshPinnedSection() {
        val alerts = pinnedAlertTracker.snapshot()
        pinnedAdapter.submitList(alerts)
        val visible = alerts.isNotEmpty()
        binding.missedAlertsHeader.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pinnedAlertsList.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // --- Profile chips ---

    private fun observeProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.allProfiles.collect { profiles -> rebuildChips(profiles) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.activeProfile.collect { profile ->
                    fusionStage.applyProfile(profile)
                    syncChipSelection(profile.id)
                }
            }
        }
    }

    private fun rebuildChips(profiles: List<SoundProfile>) {
        suppressChipListener = true
        binding.profileChipGroup.removeAllViews()
        chipMap.clear()
        val activeId = profileManager.activeProfile.value.id
        for (profile in profiles) {
            val chip = makeFilterChip(profile.name).apply {
                isChecked = profile.id == activeId
                if (!profile.isPreset) {
                    setOnLongClickListener { showDeleteProfileDialog(profile); true }
                }
            }
            binding.profileChipGroup.addView(chip)
            chipMap[profile.id] = chip
        }
        binding.profileChipGroup.addView(makeActionChip("+").apply {
            setOnClickListener { showCreateProfileDialog() }
        })
        suppressChipListener = false
    }

    private fun syncChipSelection(activeId: String) {
        suppressChipListener = true
        chipMap[activeId]?.let { binding.profileChipGroup.check(it.id) }
        suppressChipListener = false
    }

    private fun makeFilterChip(label: String): Chip =
        Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
        }

    private fun makeActionChip(label: String): Chip =
        Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
            id = View.generateViewId()
            text = label
            isCheckable = false
        }

    private fun showCreateProfileDialog() {
        val editText = EditText(this).apply { hint = "Profile name"; setSingleLine() }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        AlertDialog.Builder(this)
            .setTitle("New Profile")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val profile = profileManager.createProfile(name)
                    profileManager.setActiveProfileId(profile.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteProfileDialog(profile: SoundProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This will remove the profile and all its custom settings.")
            .setPositiveButton("Delete") { _, _ -> profileManager.deleteProfile(profile.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openProfileEditor() {
        startActivity(
            Intent(this, ProfileActivity::class.java).apply {
                putExtra(ProfileActivity.EXTRA_PROFILE_ID, profileManager.activeProfile.value.id)
            }
        )
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
        binding.statusText.text = getString(R.string.live_starting)
        binding.eventsHeader.visibility = View.VISIBLE
        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val events = processWindow(window)
                    eventAdapter.submitList(events)
                    hapticManager.vibrateForHighest(events)
                    updateStatus(events)
                    pinnedAlertTracker.onEvents(events)
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
        binding.statusText.text = getString(R.string.live_idle)
        binding.eventsHeader.visibility = View.GONE
        eventAdapter.submitList(emptyList())
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
}

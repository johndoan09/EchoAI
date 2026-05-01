package com.echoai.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val pinnedAlertTracker = PinnedAlertTracker()

    private var pipelineJob: Job? = null
    private var liveActive = false

    private val sceneChips = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSystemBarInsets()

        renderWordmark()

        pinnedAdapter = PinnedAlertAdapter(
            onDismiss = { label ->
                pinnedAlertTracker.acknowledge(label)
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
            pinnedAlertTracker.snapshot().forEach { pinnedAlertTracker.acknowledge(it.label) }
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

        observeProfiles()
    }

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

    private fun refreshPinnedSection() {
        val alerts = pinnedAlertTracker.snapshot()
        pinnedAdapter.submitList(alerts)
        binding.pinnedAlertsSection.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
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
        binding.sceneChipRow.removeAllViews()
        sceneChips.clear()
        val activeId = profileManager.activeProfile.value.id
        for (profile in profiles) {
            val chip = makeSceneChip(profile, profile.id == activeId)
            binding.sceneChipRow.addView(chip)
            sceneChips[profile.id] = chip
            chip.setOnClickListener { profileManager.setActiveProfileId(profile.id) }
            if (!profile.isPreset) {
                chip.setOnLongClickListener { showDeleteProfileDialog(profile); true }
            }
        }
        binding.sceneChipRow.addView(makeAddChip())
    }

    private fun syncChipSelection(activeId: String) {
        for ((id, view) in sceneChips) styleChip(view, id == activeId)
    }

    private fun makeSceneChip(profile: SoundProfile, active: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconForProfile(profile.name))
            layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply {
                marginEnd = dp(6)
            }
        }
        val text = TextView(this).apply {
            text = profile.name
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = 0f
        }
        container.addView(icon)
        container.addView(text)
        styleChip(container, active)
        return container
    }

    private fun styleChip(view: View, active: Boolean) {
        view.background = ContextCompat.getDrawable(
            this,
            if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive
        )
        val color = if (active) ContextCompat.getColor(this, R.color.bg)
                    else ContextCompat.getColor(this, R.color.muted)
        if (view is LinearLayout) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView) child.setTextColor(color)
                if (child is ImageView) child.setColorFilter(color)
            }
        }
    }

    private fun makeAddChip(): View {
        val container = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip_add)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), android.view.Gravity.CENTER)
        }
        container.addView(icon)
        container.setOnClickListener { showCreateProfileDialog() }
        return container
    }

    private fun iconForProfile(name: String): Int {
        val n = name.lowercase()
        return when {
            "home" in n -> R.drawable.ic_house
            "office" in n || "work" in n -> R.drawable.ic_briefcase
            else -> R.drawable.ic_building
        }
    }

    private fun showCreateProfileDialog() {
        val editText = EditText(this).apply { hint = "Profile name"; setSingleLine() }
        val container = FrameLayout(this).apply {
            val pad = dp(20)
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

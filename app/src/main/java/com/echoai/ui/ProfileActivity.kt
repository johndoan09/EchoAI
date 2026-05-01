package com.echoai.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.echoai.R
import com.echoai.databinding.ActivityProfileBinding
import com.echoai.domain.ProfileManager
import com.echoai.domain.SoundProfile
import com.echoai.domain.UrgencyClassifier
import com.echoai.util.YamnetLabelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var profileManager: ProfileManager
    private lateinit var adapter: SoundLabelAdapter
    private var profileId = SoundProfile.DEFAULT_ID

    private var allLabels: List<String> = emptyList()
    private var savedLabels: Set<String> = emptySet()
    private val pendingLabels = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSystemBarInsets()

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: SoundProfile.DEFAULT_ID
        profileManager = ProfileManager(applicationContext)
        val urgencyClassifier = UrgencyClassifier(applicationContext)
        val profile = profileManager.getProfile(profileId)

        binding.profileTitle.text = getString(R.string.tab_scene_profile)
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.tabListening.setOnClickListener { navigateToListening() }
        binding.tabProfile.setOnClickListener { /* already here */ }

        adapter = SoundLabelAdapter(
            urgencyClassifier = urgencyClassifier,
            onToggle = { label, checked ->
                if (checked) pendingLabels.add(label) else pendingLabels.remove(label)
                updateSaveBar()
            },
            onUrgencyChange = { label, urgency ->
                val current = profileManager.getProfile(profileId)
                val updatedOverrides = if (urgency != null)
                    current.urgencyOverrides + (label to urgency)
                else
                    current.urgencyOverrides - label
                profileManager.saveProfile(current.copy(urgencyOverrides = updatedOverrides))
            },
        )

        binding.labelsList.layoutManager = LinearLayoutManager(this)
        binding.labelsList.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                adapter.filter(query)
                binding.searchClearButton.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
            }
        })
        binding.searchClearButton.setOnClickListener {
            binding.searchInput.text?.clear()
        }

        binding.checkAllButton.setOnClickListener {
            pendingLabels.clear()
            pendingLabels.addAll(allLabels)
            adapter.resetTo(allLabels.toSet())
            updateSaveBar()
        }

        binding.uncheckAllButton.setOnClickListener {
            pendingLabels.clear()
            adapter.resetTo(emptySet())
            updateSaveBar()
        }

        binding.resetDefaultsButton.setOnClickListener {
            val current = profileManager.getProfile(profileId)
            profileManager.saveProfile(current.copy(urgencyOverrides = emptyMap()))
            adapter.setData(allLabels, pendingLabels, emptyMap())
        }

        binding.cancelChangesButton.setOnClickListener {
            pendingLabels.clear()
            pendingLabels.addAll(savedLabels)
            adapter.resetTo(savedLabels)
            updateSaveBar()
        }

        binding.saveChangesButton.setOnClickListener {
            val current = profileManager.getProfile(profileId)
            profileManager.saveProfile(current.copy(priorityLabels = pendingLabels.toSet(), checkAll = false))
            savedLabels = pendingLabels.toSet()
            adapter.commitSort()
            updateSaveBar()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pendingLabels != savedLabels) {
                    AlertDialog.Builder(this@ProfileActivity)
                        .setTitle(getString(R.string.unsaved_changes_title))
                        .setMessage(getString(R.string.unsaved_changes_message))
                        .setPositiveButton(getString(R.string.discard_changes)) { _, _ ->
                            finishWithoutAnimation()
                        }
                        .setNegativeButton(getString(R.string.keep_editing), null)
                        .show()
                } else {
                    finishWithoutAnimation()
                }
            }
        })

        lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) { YamnetLabelLoader.loadAll(applicationContext) }
            val current = profileManager.getProfile(profileId)
            allLabels = labels
            val checked = if (current.checkAll) allLabels.toSet() else current.priorityLabels
            savedLabels = checked
            pendingLabels.clear()
            pendingLabels.addAll(checked)
            adapter.setData(labels, checked, current.urgencyOverrides)
            binding.profileSubtitle.text = getString(
                R.string.profile_subtitle, profile.name, labels.size
            )
        }
    }

    private fun installSystemBarInsets() {
        val rootStartLeft = binding.root.paddingLeft
        val rootStartTop = binding.root.paddingTop
        val rootStartRight = binding.root.paddingRight
        val tabStartBottom = binding.bottomTabBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootStartLeft + systemBars.left,
                top = rootStartTop + systemBars.top,
                right = rootStartRight + systemBars.right,
            )
            val navSafeHeight = (systemBars.bottom * 3) / 4
            val grayHeight = systemBars.bottom / 4
            binding.bottomTabBar.updatePadding(
                bottom = tabStartBottom + (navSafeHeight - grayHeight).coerceAtLeast(0)
            )
            binding.systemNavBarBackground.layoutParams =
                binding.systemNavBarBackground.layoutParams.apply {
                    height = grayHeight
                }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun navigateToListening() {
        if (pendingLabels != savedLabels) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.unsaved_changes_title))
                .setMessage(getString(R.string.unsaved_changes_message))
                .setPositiveButton(getString(R.string.discard_changes)) { _, _ -> openListening() }
                .setNegativeButton(getString(R.string.keep_editing), null)
                .show()
        } else {
            openListening()
        }
    }

    private fun openListening() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun updateSaveBar() {
        binding.saveBar.visibility = if (pendingLabels != savedLabels) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

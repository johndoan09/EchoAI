package com.echoai.ui

import android.app.AlertDialog
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
        savedLabels = profile.priorityLabels
        pendingLabels.addAll(savedLabels)

        binding.profileTitle.text = getString(R.string.tab_scene_profile)
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

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
                adapter.filter(s?.toString().orEmpty())
            }
        })

        binding.resetDefaultsButton.setOnClickListener {
            val current = profileManager.getProfile(profileId)
            profileManager.saveProfile(current.copy(urgencyOverrides = emptyMap()))
            adapter.setData(adapter.currentLabels(), current.priorityLabels, emptyMap())
        }

        binding.cancelChangesButton.setOnClickListener {
            pendingLabels.clear()
            pendingLabels.addAll(savedLabels)
            adapter.resetTo(savedLabels)
            updateSaveBar()
        }

        binding.saveChangesButton.setOnClickListener {
            val current = profileManager.getProfile(profileId)
            profileManager.saveProfile(current.copy(priorityLabels = pendingLabels.toSet()))
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
                        .setPositiveButton(getString(R.string.discard_changes)) { _, _ -> finish() }
                        .setNegativeButton(getString(R.string.keep_editing), null)
                        .show()
                } else {
                    finish()
                }
            }
        })

        lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) { YamnetLabelLoader.loadAll(applicationContext) }
            val current = profileManager.getProfile(profileId)
            adapter.setData(labels, current.priorityLabels, current.urgencyOverrides)
            binding.profileSubtitle.text = getString(
                R.string.profile_subtitle, profile.name, labels.size
            )
        }
    }

    private fun installSystemBarInsets() {
        val rootStartLeft = binding.root.paddingLeft
        val rootStartTop = binding.root.paddingTop
        val rootStartRight = binding.root.paddingRight
        val rootStartBottom = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootStartLeft + systemBars.left,
                top = rootStartTop + systemBars.top,
                right = rootStartRight + systemBars.right,
                bottom = rootStartBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateSaveBar() {
        binding.saveBar.visibility = if (pendingLabels != savedLabels) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

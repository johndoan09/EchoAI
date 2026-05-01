package com.echoai.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: SoundProfile.DEFAULT_ID
        profileManager = ProfileManager(applicationContext)
        val urgencyClassifier = UrgencyClassifier(applicationContext)
        val profile = profileManager.getProfile(profileId)

        binding.profileTitle.text = getString(com.echoai.R.string.tab_scene_profile)
        binding.backButton.setOnClickListener { finish() }

        adapter = SoundLabelAdapter(
            urgencyClassifier = urgencyClassifier,
            onToggle = { label, checked ->
                val current = profileManager.getProfile(profileId)
                val updated = if (checked) current.priorityLabels + label else current.priorityLabels - label
                profileManager.saveProfile(current.copy(priorityLabels = updated))
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

        lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) { YamnetLabelLoader.loadAll(applicationContext) }
            val current = profileManager.getProfile(profileId)
            adapter.setData(labels, current.priorityLabels, current.urgencyOverrides)
            binding.profileSubtitle.text = getString(
                com.echoai.R.string.profile_subtitle, profile.name, labels.size
            )
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

package com.echoai.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.echoai.databinding.ActivityProfileBinding
import com.echoai.domain.ProfileManager
import com.echoai.domain.SoundProfile
import com.echoai.domain.Urgency
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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "${profile.name} Profile"
            setDisplayHomeAsUpEnabled(true)
        }

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

        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                adapter.filter(q ?: "")
                return true
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
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

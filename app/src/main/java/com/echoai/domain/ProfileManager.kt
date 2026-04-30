package com.echoai.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("echo_profiles", Context.MODE_PRIVATE)

    private val _activeProfile = MutableStateFlow(loadActiveProfile())
    val activeProfile: StateFlow<SoundProfile> = _activeProfile

    private val _allProfiles = MutableStateFlow(loadAllProfiles())
    val allProfiles: StateFlow<List<SoundProfile>> = _allProfiles

    fun getProfile(id: String): SoundProfile {
        val isPreset = SoundProfile.PRESETS.any { it.id == id }
        val json = prefs.getString("profile_$id", null)
            ?: return if (isPreset) SoundProfile.PRESETS.first { it.id == id }
            else SoundProfile(id = id, name = nameFor(id), priorityLabels = emptySet())

        return try {
            val obj = JSONObject(json)

            val labelsArr = obj.getJSONArray("labels")
            val labels = (0 until labelsArr.length()).map { labelsArr.getString(it) }.toSet()

            val overridesObj = obj.optJSONObject("urgency_overrides")
            val overrides = mutableMapOf<String, Urgency>()
            if (overridesObj != null) {
                for (key in overridesObj.keys()) {
                    Urgency.entries.firstOrNull { it.name == overridesObj.getString(key) }
                        ?.let { overrides[key] = it }
                }
            }

            SoundProfile(
                id = id,
                name = nameFor(id),
                priorityLabels = labels,
                urgencyOverrides = overrides,
                isPreset = isPreset,
            )
        } catch (e: Exception) {
            if (isPreset) SoundProfile.PRESETS.first { it.id == id }
            else SoundProfile(id = id, name = nameFor(id), priorityLabels = emptySet())
        }
    }

    fun saveProfile(profile: SoundProfile) {
        val labelsArr = JSONArray().apply { profile.priorityLabels.forEach { put(it) } }
        val overridesObj = JSONObject().apply {
            profile.urgencyOverrides.forEach { (label, urgency) -> put(label, urgency.name) }
        }
        prefs.edit()
            .putString(
                "profile_${profile.id}",
                JSONObject().put("labels", labelsArr).put("urgency_overrides", overridesObj).toString(),
            )
            .apply()
        if (profile.id == _activeProfile.value.id) {
            _activeProfile.value = profile
        }
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString("active_profile", id).apply()
        _activeProfile.value = getProfile(id)
    }

    fun createProfile(name: String): SoundProfile {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("profile_name_$id", name).apply()
        val profile = SoundProfile(id = id, name = name, priorityLabels = emptySet(), isPreset = false)
        saveProfile(profile)
        saveCustomIds(loadCustomIds() + id)
        _allProfiles.value = loadAllProfiles()
        return profile
    }

    fun deleteProfile(id: String) {
        if (SoundProfile.PRESETS.any { it.id == id }) return
        prefs.edit().remove("profile_$id").remove("profile_name_$id").apply()
        saveCustomIds(loadCustomIds() - id)
        if (_activeProfile.value.id == id) setActiveProfileId(SoundProfile.DEFAULT_ID)
        _allProfiles.value = loadAllProfiles()
    }

    private fun loadActiveProfile(): SoundProfile =
        getProfile(
            prefs.getString("active_profile", SoundProfile.DEFAULT_ID) ?: SoundProfile.DEFAULT_ID
        )

    private fun loadAllProfiles(): List<SoundProfile> =
        SoundProfile.PRESETS + loadCustomIds().map { getProfile(it) }

    private fun loadCustomIds(): List<String> = try {
        val arr = JSONArray(prefs.getString("custom_profile_ids", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun saveCustomIds(ids: List<String>) {
        prefs.edit()
            .putString("custom_profile_ids", JSONArray(ids).toString())
            .apply()
    }

    private fun nameFor(id: String): String =
        SoundProfile.PRESETS.firstOrNull { it.id == id }?.name
            ?: prefs.getString("profile_name_$id", id) ?: id
}

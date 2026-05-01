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
            ?: return if (isPreset) SoundProfile.PRESETS.first { it.id == id }.copy(name = nameFor(id))
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
                checkAll = obj.optBoolean("check_all", false),
            )
        } catch (e: Exception) {
            if (isPreset) SoundProfile.PRESETS.first { it.id == id }.copy(name = nameFor(id))
            else SoundProfile(id = id, name = nameFor(id), priorityLabels = emptySet())
        }
    }

    fun saveProfile(profile: SoundProfile) {
        val labelsArr = JSONArray().apply { profile.priorityLabels.forEach { put(it) } }
        val overridesObj = JSONObject().apply {
            profile.urgencyOverrides.forEach { (label, urgency) -> put(label, urgency.name) }
        }
        val json = JSONObject()
            .put("labels", labelsArr)
            .put("urgency_overrides", overridesObj)
            .put("check_all", profile.checkAll)
        prefs.edit().putString("profile_${profile.id}", json.toString()).apply()
        if (profile.id == _activeProfile.value.id) {
            _activeProfile.value = profile
        }
        _allProfiles.value = loadAllProfiles()
    }

    fun refreshFromStorage() {
        _allProfiles.value = loadAllProfiles()
        _activeProfile.value = loadActiveProfile()
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString("active_profile", id).apply()
        _activeProfile.value = getProfile(id)
    }

    fun createProfile(name: String): SoundProfile {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("profile_name_$id", name).apply()
        val profile = SoundProfile(id = id, name = name, priorityLabels = emptySet(), isPreset = false, checkAll = true)
        saveProfile(profile)
        saveCustomIds(loadCustomIds() + id)
        // Append to saved order if one exists
        val orderStr = prefs.getString("profile_order", null)
        if (orderStr != null) {
            try {
                prefs.edit().putString("profile_order", JSONArray(orderStr).put(id).toString()).apply()
            } catch (_: Exception) {}
        }
        _allProfiles.value = loadAllProfiles()
        return profile
    }

    fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString("profile_name_$id", trimmed).apply()
        val current = getProfile(id)
        if (current.id == _activeProfile.value.id) {
            _activeProfile.value = current
        }
        _allProfiles.value = loadAllProfiles()
    }

    fun reorderProfiles(orderedIds: List<String>) {
        prefs.edit().putString("profile_order", JSONArray(orderedIds).toString()).apply()
        _allProfiles.value = loadAllProfiles()
    }

    fun canDeleteProfile(id: String): Boolean =
        loadAllProfiles().any { it.id == id } && loadAllProfiles().size > 1

    fun deleteProfile(id: String): Boolean {
        if (!canDeleteProfile(id)) return false

        val isPreset = SoundProfile.PRESETS.any { it.id == id }
        val editor = prefs.edit()
            .remove("profile_$id")
            .remove("profile_name_$id")

        if (isPreset) {
            editor.putString("deleted_preset_ids", JSONArray(loadDeletedPresetIds() + id).toString())
        } else {
            saveCustomIds(loadCustomIds() - id)
        }

        val orderStr = prefs.getString("profile_order", null)
        if (orderStr != null) {
            val orderedIds = try {
                val arr = JSONArray(orderStr)
                (0 until arr.length()).map { arr.getString(it) }.filterNot { it == id }
            } catch (_: Exception) {
                emptyList()
            }
            editor.putString("profile_order", JSONArray(orderedIds).toString())
        }

        editor.apply()

        val profiles = loadAllProfiles()
        if (_activeProfile.value.id == id) {
            prefs.edit().putString("active_profile", profiles.first().id).apply()
            _activeProfile.value = profiles.first()
        }
        _allProfiles.value = profiles
        return true
    }

    private fun loadActiveProfile(): SoundProfile =
        getProfile(
            prefs.getString("active_profile", SoundProfile.DEFAULT_ID) ?: SoundProfile.DEFAULT_ID
        )

    private fun loadAllProfiles(): List<SoundProfile> {
        val presetIds = SoundProfile.PRESETS.map { it.id } - loadDeletedPresetIds().toSet()
        val allKnownIds = presetIds + loadCustomIds()
        val orderStr = prefs.getString("profile_order", null) ?: return allKnownIds.map { getProfile(it) }
        return try {
            val arr = JSONArray(orderStr)
            val savedIds = (0 until arr.length()).map { arr.getString(it) }
            val ordered = savedIds.filter { it in allKnownIds } +
                allKnownIds.filter { it !in savedIds }
            ordered.map { id -> getProfile(id) }
        } catch (_: Exception) {
            allKnownIds.map { getProfile(it) }
        }
    }

    private fun loadCustomIds(): List<String> = try {
        val arr = JSONArray(prefs.getString("custom_profile_ids", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun saveCustomIds(ids: List<String>) {
        prefs.edit()
            .putString("custom_profile_ids", JSONArray(ids).toString())
            .apply()
    }

    private fun loadDeletedPresetIds(): Set<String> = try {
        val arr = JSONArray(prefs.getString("deleted_preset_ids", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun nameFor(id: String): String =
        prefs.getString("profile_name_$id", null)
            ?: SoundProfile.PRESETS.firstOrNull { it.id == id }?.name
            ?: id
}

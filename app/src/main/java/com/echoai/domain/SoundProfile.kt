package com.echoai.domain

data class SoundProfile(
    val id: String,
    val name: String,
    val priorityLabels: Set<String>,
    val urgencyOverrides: Map<String, Urgency> = emptyMap(),
    val isPreset: Boolean = false,
    val checkAll: Boolean = false,
) {
    companion object {
        val DEFAULT = SoundProfile(
            id = "default",
            name = "Default",
            isPreset = true,
            priorityLabels = emptySet(),
            checkAll = true,
        )
        val PRESETS: List<SoundProfile> = listOf(DEFAULT)
        const val DEFAULT_ID = "default"
    }
}

package com.echoai.domain

data class SoundProfile(
    val id: String,
    val name: String,
    val priorityLabels: Set<String>,
    val urgencyOverrides: Map<String, Urgency> = emptyMap(),
    val isPreset: Boolean = false,
) {
    companion object {
        val CITY = SoundProfile(
            id = "city",
            name = "City",
            isPreset = true,
            priorityLabels = setOf(
                "Siren", "Civil defense siren",
                "Vehicle horn, car horn, honking", "Car alarm",
                "Air horn, truck horn", "Emergency vehicle",
                "Police car (siren)", "Ambulance (siren)",
                "Fire engine, fire truck (siren)", "Screaming",
                "Gunshot, gunfire", "Train", "Train horn",
                "Motorcycle", "Tire squeal", "Skidding",
            ),
        )
        val HOME = SoundProfile(
            id = "home",
            name = "Home",
            isPreset = true,
            priorityLabels = setOf(
                "Baby cry, infant cry", "Knock", "Doorbell", "Ding-dong",
                "Fire alarm", "Smoke detector, smoke alarm",
                "Dog", "Bark", "Telephone bell ringing",
                "Glass", "Shatter", "Breaking",
                "Crying, sobbing", "Wail, moan",
            ),
        )
        val OFFICE = SoundProfile(
            id = "office",
            name = "Office",
            isPreset = true,
            priorityLabels = setOf(
                "Telephone bell ringing", "Alarm", "Alarm clock",
                "Doorbell", "Ding-dong", "Fire alarm",
                "Smoke detector, smoke alarm", "Knock", "Slam", "Buzzer",
            ),
        )

        val PRESETS: List<SoundProfile> = listOf(CITY, HOME, OFFICE)
        const val DEFAULT_ID = "city"
    }
}

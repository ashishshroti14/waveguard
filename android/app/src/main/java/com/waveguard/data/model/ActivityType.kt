package com.waveguard.data.model

enum class ActivityType(val displayName: String) {
    EMPTY("Empty Room"),
    SITTING("Sitting"),
    STANDING("Standing"),
    WALKING("Walking"),
    FALLING("Fall Detected"),
    UNKNOWN("Unknown")
}

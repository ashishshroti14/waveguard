package com.waveguard.data.model

data class RuViewTelemetryData(
    val source: String?,
    val meanRssi: Float?,
    val variance: Float?,
    val motionBandPower: Float?,
    val breathingBandPower: Float?,
    val spectralPower: Float?,
    val dominantFreqHz: Float?,
    val changePoints: Int?,
    val motionLevel: String?,
    val confidence: Float?,
    val timestamp: Long
)

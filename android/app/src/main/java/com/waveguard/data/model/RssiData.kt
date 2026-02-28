package com.waveguard.data.model

data class RssiData(
    val id: Long = 0,
    val timestamp: Long,
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequency: Int
)

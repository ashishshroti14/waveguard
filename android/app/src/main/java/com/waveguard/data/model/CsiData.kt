package com.waveguard.data.model

data class CsiData(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val amplitudes: FloatArray,    // 52 subcarriers
    val phases: FloatArray,        // 52 subcarriers
    val rssi: Int,
    val noiseFloor: Int,
    val channel: Int,
    val bandwidth: Int             // 20 or 40 MHz
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CsiData) return false
        return id == other.id &&
            timestamp == other.timestamp &&
            amplitudes.contentEquals(other.amplitudes) &&
            phases.contentEquals(other.phases) &&
            rssi == other.rssi &&
            noiseFloor == other.noiseFloor &&
            channel == other.channel &&
            bandwidth == other.bandwidth
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + amplitudes.contentHashCode()
        result = 31 * result + phases.contentHashCode()
        result = 31 * result + rssi
        result = 31 * result + noiseFloor
        result = 31 * result + channel
        result = 31 * result + bandwidth
        return result
    }
}

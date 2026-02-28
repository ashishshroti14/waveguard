package com.waveguard.data.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.waveguard.data.model.CsiData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EspCsiReceiver"
private const val DEVICE_NAME = "WaveGuard-ESP32"
private const val MTU_SIZE = 512

private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
private val CSI_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-123456789001")
private val CONFIG_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-123456789002")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE GATT client that connects to the WaveGuard ESP32 sensor and streams CSI data.
 *
 * Packet layout (little-endian):
 *   [4B rssi (int)] [4B noiseFloor (int)] [2B channel (short)] [2B bandwidth (short)]
 *   [52 × 4B amplitudes (float)] [52 × 4B phases (float)]
 *   Total = 12 + 208 + 208 = 428 bytes
 */
@Singleton
class EspCsiReceiver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val leScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var gatt: BluetoothGatt? = null

    // ------------------------------------------------------------------
    // Public CSI flow
    // ------------------------------------------------------------------

    /**
     * Cold flow that starts a BLE scan, connects to the ESP32, and emits parsed [CsiData]
     * packets.  The scan/connection is torn down when the collector cancels.
     */
    @SuppressLint("MissingPermission")
    val csiFlow: Flow<CsiData> = callbackFlow {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not available or disabled")
            close()
            return@callbackFlow
        }

        val gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "GATT connected – requesting MTU $MTU_SIZE")
                        _isConnected.value = true
                        gatt.requestMtu(MTU_SIZE)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "GATT disconnected (status=$status)")
                        _isConnected.value = false
                        gatt.close()
                        this@callbackFlow.close()
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU changed to $mtu (status=$status)")
                gatt.discoverServices()
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed (status=$status)")
                    return
                }
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "WaveGuard service not found")
                    return
                }
                val csiChar = service.getCharacteristic(CSI_CHAR_UUID)
                if (csiChar == null) {
                    Log.e(TAG, "CSI characteristic not found")
                    return
                }
                enableNotifications(gatt, csiChar)
            }

            @Suppress("DEPRECATION")
            @SuppressLint("MissingPermission")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == CSI_CHAR_UUID) {
                    val data = characteristic.value ?: return
                    parseCsiPacket(data)?.let { trySend(it) }
                }
            }

            // Android 13+ callback
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CSI_CHAR_UUID) {
                    parseCsiPacket(value)?.let { trySend(it) }
                }
            }
        }

        val scanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(TAG, "Found device: ${result.device.name} (${result.device.address})")
                leScanner?.stopScan(this)
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    result.device.connectGatt(
                        context, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    result.device.connectGatt(context, false, gattCallback)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed (errorCode=$errorCode)")
                this@callbackFlow.close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        val scanFilter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "Starting BLE scan for $DEVICE_NAME")
        leScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        awaitClose {
            Log.i(TAG, "Closing EspCsiReceiver flow")
            leScanner?.stopScan(scanCallback)
            gatt?.apply {
                disconnect()
                close()
            }
            gatt = null
            _isConnected.value = false
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD descriptor not found on CSI characteristic")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        Log.i(TAG, "Notifications enabled for CSI characteristic")
    }

    /**
     * Parses a raw BLE packet into a [CsiData] object.
     *
     * Expected packet layout (little-endian, 428 bytes):
     * ```
     * offset  size  field
     *      0     4  rssi (Int)
     *      4     4  noiseFloor (Int)
     *      8     2  channel (Short)
     *     10     2  bandwidth (Short)
     *     12   208  amplitudes (52 × Float)
     *    220   208  phases (52 × Float)
     * ```
     */
    private fun parseCsiPacket(bytes: ByteArray): CsiData? {
        val expectedSize = 4 + 4 + 2 + 2 + (52 * 4) + (52 * 4) // 428
        if (bytes.size < expectedSize) {
            Log.w(TAG, "Packet too short: ${bytes.size} < $expectedSize bytes")
            return null
        }
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rssi = buf.int
            val noiseFloor = buf.int
            val channel = buf.short.toInt()
            val bandwidth = buf.short.toInt()
            val amplitudes = FloatArray(52) { buf.float }
            val phases = FloatArray(52) { buf.float }
            CsiData(
                timestamp = System.currentTimeMillis(),
                amplitudes = amplitudes,
                phases = phases,
                rssi = rssi,
                noiseFloor = noiseFloor,
                channel = channel,
                bandwidth = bandwidth
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CSI packet", e)
            null
        }
    }
}

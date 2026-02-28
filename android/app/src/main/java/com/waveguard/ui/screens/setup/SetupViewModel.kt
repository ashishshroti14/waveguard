package com.waveguard.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waveguard.data.remote.EspCsiReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val espCsiReceiver: EspCsiReceiver
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _esp32Found = MutableStateFlow(false)
    val esp32Found: StateFlow<Boolean> = _esp32Found.asStateFlow()

    private val _connectionState = MutableStateFlow("idle")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Observe BLE connection status from the receiver
        viewModelScope.launch {
            espCsiReceiver.isConnected.collect { connected ->
                if (connected) {
                    _connectionState.value = "connected"
                    _esp32Found.value = true
                } else if (_connectionState.value == "connecting") {
                    _connectionState.value = "failed"
                }
            }
        }
    }

    fun scanForEsp32() {
        if (_isScanning.value) return
        _isScanning.value = true
        _esp32Found.value = false
        _connectionState.value = "scanning"

        scanJob = viewModelScope.launch {
            // Simulate scan window; actual discovery happens via BLE callbacks in EspCsiReceiver
            delay(8_000L)
            _isScanning.value = false
            if (_connectionState.value == "scanning") {
                _connectionState.value = "not_found"
            }
        }
    }

    fun connectToEsp32() {
        _connectionState.value = "connecting"
        // Connection is managed by EspCsiReceiver via its csiFlow collection; we reflect state here
        viewModelScope.launch {
            espCsiReceiver.csiFlow.collect {
                // Receiving data confirms connection; handled by isConnected observer above
            }
        }
    }

    fun selectPhoneOnlyMode() {
        scanJob?.cancel()
        _isScanning.value = false
        _connectionState.value = "phone_only"
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}

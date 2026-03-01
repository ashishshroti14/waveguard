package com.waveguard.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waveguard.data.repository.SensorRepository
import com.waveguard.domain.calibration.CalibrationState
import com.waveguard.domain.calibration.RoomCalibrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val roomCalibrator: RoomCalibrator,
    private val sensorRepository: SensorRepository
) : ViewModel() {

    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    fun startCalibration() {
        viewModelScope.launch {
            roomCalibrator.calibrate(sensorRepository.rssiData).collect { state ->
                _calibrationState.value = state
            }
        }
    }

    fun resetCalibration() {
        _calibrationState.value = CalibrationState.Idle
    }
}

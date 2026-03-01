package com.waveguard.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.repository.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val alertRepository: AlertRepository
) : ViewModel() {

    private val _alerts = MutableStateFlow<List<AlertEvent>>(emptyList())
    val alerts: StateFlow<List<AlertEvent>> = _alerts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadAlerts()
    }

    private fun loadAlerts() {
        viewModelScope.launch {
            try {
                alertRepository.getAllAlerts().collect { alertList ->
                    _alerts.value = alertList
                    _isLoading.value = false
                }
            } catch (_: Exception) {
                _isLoading.value = false
            }
        }
    }

    fun acknowledgeAlert(id: Long) {
        viewModelScope.launch {
            alertRepository.acknowledgeAlert(id)
        }
    }
}

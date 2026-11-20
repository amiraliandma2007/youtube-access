package com.directaccess.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class VpnStatus {
    data object Disconnected : VpnStatus()
    data object Connecting : VpnStatus()
    data class Connected(val startedAtElapsedMs: Long) : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

object VpnState {
    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    fun update(newStatus: VpnStatus) {
        _status.value = newStatus
    }
}

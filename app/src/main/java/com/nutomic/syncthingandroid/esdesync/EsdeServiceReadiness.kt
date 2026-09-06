package com.nutomic.syncthingandroid.esdesync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EsdeServiceSnapshot(
    val apiReady: Boolean = false,
    val coordinatorReady: Boolean = false,
    val revision: Long = 0,
)

/** Readiness and configuration changes, including changes while the service stays ACTIVE. */
class EsdeServiceReadiness {
    private val mutableState = MutableStateFlow(EsdeServiceSnapshot())
    val state: StateFlow<EsdeServiceSnapshot> = mutableState.asStateFlow()

    @Synchronized fun publish(apiReady: Boolean, coordinatorReady: Boolean) {
        mutableState.value = EsdeServiceSnapshot(apiReady, coordinatorReady, mutableState.value.revision + 1)
    }
}

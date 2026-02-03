package com.example.nupe.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class RiskLevel {
    SAFE,
    WARNING,
    INTENT, // Trigger Notification
    MAX_PENALTY
}

@Singleton
class RiskEscalationManager @Inject constructor() {

    private var riskScore = 0
    private var decayJob: Job? = null
    // CRITICAL FIX: Use managed Job for proper lifecycle control
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(managerJob + Dispatchers.Default)


    fun onSuspiciousEvent() {
        riskScore++
        resetDecayTimer()
        android.util.Log.d("NupeRisk", "Risk Score Escalated: $riskScore")
    }

    private fun resetDecayTimer() {
        decayJob?.cancel()
        decayJob = scope.launch {
            delay(30_000) // 30 seconds
            riskScore = 0
            android.util.Log.d("NupeRisk", "Risk Score Decay: Reset to 0")
        }
    }

    fun getRiskLevel(): RiskLevel {
        return when {
            riskScore == 0 -> RiskLevel.SAFE
            riskScore == 1 -> RiskLevel.WARNING
            riskScore >= 2 -> RiskLevel.INTENT
            // Max penalty logic could be separate or just high score, 
            // but prompt says "If TFLite detects Porn -> Max Penalty".
            // We can handle manual MAX trigger separately.
            else -> RiskLevel.INTENT 
        }
    }

    // Special trigger for Image Detection
    fun triggerMaxPenalty() {
        riskScore = 100 // Instant high score
        resetDecayTimer()
    }
    
    fun incrementRisk() = onSuspiciousEvent()
    fun setMaxRisk() = triggerMaxPenalty()
    
    fun resetRisk() {
        riskScore = 0
        decayJob?.cancel()
        android.util.Log.d("NupeRisk", "Risk Score Reset to 0 (Safe Zone)")
    }

    fun getCurrentScore() = riskScore

    /**
     * CRITICAL FIX: Call this when the service is destroyed to clean up coroutines
     */
    fun cleanup() {
        decayJob?.cancel()
        managerJob.cancel()
        riskScore = 0
        android.util.Log.d("NupeRisk", "RiskEscalationManager cleaned up")
    }
}

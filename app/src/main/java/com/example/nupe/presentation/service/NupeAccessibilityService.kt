package com.example.nupe.presentation.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.nupe.core.CoreConstants
import com.example.nupe.data.ml.ImageAnalyzer
import com.example.nupe.data.text.TextAnalyzer
import com.example.nupe.presentation.overlay.OverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class NupeAccessibilityService : AccessibilityService() {

    @Inject lateinit var textAnalyzer: TextAnalyzer
    @Inject lateinit var imageAnalyzer: ImageAnalyzer
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var riskManager: com.example.nupe.domain.manager.RiskEscalationManager
    @Inject lateinit var quranRepository: com.example.nupe.core.data.QuranRepository
    @Inject lateinit var notificationHelper: com.example.nupe.presentation.util.NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastScrollTime = 0L
    private val scrollDebounceJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 1. FILTER: Ignore safe apps
        if (CoreConstants.SAFE_PACKAGES.contains(packageName)) {
            // android.util.Log.v("NupeService", "Ignored safe package: $packageName")
            return
        }
        android.util.Log.d("NupeService", "Processing event from: $packageName, type: ${event.eventType}")

        // 2. SMART TRIGGER: Check event type
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScrollEvent()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleContentChange(event)
            }
        }
    }

    private fun handleScrollEvent() {
        lastScrollTime = System.currentTimeMillis()
        overlayManager.hideBubble() // Hide bubble on scroll
    }

    private fun handleContentChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val isKeyboard = packageName.contains("inputmethod") || packageName.contains("keyboard")
        
        // Skip debounce checks for keyboard events or explicit text changes
        if (!isKeyboard && event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            System.currentTimeMillis() - lastScrollTime < CoreConstants.SCROLL_DEBOUNCE_MS) {
            android.util.Log.d("NupeService", "Skipping content change due to scroll debounce")
            return
        }

        // 1. Try event text first
        val eventText = event.text.joinToString(" ")
        
        // 2. Try content description
        val contentDesc = event.contentDescription?.toString() ?: ""
        
        // 3. For keyboards, the text is often in the class name or event text
        // 4. If empty, traverse.
        var extractedText = "$eventText $contentDesc".trim()
        
        if (extractedText.isEmpty() || extractedText.isBlank()) {
             extractedText = extractTextFromNode(event.source)
        }

        android.util.Log.d("NupeService", "Content changed (Final): '$extractedText'")
        
        if (extractedText.isNotEmpty()) {
             analyzeText(extractedText)
        }
    }

    private fun extractTextFromNode(node: android.view.accessibility.AccessibilityNodeInfo?): String {
        if (node == null) return ""
        
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        
        val sb = StringBuilder()
        if (!text.isNullOrBlank()) sb.append(text).append(" ")
        if (!desc.isNullOrBlank()) sb.append(desc).append(" ")
        
        // Recursively check children
        for (i in 0 until node.childCount) {
             val child = node.getChild(i)
             sb.append(extractTextFromNode(child)).append(" ")
             child?.recycle() // Important to recycle!
        }
        
        return sb.toString().trim()
    }

    private fun analyzeText(text: String) {
        scope.launch {
            val isSuspicious = textAnalyzer.analyze(text)
            android.util.Log.d("NupeService", "Analyzing text: '$text', suspicious: $isSuspicious")
            
            if (isSuspicious) {
                riskManager.onSuspiciousEvent()
                val currentRisk = riskManager.getRiskLevel()
                
                withContext(Dispatchers.Main) {
                    when (currentRisk) {
                         com.example.nupe.domain.manager.RiskLevel.WARNING -> {
                             // Level 1: Warning Bubble
                             // Refined UI: Red circle, White "!" (To be implemented in OverlayManager UI code, or assume it handles "showBubble" with updated Logic)
                             // For now we just call showBubble.
                             overlayManager.showBubble(this@NupeAccessibilityService)
                         }
                         com.example.nupe.domain.manager.RiskLevel.INTENT, com.example.nupe.domain.manager.RiskLevel.MAX_PENALTY -> {
                             // Level 2+: Notification + Verse
                             val verse = quranRepository.getRandomVerse()
                             notificationHelper.showRiskNotification(verse)
                             overlayManager.showBubble(this@NupeAccessibilityService) // Also show bubble
                         }
                         else -> {} // SAFE
                    }
                }
                
                if (currentRisk == com.example.nupe.domain.manager.RiskLevel.INTENT) {
                     triggerImageAnalysis()
                }
            }
        }
    }

    private fun triggerImageAnalysis() {
        scope.launch {
            // Mocking screenshot capture or using a real mechanism if available
            // In a real accessibility service, takeScreenshot() API (Android 11+) or MediaProjection is used.
            // For this snippet, we assume imageAnalyzer takes a bitmap or we simulate the result.
            
            // val bitmap = takeScreenshotOrBitmap() 
            // val isPorn = imageAnalyzer.analyze(bitmap)
             
            // Simulating image analysis result for purpose of logic flow:
            val isPorn = false // Default safe
            
            if (isPorn) {
                withContext(Dispatchers.Main) {
                    riskManager.triggerMaxPenalty()
                    overlayManager.showBlur(this@NupeAccessibilityService)
                    overlayManager.showBlock(this@NupeAccessibilityService) {
                         // Sanctuary navigation
                         val intent = android.content.Intent(this@NupeAccessibilityService, com.example.nupe.presentation.SanctuaryActivity::class.java).apply {
                             flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                         }
                         startActivity(intent)
                    }
                }
            }
        }
    }

    // Example escalation method
    private fun triggerBlock() {
         scope.launch(Dispatchers.Main) {
             performGlobalAction(GLOBAL_ACTION_BACK)
             overlayManager.showBlock(this@NupeAccessibilityService) {
                 // Navigate to Sanctuary
                 val intent = android.content.Intent(this@NupeAccessibilityService, com.example.nupe.presentation.SanctuaryActivity::class.java).apply {
                     flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                 }
                 startActivity(intent)
             }
         }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onInterrupt() {
        // Handle interruption
    }
}

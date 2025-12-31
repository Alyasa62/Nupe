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
                withContext(Dispatchers.Main) {
                    overlayManager.showBubble()
                     // Escalation: If very suspicious, could trigger image analysis or block immediately.
                     // For now, Level 1: Bubble.
                }
                
                // Trigger Image Analysis as escalation (mock logic)
                // takeScreenshotAndAnalyze() 
            }
        }
    }

    // Example escalation method
    private fun triggerBlock() {
         scope.launch(Dispatchers.Main) {
             performGlobalAction(GLOBAL_ACTION_BACK)
             overlayManager.showBlock {
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

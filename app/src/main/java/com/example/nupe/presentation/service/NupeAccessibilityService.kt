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
    @Inject lateinit var ocrAnalyzer: com.example.nupe.core.data.OcrAnalyzer
    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var riskManager: com.example.nupe.domain.manager.RiskEscalationManager
    @Inject lateinit var quranRepository: com.example.nupe.core.data.QuranRepository
    @Inject lateinit var notificationHelper: com.example.nupe.presentation.util.NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastScrollTime = 0L
    private var lastSuspiciousTime = 0L // Feature: Sticky Bubble Cool-down
    private var lastImageScanTime = 0L // Feature: Throttling
    private val scrollDebounceJob: Job? = null
    
    // Feature: Safe Zone Detection (Safe Apps)
    private val safeApps = listOf(
        "com.example.nupe",
        "com.miui.home", 
        "com.android.launcher3", 
        "com.google.android.apps.nexuslauncher", 
        "com.android.systemui", 
        "com.android.settings",
        "com.google.android.dialer",
        "com.android.deskclock"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 1. FILTER: Ignore safe apps
        if (CoreConstants.SAFE_PACKAGES.contains(packageName)) {
            return
        }
        android.util.Log.d("NupeService", "Processing event from: $packageName, type: ${event.eventType}")

        // 2. SMART TRIGGER: Check event type
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScrollEvent()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                 handleContentChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                 // Feature: Forgiveness Logic (Enter Safe Zone)
                 if (packageName == this.packageName || safeApps.any { packageName.contains(it) }) {
                     android.util.Log.d("Nupe", "Entered Safe Zone: $packageName. Resetting.")
                     // 1. Reset Score
                     riskManager.resetRisk()
                     // 2. Remove Overlay (Safely)
                     overlayManager.hideBlock() 
                     overlayManager.hideBubble()
                     // 3. Stop processing this event
                     return
                 }
                 handleContentChange(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleContentChange(event)
            }
        }
    }

    private fun handleScrollEvent() {
        lastScrollTime = System.currentTimeMillis()
        overlayManager.hideBubble() // Hide bubble on scroll
        
        // Feature: Aggressive Image Scanning
        // If Red Bubble is active (risk > 0), check images on every scroll
        if (riskManager.getCurrentScore() > 0) {
            triggerImageAnalysis()
        }
    }

    private fun handleContentChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val isKeyboard = packageName.contains("inputmethod") || packageName.contains("keyboard")
        
        // Skip debounce checks for keyboard events or explicit text changes
        val isTyping = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                       event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

        if (!isKeyboard && !isTyping &&
            System.currentTimeMillis() - lastScrollTime < CoreConstants.SCROLL_DEBOUNCE_MS) {
            android.util.Log.d("NupeService", "Skipping content change due to scroll debounce")
            return
        }

        // Feature: Aggressive Image Scanning
        if (riskManager.getCurrentScore() > 0) {
            triggerImageAnalysis()
        }

        // 1. Feature: Full Node Text Scanning
        // MUST grab the Full Node Text
        var extractedText = event.source?.text?.toString() ?: ""
        
        if (extractedText.isBlank()) {
             val eventText = event.text.joinToString(" ")
             val contentDesc = event.contentDescription?.toString() ?: ""
             extractedText = "$eventText $contentDesc".trim()
        }
        
        if (extractedText.isEmpty() || extractedText.isBlank()) {
             extractedText = extractTextFromNode(event.source)
        }

        android.util.Log.d("NupeService", "Content changed (Strict Mode): '$extractedText'")
        
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
            // 1. Check Keywords (TextAnalyzer checks both BAD and NUCLEAR keywords)
            val isSuspicious = textAnalyzer.analyze(text)
            
            if (isSuspicious) {
                // CHANGED LOG TAG TO VERIFY UPDATE
                android.util.Log.d("Nupe", "Text Warning (Bubble Only): Suspicious text found.")
                
                withContext(Dispatchers.Main) {
                    // 2. Show Warning (Bubble ONLY)
                    // RULE: NEVER BLOCK ON TEXT. ONLY SHOW BUBBLE.
                    overlayManager.showBubble(this@NupeAccessibilityService)
                    
                    // 3. Track Risk
                    riskManager.incrementRisk()
                    lastSuspiciousTime = System.currentTimeMillis()
                    
                    // 4. Trigger the "Eyes" (Check for images now)
                    // Blocking only happens if this function confirms an image violation.
                    triggerImageAnalysis() 
                }
            } else {
                 // Hide bubble if enough time has passed (cleanup logic)
                 if (System.currentTimeMillis() - lastSuspiciousTime > 3000) {
                     withContext(Dispatchers.Main) {
                        overlayManager.hideBubble()
                     }
                 }
            }
        }
    }

    private fun triggerImageAnalysis() {
        // Throttling: Max once per 1 second
        val now = System.currentTimeMillis()
        if (now - lastImageScanTime < 1000L) {
             return
        }
        lastImageScanTime = now

        scope.launch {
            // Fix: Image Analyzer Logging
            android.util.Log.d("Nupe", "Attempting Screenshot...")

            // 1. Capture Bitmap (Android 11+ API) with Callback
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    this@NupeAccessibilityService.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bitmap = try {
                                val hardwareBitmap = screenshot.hardwareBuffer.let { 
                                    android.graphics.Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace) 
                                }
                                // Copy to software bitmap for TFLite processing (Hardware bitmaps are often immutable/gpu-only)
                                hardwareBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also {
                                    hardwareBitmap?.recycle()
                                    screenshot.hardwareBuffer.close()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Nupe", "Bitmap conversion failed", e)
                                null
                            }

                            if (bitmap != null) {
                                processBitmap(bitmap)
                            } else {
                                handleScreenshotFailure(-1)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            android.util.Log.e("Nupe", "Screenshot failed with error code: $errorCode")
                            handleScreenshotFailure(errorCode)
                        }
                    }
                )
            } else {
                android.util.Log.e("Nupe", "Screenshot not supported on this Android version")
            }
        }
    }

    private fun handleScreenshotFailure(errorCode: Int) {
        // Feature: "Blind Block" Removed -> Notification Only
        // Logic: "Ensure that a high RiskScore triggers a System Notification but does NOT trigger the Blocker."
        val currentRisk = riskManager.getCurrentScore()
        if (currentRisk > 20) {
            android.util.Log.w("Nupe", "Screenshot failed ($errorCode) and Risk is High ($currentRisk). Sending Repentance Notification.")
            
            // Show Repentance Reminder (Notification)
            val verse = quranRepository.getRandomVerse()
            notificationHelper.showRiskNotification(verse)
            
            // CRITICAL: Do NOT call triggerBlock() here. 
            // Blocking is exclusive to confirmed visual evidence.
        }
    }

    private fun processBitmap(bitmap: android.graphics.Bitmap) {
        scope.launch {
            // 2. Parallel Analysis (OCR + Visual)
            // Use async to run them concurrently on the background thread
            val ocrDeferred = async { 
                try {
                    ocrAnalyzer.analyze(bitmap) 
                } catch(e: Exception) { 
                    false 
                }
            }
            
            val visualDeferred = async {
                try {
                    val score = imageAnalyzer.analyzeImage(bitmap)
                    score > 0.5f // Threshold for porn (0.0 - 1.0)
                } catch(e: Exception) {
                    false
                }
            }

            // 3. Smart Short-circuit & Split Logic
            val isTextPorn = ocrDeferred.await()
            val isVisualPorn = visualDeferred.await()

            if (isVisualPorn) {
                // CRITICAL: Only Visual Nudity triggers the Nuclear Option (Block)
                android.util.Log.d("Nupe", "Visual Porn Detected! Blocking.")
                triggerBlock()
            } else if (isTextPorn) {
                // OCR text is treated just like typed text -> Warning only
                // This prevents the "Typing 'porn' leads to Block" bug
                android.util.Log.d("Nupe", "OCR found bad text. Showing Warning.")
                withContext(Dispatchers.Main) {
                     overlayManager.showBubble(this@NupeAccessibilityService)
                     riskManager.incrementRisk()
                }
                // DO NOT call triggerBlock() here.
            }
            
            bitmap.recycle()
        }
    }

    // Example escalation method
    // The ONLY method authorized to block the screen.
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

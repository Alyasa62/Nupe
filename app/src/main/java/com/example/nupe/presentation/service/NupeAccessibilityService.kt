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

    // CRITICAL FIX: Use managed Job for proper lifecycle control
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private var lastScrollTime = 0L
    private var lastSuspiciousTime = 0L // Feature: Sticky Bubble Cool-down
    private var lastImageScanTime = 0L // Feature: Throttling

    // CRITICAL PERFORMANCE FIX: Prevent concurrent analysis to avoid UI freeze
    private var isAnalyzing = false
    
    // Feature: Safe Zone Detection (Safe Apps)
    // CRITICAL FIX: Removed launchers from safe apps - users can search for bad words in launcher!
    private val safeApps = listOf(
        "com.example.nupe",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.dialer",
        "com.android.deskclock"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // CRITICAL: Wrap entire method in try-catch to prevent service crashes
        try {
            if (event == null) return

            // HEARTBEAT: Log every event received
            android.util.Log.v("Nupe", "Event received: ${event.eventType}")

            val packageName = event.packageName?.toString() ?: return

            // DEBUG: Log package name
            android.util.Log.d("Nupe", "Package: $packageName")

            // 1. FILTER: Ignore safe apps
            if (CoreConstants.SAFE_PACKAGES.contains(packageName)) {
                android.util.Log.d("Nupe", "Filtered (SAFE_PACKAGES): $packageName")
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
        } catch (e: Exception) {
            // CRITICAL: Log error but DO NOT crash the service
            android.util.Log.e("Nupe", "Error in onAccessibilityEvent - Service continues running", e)
        }
    }

    private fun handleScrollEvent() {
        lastScrollTime = System.currentTimeMillis()
        overlayManager.hideBubble() // Hide bubble on scroll

        // CRITICAL FIX: Always trigger image analysis on scroll
        // This ensures we detect images even without text triggers first
        triggerImageAnalysis()
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

        // CRITICAL FIX: Always trigger image analysis on content change
        // This ensures we detect images proactively
        triggerImageAnalysis()

        // 1. Feature: Full Node Text Scanning
        // MUST grab the Full Node Text
        val rootNode = event.source
        var extractedText = rootNode?.text?.toString() ?: ""

        if (extractedText.isBlank()) {
             val eventText = event.text.joinToString(" ")
             val contentDesc = event.contentDescription?.toString() ?: ""
             extractedText = "$eventText $contentDesc".trim()
        }

        if (extractedText.isEmpty() || extractedText.isBlank()) {
             extractedText = extractTextFromNode(rootNode, depth = 0, maxDepth = 4)
        }

        // CRITICAL FIX: Recycle the root node to prevent memory leak
        rootNode?.recycle()

        android.util.Log.d("NupeService", "Content changed (Strict Mode): '$extractedText'")

        if (extractedText.isNotEmpty()) {
             analyzeText(extractedText)
        }
    }

    private fun extractTextFromNode(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 4
    ): String {
        if (node == null) return ""

        // CRITICAL FIX: Add depth limit to prevent ANR on deep UI hierarchies
        if (depth >= maxDepth) {
            return ""
        }

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        val sb = StringBuilder()
        if (!text.isNullOrBlank()) sb.append(text).append(" ")
        if (!desc.isNullOrBlank()) sb.append(desc).append(" ")

        // CRITICAL FIX: Early termination if we have enough text (1000 chars)
        if (sb.length > 1000) {
            return sb.toString().trim()
        }

        // Recursively check children with depth tracking
        for (i in 0 until node.childCount) {
             val child = node.getChild(i)
             sb.append(extractTextFromNode(child, depth + 1, maxDepth)).append(" ")
             child?.recycle() // Important to recycle!

             // Early exit if we have enough text
             if (sb.length > 1000) break
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
        // HEARTBEAT: Log image analysis trigger
        android.util.Log.d("Nupe", "Triggering Image Analysis...")

        // CRITICAL FIX: Check if analyzer is ready
        android.util.Log.d("Nupe", "Analyzer Status: ${if (imageAnalyzer.isReady) "Ready" else "Not Ready"}")

        if (!imageAnalyzer.isReady) {
            android.util.Log.e("Nupe", "ImageAnalyzer not initialized. Skipping analysis.")
            return
        }

        // CRITICAL PERFORMANCE FIX: Drop frame if already analyzing
        if (isAnalyzing) {
            android.util.Log.d("Nupe", "Analysis already in progress, dropping frame")
            return
        }

        // Throttling: Max once per 1 second
        val now = System.currentTimeMillis()
        if (now - lastImageScanTime < 1000L) {
            android.util.Log.d("Nupe", "Skipping analysis due to throttling (1s cooldown)")
            return
        }
        lastImageScanTime = now

        // Fix: Image Analyzer Logging
        android.util.Log.d("Nupe", "Attempting Screenshot...")

        // CRITICAL FIX: Set analyzing flag before screenshot to prevent concurrent analysis
        isAnalyzing = true

        // 1. Capture Bitmap (Android 11+ API) with Callback
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // CRITICAL FIX: Use background executor to avoid blocking Main thread
            val backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                backgroundExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        // This callback now runs on background thread
                        val bitmap = try {
                            val hardwareBitmap = screenshot.hardwareBuffer.let {
                                android.graphics.Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace)
                            }
                            // Copy to software bitmap for TFLite processing (Hardware bitmaps are often immutable/gpu-only)
                            // CRITICAL FIX: This expensive operation now runs on background thread
                            hardwareBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also {
                                hardwareBitmap?.recycle()
                                screenshot.hardwareBuffer.close()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Nupe", "Bitmap conversion failed", e)
                            null
                        }

                        // Shutdown the executor after use
                        backgroundExecutor.shutdown()

                        if (bitmap != null) {
                            processBitmap(bitmap)
                        } else {
                            // CRITICAL FIX: Reset analyzing flag on bitmap conversion failure
                            isAnalyzing = false
                            handleScreenshotFailure(-1)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.e("Nupe", "Screenshot failed with error code: $errorCode")
                        backgroundExecutor.shutdown()
                        // CRITICAL FIX: Reset analyzing flag on screenshot failure
                        isAnalyzing = false
                        handleScreenshotFailure(errorCode)
                    }
                }
            )
        } else {
            android.util.Log.e("Nupe", "Screenshot not supported on this Android version")
            // CRITICAL FIX: Reset analyzing flag when screenshot API not available
            isAnalyzing = false
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
        // HEARTBEAT: Log bitmap processing
        android.util.Log.d("Nupe", "Processing Bitmap: ${bitmap.width}x${bitmap.height}")

        // Note: isAnalyzing flag already set in triggerImageAnalysis()

        // CRITICAL PERFORMANCE FIX: Ensure execution on Dispatchers.Default (Background)
        scope.launch(Dispatchers.Default) {
            try {
                android.util.Log.d("Nupe", "Starting parallel image analysis...")
                val startTime = System.currentTimeMillis()

                // 2. PARALLEL Analysis (OCR + Visual) - Run concurrently, not sequentially!
                val ocrDeferred = async(Dispatchers.Default) {
                    try {
                        ocrAnalyzer.analyze(bitmap)
                    } catch(e: Exception) {
                        android.util.Log.e("Nupe", "OCR analysis failed", e)
                        false
                    }
                }

                val visualDeferred = async(Dispatchers.Default) {
                    try {
                        imageAnalyzer.analyze(bitmap) // OBJECTIVE 3: Use new analyze() method
                    } catch(e: Exception) {
                        android.util.Log.e("Nupe", "Visual analysis failed", e)
                        com.example.nupe.data.ml.AnalysisResult(false, false, 0f)
                    }
                }

                // 3. Smart Short-circuit & Split Logic
                val isTextPorn = ocrDeferred.await()
                val visualResult = visualDeferred.await()

                val duration = System.currentTimeMillis() - startTime
                android.util.Log.d("Nupe", "Analysis completed in ${duration}ms")

                // OBJECTIVE 3: Handle hardcore, softcore, and text separately
                if (visualResult.isHardcore) {
                    // CRITICAL: Only Visual Hardcore Nudity triggers the Nuclear Option (Block + Back)
                    android.util.Log.d("Nupe", "Hardcore Visual Porn Detected! Blocking.")
                    triggerBlock()
                } else if (visualResult.isSoftcore) {
                    // OBJECTIVE 3: Softcore (Sexy/Bikini) -> Send System Notification only
                    android.util.Log.d("Nupe", "Softcore/Suggestive Content Detected. Sending notification.")
                    withContext(Dispatchers.Main) {
                        notificationHelper.showSuggestiveContentNotification()
                    }
                    // DO NOT block or show bubble
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
            } finally {
                // CRITICAL PERFORMANCE FIX: Always clear flag in finally block
                bitmap.recycle()
                isAnalyzing = false
                android.util.Log.d("Nupe", "Analysis complete, ready for next frame")
            }
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
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.d("Nupe", "========================================")
        android.util.Log.d("Nupe", "Accessibility Service Connected!")
        android.util.Log.d("Nupe", "Nupe is now protecting you 24/7")
        android.util.Log.d("Nupe", "========================================")

        // Verify all dependencies are injected
        try {
            android.util.Log.d("Nupe", "Checking dependencies...")
            android.util.Log.d("Nupe", "TextAnalyzer: ${if (::textAnalyzer.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "ImageAnalyzer: ${if (::imageAnalyzer.isInitialized) "✓ Ready (Model: ${if (imageAnalyzer.isReady) "Loaded" else "Loading..."})" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "OcrAnalyzer: ${if (::ocrAnalyzer.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "OverlayManager: ${if (::overlayManager.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "RiskManager: ${if (::riskManager.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "QuranRepository: ${if (::quranRepository.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "NotificationHelper: ${if (::notificationHelper.isInitialized) "✓ Ready" else "✗ MISSING"}")
            android.util.Log.d("Nupe", "All dependencies initialized successfully!")
        } catch (e: Exception) {
            android.util.Log.e("Nupe", "CRITICAL: Dependency check failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.w("Nupe", "Service destroyed - This should not happen! System may have killed the service.")

        try {
            // CRITICAL FIX: Cancel all coroutines with proper cleanup
            serviceJob.cancel()

            // Only cleanup if dependencies are initialized
            if (::overlayManager.isInitialized) {
                overlayManager.hideBubble()
                overlayManager.hideBlock()
                overlayManager.hideBlur()
            }

            if (::riskManager.isInitialized) {
                riskManager.cleanup() // Clean up RiskManager's coroutines
            }

            android.util.Log.d("Nupe", "Service destroyed, all resources cleaned up")
        } catch (e: Exception) {
            android.util.Log.e("Nupe", "Error during service cleanup", e)
        }
    }

    override fun onInterrupt() {
        android.util.Log.w("Nupe", "Service interrupted - User may have disabled the service manually")

        try {
            // Handle interruption - clean up overlays
            if (::overlayManager.isInitialized) {
                overlayManager.hideBubble()
                overlayManager.hideBlock()
            }
        } catch (e: Exception) {
            android.util.Log.e("Nupe", "Error during service interrupt cleanup", e)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        android.util.Log.w("Nupe", "Service unbinding - Requesting rebind to stay alive")
        // Return true to request rebind when killed
        return true
    }
}

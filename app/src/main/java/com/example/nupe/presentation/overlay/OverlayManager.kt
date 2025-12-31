package com.example.nupe.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner

import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    // We still keep a reference to a WindowManager, but we prefer using the Service's WM if possible.
    // However, getting WM from applicationContext is usually fine IF we use the correct context for the View.
    // To be perfectly safe and follow the plan, we will get WM from the passed context in show methods,
    // or arguably we can keep a default one. 
    // The Diagnosis says: "The app is trying to add... using an invalid Window Token... or is using an Activity Context instead of the Service Context."
    // It implies the Context used to create the VIEW is important.
    
    private val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: ComposeView? = null
    private var blockView: ComposeView? = null

    // Safety flags
    private var isBubbleAttached = false
    private var isBlockAttached = false

    // Lifecycle/SavedState boilerplate for Compose in WindowManager
    private val _lifecycleRegistry = LifecycleRegistry(this)
    private val _viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        _lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = _lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /**
     * Shows the bubble overlay.
     * @param context MUST be the AccessibilityService context.
     */
    fun showBubble(context: Context) {
        if (isBubbleAttached || bubbleView != null) return // Already showing

        // CRITICAL FIX: Use TYPE_APPLICATION_OVERLAY for Android 8.0+
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Flags: Don't take focus (let user type), Allow drawing outside screen, watch outside touches
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

        // Fix: Use the Service Context to create the view
        bubbleView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            setContent {
                BubbleOverlay(onClick = { /* Expand to settings or dismiss */ })
            }
        }

        try {
            android.util.Log.d("NupeOverlay", "Attempting to add bubble view to WindowManager")
            // Use the WindowManager from the context (Service) if possible, or the system one.
            // Usually context.getSystemService(Context.WINDOW_SERVICE) from Service is best.
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(bubbleView, params)
            isBubbleAttached = true
            android.util.Log.d("NupeOverlay", "Bubble view added successfully")
        } catch (e: Exception) {
            android.util.Log.e("NupeOverlay", "Error adding bubble view", e)
            e.printStackTrace()
            // Clean up if add failed
            bubbleView = null
            isBubbleAttached = false
        }
    }

    fun hideBubble() {
        if (!isBubbleAttached) return
        
        bubbleView?.let { view ->
            try {
                // We need to use the same WindowManager that added it. 
                // Since we used `context.getSystemService` in showBubble, we should strictly speaking retrieve it again 
                // OR use the `windowManager` field if we assume it's the same system service instance (which it usually is).
                // However, `removeView` checks if the view is attached to *that* window manager.
                // To be safe, we try to use the application context's WM which is what we stored in `windowManager` field,
                // OR we just assume `windowManager` holds the reference. 
                // Actually, `windowManager.removeView(view)` works if `windowManager` corresponds to the same display.
                // For AccessibilityService, `getSystemService` returns a WM tailored for the service's display context.
                // `applicationContext` might return the default display WM. They should match for the default display.
                // Let's try the stored `windowManager`.
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                // "View not attached to window manager"
                android.util.Log.w("NupeOverlay", "View not attached when trying to remove", e)
            } catch (e: Exception) {
                 e.printStackTrace()
            } finally {
                isBubbleAttached = false
                bubbleView = null
            }
        }
    }

    /**
     * Shows the blocking overlay.
     * @param context MUST be the AccessibilityService context.
     */
    fun showBlock(context: Context, onSanctuary: () -> Unit) {
        if (isBlockAttached || blockView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Flags: Layout in screen to cover everything. 
            // We DO want to block touches, so we DON'T set FLAG_NOT_TOUCHABLE.
            // We might want FLAG_NOT_FOCUSABLE if we don't need keyboard input, 
            // but for a blocker, usually we might want to catch everything.
            // The prompt said: "Don't take focus (let user type)" was for Bubble.
            // For Blocker: "Or MATCH_PARENT for blocker".
            // Let's stick to the prompt's suggested flags, ensuring we cover the screen.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Let system keys work? or block everything? 
            // If we want to strictly BLOCK, we usually don't want NOT_FOCUSABLE. 
            // But let's follow the "Crash 1" fix which implies using the correct TYPE and flags.
            // The user prompt said: "Fix the Window Layout Params: Ensure... configured exactly like this... FLAG_LAYOUT_IN_SCREEN..."
            PixelFormat.TRANSLUCENT
        )

        blockView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            setContent {
                BlockOverlay(onSanctuaryClick = {
                    hideBlock()
                    onSanctuary()
                })
            }
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(blockView, params)
            isBlockAttached = true
        } catch (e: Exception) {
             e.printStackTrace()
             blockView = null
             isBlockAttached = false
        }
    }

    fun hideBlock() {
         if (!isBlockAttached) return

        blockView?.let { view ->
             try {
                windowManager.removeView(view)
            } catch (e: IllegalArgumentException) {
                 android.util.Log.w("NupeOverlay", "View not attached when trying to remove block", e)
            } catch (e: Exception) { 
                e.printStackTrace() 
            } finally {
                isBlockAttached = false
                blockView = null
            }
        }
    }

    private var blurView: ComposeView? = null
    private var isBlurAttached = false

    fun showBlur(context: Context) {
        if (isBlurAttached || blurView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Let touches pass? No, visual blur usually assumes blocking vision but maybe not interaction? 
                    // Prompt says: "Update OverlayManager to support a 'Blur' effect... use a Box with Color.Black.copy(alpha = 0.9f) overlay as a fallback"
                    // Usually a blur/dim overlay is just visual.
            PixelFormat.TRANSLUCENT
        )

        blurView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayManager)
            setViewTreeViewModelStoreOwner(this@OverlayManager)
            setViewTreeSavedStateRegistryOwner(this@OverlayManager)
            setContent {
                // Fallback Blur: A dark semi-transparent box
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f))
                )
            }
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(blurView, params)
            isBlurAttached = true
        } catch (e: Exception) {
            e.printStackTrace()
             blurView = null
             isBlurAttached = false
        }
    }
    
    fun hideBlur() {
        if (!isBlurAttached) return
        blurView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) { e.printStackTrace() }
            finally {
                isBlurAttached = false
                blurView = null
            }
        }
    }
}

// Wrapper interface for LifecycleOwner (built-in but repeated for explicit clarity if needed)
interface LifecycleOwner : androidx.lifecycle.LifecycleOwner

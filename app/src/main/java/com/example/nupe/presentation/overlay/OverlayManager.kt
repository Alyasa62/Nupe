package com.example.nupe.presentation.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
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
    @ApplicationContext private val context: Context
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: ComposeView? = null
    private var blockView: ComposeView? = null

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

    fun showBubble() {
        if (bubbleView != null) return // Already showing

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // or TYPE_APPLICATION_OVERLAY if targeting 26+ and using permission
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

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
            windowManager.addView(bubbleView, params)
            android.util.Log.d("NupeOverlay", "Bubble view added successfully")
        } catch (e: Exception) {
            android.util.Log.e("NupeOverlay", "Error adding bubble view", e)
            e.printStackTrace()
        }
    }

    fun hideBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { e.printStackTrace() }
            bubbleView = null
        }
    }

    fun showBlock(onSanctuary: () -> Unit) {
        if (blockView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Allow back touches? No, we want to block.
            PixelFormat.TRANSLUCENT
        )
        // Ensure we block touches
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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
            windowManager.addView(blockView, params)
        } catch (e: Exception) {
             e.printStackTrace()
        }
    }

    fun hideBlock() {
        blockView?.let {
             try {
                windowManager.removeView(it)
            } catch (e: Exception) { e.printStackTrace() }
            blockView = null
        }
    }
}

// Wrapper interface for LifecycleOwner (built-in but repeated for explicit clarity if needed)
interface LifecycleOwner : androidx.lifecycle.LifecycleOwner

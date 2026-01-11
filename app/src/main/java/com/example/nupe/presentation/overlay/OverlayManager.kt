package com.example.nupe.presentation.overlay

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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

    // CRITICAL FIX: Store WindowManager references per view to ensure consistency
    private var bubbleView: View? = null
    private var blockView: ComposeView? = null
    private var blurView: ComposeView? = null

    // CRITICAL FIX: Store the WindowManager instance used to add each view
    private var bubbleWindowManager: WindowManager? = null
    private var blockWindowManager: WindowManager? = null
    private var blurWindowManager: WindowManager? = null

    // Safety flags
    private var isBubbleAttached = false
    private var isBlockAttached = false
    private var isBlurAttached = false

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
        // FORCE UI VISIBILITY: Run on Main Thread
        MainScope().launch(Dispatchers.Main) {
            if (isBubbleAttached || bubbleView != null) return@launch // Already showing

            // FORCE UI: Fixed 200px size (not wrap_content)
            val params = WindowManager.LayoutParams(
                200, // Fixed width in pixels
                200, // Fixed height in pixels
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Flags: Don't take focus, allow touches to pass through
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // Allow touches to pass through!
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                // FORCE UI: Top-left corner is safest
                gravity = Gravity.TOP or Gravity.START
                y = 200 // Push down to avoid status bar
                x = 0

                // FORCE Z-ORDER: Ensure window is on top
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }

            // FORCE UI: Create NATIVE ANDROID VIEW (not Compose) for guaranteed visibility
            bubbleView = FrameLayout(context).apply {
                // FORCE VISIBILITY
                visibility = View.VISIBLE
                elevation = 100f

                // CIRCULAR SEMI-TRANSPARENT RED BACKGROUND
                val circleDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AndroidColor.argb(180, 255, 0, 0)) // Semi-transparent red (70% opacity)
                    setStroke(4, AndroidColor.WHITE) // White border
                }
                background = circleDrawable

                // Add white "!" text
                val textView = TextView(context).apply {
                    text = "!"
                    textSize = 40f
                    setTextColor(AndroidColor.WHITE)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(textView)

                // CRITICAL: Force measure and layout BEFORE adding to WindowManager
                val size = 200
                measure(
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, size, size)

                android.util.Log.d("NupeOverlay", "Created native Android view: ${this.measuredWidth}x${this.measuredHeight}")
            }

            try {
                android.util.Log.d("NupeOverlay", "Attempting to add bubble view to WindowManager")
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                bubbleWindowManager = wm
                wm.addView(bubbleView, params)
                isBubbleAttached = true
                android.util.Log.d("NupeOverlay", "Bubble view added successfully")
            } catch (e: Exception) {
                android.util.Log.e("NupeOverlay", "Error adding bubble view", e)
                e.printStackTrace()
                // Clean up if add failed
                bubbleView = null
                bubbleWindowManager = null
                isBubbleAttached = false
            }
        }
    }

    fun hideBubble() {
        if (!isBubbleAttached) return

        bubbleView?.let { view ->
            try {
                if (view.parent != null) {
                    // CRITICAL FIX: Use the same WindowManager instance that added the view
                    bubbleWindowManager?.removeView(view)
                }
            } catch (e: Exception) {
                 android.util.Log.e("NupeOverlay", "Error removing bubble view", e)
            } finally {
                isBubbleAttached = false
                bubbleView = null
                bubbleWindowManager = null
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
            // CRITICAL FIX: Store the WindowManager instance for consistent removal
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            blockWindowManager = wm
            wm.addView(blockView, params)
            isBlockAttached = true
        } catch (e: Exception) {
             e.printStackTrace()
             blockView = null
             blockWindowManager = null
             isBlockAttached = false
        }
    }

    fun hideBlock() {
         if (!isBlockAttached) return

        blockView?.let { view ->
             try {
                if (view.parent != null) {
                    // CRITICAL FIX: Use the same WindowManager instance that added the view
                    blockWindowManager?.removeView(view)
                }
            } catch (e: Exception) {
                android.util.Log.e("NupeOverlay", "Error removing block view", e)
            } finally {
                isBlockAttached = false
                blockView = null
                blockWindowManager = null
            }
        }
    }

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
            // CRITICAL FIX: Store the WindowManager instance for consistent removal
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            blurWindowManager = wm
            wm.addView(blurView, params)
            isBlurAttached = true
        } catch (e: Exception) {
            e.printStackTrace()
             blurView = null
             blurWindowManager = null
             isBlurAttached = false
        }
    }

    fun hideBlur() {
        if (!isBlurAttached) return
        blurView?.let { view ->
            try {
                // CRITICAL FIX: Use the same WindowManager instance that added the view
                blurWindowManager?.removeView(view)
            } catch (e: Exception) { e.printStackTrace() }
            finally {
                isBlurAttached = false
                blurView = null
                blurWindowManager = null
            }
        }
    }
}

// Wrapper interface for LifecycleOwner (built-in but repeated for explicit clarity if needed)
interface LifecycleOwner : androidx.lifecycle.LifecycleOwner

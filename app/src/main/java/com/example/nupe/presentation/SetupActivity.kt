package com.example.nupe.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.nupe.presentation.setup.SetupScreen
import com.example.nupe.presentation.theme.NupeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NupeTheme {
                SetupWizard(
                    onSetupComplete = {
                        // Navigate to MainActivity
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // This will trigger permission rechecks when user returns from Settings
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupWizard(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Permission states
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isOverlayPermissionGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(checkBatteryOptimization(context)) }

    // Calculate initial page based on first missing permission
    val initialPage = remember {
        when {
            !isAccessibilityEnabled -> 0
            !isOverlayPermissionGranted -> 1
            !isBatteryOptimizationIgnored -> 2
            else -> 3 // All granted, show completion
        }
    }

    // Pager state for 4 steps (0-3)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { 4 }
    )

    // Auto-check permissions periodically
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000) // Check every second
            isAccessibilityEnabled = checkAccessibilityEnabled(context)
            isOverlayPermissionGranted = Settings.canDrawOverlays(context)
            isBatteryOptimizationIgnored = checkBatteryOptimization(context)

            // Auto-advance to next step when permission is granted
            when (pagerState.currentPage) {
                0 -> if (isAccessibilityEnabled) {
                    scope.launch { pagerState.animateScrollToPage(1) }
                }
                1 -> if (isOverlayPermissionGranted) {
                    scope.launch { pagerState.animateScrollToPage(2) }
                }
                2 -> if (isBatteryOptimizationIgnored) {
                    scope.launch { pagerState.animateScrollToPage(3) }
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false, // Disable manual scrolling - auto-advance only
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> SetupScreen(
                    stepNumber = 1,
                    totalSteps = 3,
                    title = "Turn on Nupe Protection",
                    description = "Nupe needs Accessibility to detect and block bad content. Find 'Nupe' in the list and turn it ON.",
                    isPermissionGranted = isAccessibilityEnabled,
                    buttonText = "GO TO ACCESSIBILITY SETTINGS",
                    onActionClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                1 -> SetupScreen(
                    stepNumber = 2,
                    totalSteps = 3,
                    title = "Enable Visual Warnings",
                    description = "Nupe needs to show the Red Bubble warning over other apps.",
                    isPermissionGranted = isOverlayPermissionGranted,
                    buttonText = "GRANT OVERLAY PERMISSION",
                    onActionClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                2 -> SetupScreen(
                    stepNumber = 3,
                    totalSteps = 3,
                    title = "Keep Nupe Alive",
                    description = "Your phone will kill Nupe to save battery. You must disable battery optimization for Nupe to work 24/7.",
                    isPermissionGranted = isBatteryOptimizationIgnored,
                    buttonText = "DISABLE BATTERY OPTIMIZATION",
                    onActionClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to general battery optimization settings
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )

                3 -> SetupScreen(
                    stepNumber = 4,
                    totalSteps = 3,
                    title = "You are Protected!",
                    description = "All permissions granted. Nupe is now ready to protect you from explicit content 24/7.",
                    isPermissionGranted = true,
                    buttonText = "START USING NUPE",
                    onActionClick = onSetupComplete,
                    isCompletionScreen = true
                )
            }
        }
    }
}

/**
 * Check if Accessibility Service is enabled
 */
private fun checkAccessibilityEnabled(context: Context): Boolean {
    val accessibilityEnabled = try {
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
    } catch (e: Settings.SettingNotFoundException) {
        0
    }

    if (accessibilityEnabled != 1) return false

    // Check if our specific service is enabled
    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val expectedServiceName = "${context.packageName}/com.example.nupe.presentation.service.NupeAccessibilityService"
    return services.contains(expectedServiceName)
}

/**
 * Check if battery optimization is disabled for this app
 */
private fun checkBatteryOptimization(context: Context): Boolean {
    return try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

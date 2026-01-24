package com.example.nupe.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.nupe.presentation.theme.NupeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRITICAL: Check all permissions BEFORE showing UI
        if (!areAllPermissionsGranted()) {
            // Redirect to SetupActivity if any permission is missing
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            NupeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when user returns from settings
        if (!areAllPermissionsGranted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    /**
     * Check if all required permissions are granted
     */
    private fun areAllPermissionsGranted(): Boolean {
        val isAccessibilityEnabled = checkAccessibilityEnabled(this)
        val isOverlayGranted = Settings.canDrawOverlays(this)
        val isBatteryOptimizationIgnored = checkBatteryOptimization(this)

        return isAccessibilityEnabled && isOverlayGranted && isBatteryOptimizationIgnored
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
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NupeTheme {
        Greeting("Android")
    }
}
package com.example.nupe.presentation.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nupe.R

@Composable
fun SetupScreen(
    stepNumber: Int,
    totalSteps: Int,
    title: String,
    description: String,
    isPermissionGranted: Boolean,
    buttonText: String,
    onActionClick: () -> Unit,
    isCompletionScreen: Boolean = false
) {
    // Deep Blue/Black Gradient Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E27), // Deep navy blue
                        Color(0xFF000000), // Black
                        Color(0xFF0D1B2A)  // Dark blue
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Progress & Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // Progress Indicator
                if (!isCompletionScreen) {
                    StepProgressIndicator(
                        currentStep = stepNumber,
                        totalSteps = totalSteps,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )
                }

                // Icon
                IconContainer(
                    icon = getIconForStep(stepNumber),
                    isGranted = isPermissionGranted,
                    isCompletionScreen = isCompletionScreen
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                Text(
                    text = description,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Middle Section: Visual Placeholder
            // TODO: Replace with actual screenshot images
            ScreenshotPlaceholder(stepNumber)

            // Bottom Section: Action Button & Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Permission Status Indicator
                AnimatedVisibility(
                    visible = isPermissionGranted && !isCompletionScreen,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    PermissionGrantedBadge()
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Button
                ActionButton(
                    text = buttonText,
                    isEnabled = !isPermissionGranted || isCompletionScreen,
                    onClick = onActionClick
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun StepProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(totalSteps) { index ->
            val isActive = index + 1 <= currentStep
            Box(
                modifier = Modifier
                    .width(if (isActive) 60.dp else 40.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isActive) Color(0xFF00D9FF) // Cyan
                        else Color.White.copy(alpha = 0.2f)
                    )
            )
        }
    }
}

@Composable
private fun IconContainer(
    icon: ImageVector,
    isGranted: Boolean,
    isCompletionScreen: Boolean
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(
                if (isCompletionScreen || isGranted) {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00FF88), // Green
                            Color(0xFF00AA55)
                        )
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1E3A8A), // Blue
                            Color(0xFF0F172A)  // Dark blue
                        )
                    )
                }
            )
    ) {
        Icon(
            imageVector = if (isGranted && !isCompletionScreen) Icons.Default.Check else icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(56.dp)
        )
    }
}

@Composable
private fun ScreenshotPlaceholder(stepNumber: Int) {
    // Screenshots showing users exactly what to click in settings
    val drawableRes = when (stepNumber) {
        1 -> R.drawable.setup_step1_accessibility
        2 -> R.drawable.setup_step2_overlay
        3 -> R.drawable.setup_step3_battery
        else -> null
    }

    if (drawableRes != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.05f)
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = "Setup Step $stepNumber Screenshot",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    } else {
        // Fallback for completion screen (step 4) - no screenshot needed
        Spacer(modifier = Modifier.height(280.dp))
    }
}

@Composable
private fun PermissionGrantedBadge() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF00FF88).copy(alpha = 0.2f)
        ),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF00FF88),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Permission Granted - Moving to Next Step...",
                color = Color(0xFF00FF88),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = isEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00D9FF), // Cyan
            disabledContainerColor = Color.White.copy(alpha = 0.2f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp
        )
    ) {
        Text(
            text = if (isEnabled) text else "WAITING FOR PERMISSION...",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (isEnabled) Color.Black else Color.White.copy(alpha = 0.5f),
            letterSpacing = 1.sp
        )
    }
}

/**
 * Get the appropriate icon for each setup step
 */
private fun getIconForStep(stepNumber: Int): ImageVector {
    return when (stepNumber) {
        1 -> Icons.Default.Settings          // Accessibility Service
        2 -> Icons.Default.Notifications     // Overlay Permission
        3 -> Icons.Default.Info              // Battery Optimization
        4 -> Icons.Default.Lock              // Completion - Protected
        else -> Icons.Default.Settings
    }
}

package com.example.nupe.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.nupe.presentation.theme.NupeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SanctuaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NupeTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF121212)), // Dark mode nature theme background
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🌿 Sanctuary",
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.Green
                    )
                }
            }
        }
    }
}

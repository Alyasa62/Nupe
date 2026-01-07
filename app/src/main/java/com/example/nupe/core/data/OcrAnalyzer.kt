package com.example.nupe.core.data

import android.graphics.Bitmap
import android.util.Log
import com.example.nupe.core.CoreConstants
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class OcrAnalyzer @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(bitmap: Bitmap): Boolean {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            
            val detectedText = result.text.lowercase()
            // Log.d("NupeOCR", "Detected Text: $detectedText") // Verbose logging - disable in prod if needed

            CoreConstants.NUCLEAR_KEYWORDS.any { keyword ->
                detectedText.contains(keyword.lowercase())
            }
        } catch (e: Exception) {
            Log.e("NupeOCR", "OCR Failed", e)
            false
        }
    }
}

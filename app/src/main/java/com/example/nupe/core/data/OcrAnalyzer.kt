package com.example.nupe.core.data

import android.graphics.Bitmap
import android.util.Log
import com.example.nupe.data.text.TextAnalyzer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class OcrAnalyzer @Inject constructor(
    private val textAnalyzer: TextAnalyzer
) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun analyze(bitmap: Bitmap): Boolean {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val detectedText = result.text

            // HEARTBEAT: Log OCR detected text
            Log.d("Nupe", "OCR Text: $detectedText")

            // Use TextAnalyzer to check for bad words (checks both BAD and NUCLEAR keywords)
            textAnalyzer.analyze(detectedText)
        } catch (e: Exception) {
            Log.e("NupeOCR", "OCR Failed", e)
            false
        }
    }
}

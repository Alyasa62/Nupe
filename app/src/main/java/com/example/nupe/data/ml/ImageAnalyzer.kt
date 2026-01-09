package com.example.nupe.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.example.nupe.core.CoreConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OBJECTIVE 3: Result data class for soft porn logic
 */
data class AnalysisResult(
    val isHardcore: Boolean,
    val isSoftcore: Boolean,
    val score: Float = 0f
)

@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var interpreter: Interpreter? = null
    private val inputImageWidth = 224
    private val inputImageHeight = 224
    // Float32 requires 4 bytes per channel
    private val modelInputSize = inputImageWidth * inputImageHeight * 3 * 4 

    init {
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        try {
            val modelBuffer = loadModelFile(CoreConstants.MODEL_FILENAME)

            // CRITICAL PERFORMANCE FIX: Try hardware acceleration first, fallback to CPU
            val options = Interpreter.Options().apply {
                setNumThreads(4) // Use 4 threads for CPU fallback

                // Try GPU delegate first (most compatible with float models)
                var hardwareAccelEnabled = false

                // CRITICAL FIX: Check device compatibility before using GPU delegate
                val compatibilityList = CompatibilityList()
                if (compatibilityList.isDelegateSupportedOnThisDevice) {
                    try {
                        // Use default GPU delegate options for maximum compatibility
                        val gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                        android.util.Log.d("Nupe", "GPU delegate enabled for hardware acceleration")
                        hardwareAccelEnabled = true
                    } catch (e: Exception) {
                        android.util.Log.w("Nupe", "GPU delegate initialization failed: ${e.message}")
                    }
                } else {
                    android.util.Log.d("Nupe", "GPU delegate not supported on this device")
                }

                // If GPU failed, try NNAPI (Neural Network API) delegate
                if (!hardwareAccelEnabled) {
                    try {
                        val nnApiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate()
                        addDelegate(nnApiDelegate)
                        android.util.Log.d("Nupe", "NNAPI delegate enabled for hardware acceleration")
                        hardwareAccelEnabled = true
                    } catch (e: Exception) {
                        android.util.Log.w("Nupe", "NNAPI delegate not available: ${e.message}")
                    }
                }

                if (!hardwareAccelEnabled) {
                    android.util.Log.w("Nupe", "No hardware acceleration available, using CPU with 4 threads")
                }
            }

            interpreter = Interpreter(modelBuffer, options)
            android.util.Log.d("Nupe", "NSFW Float32 Model loaded successfully.")
        } catch (e: Exception) {
            android.util.Log.e("Nupe", "CRITICAL: AI Model failed to load. Image detection disabled.", e)
             interpreter = null
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            throw Exception("Model file '\$modelName' not found in assets.")
        }
    }

    /**
     * OBJECTIVE 3: Updated to return AnalysisResult with hardcore and softcore detection
     */
    suspend fun analyze(bitmap: Bitmap): AnalysisResult = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext AnalysisResult(false, false, 0f)

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // Output: [1, 5] Probabilities
        // 0=Drawing, 1=Hentai, 2=Neutral, 3=Porn, 4=Sexy
        val outputBuffer = Array(1) { FloatArray(5) }

        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext AnalysisResult(false, false, 0f)
        } finally {
             resizedBitmap.recycle()
        }

        // Scoring Logic
        val hentaiScore = outputBuffer[0][1]
        val pornScore = outputBuffer[0][3]
        val sexyScore = outputBuffer[0][4]

        val totalExplicitScore = hentaiScore + pornScore

        // OBJECTIVE 3: Hardcore vs Softcore logic
        val isHardcore = totalExplicitScore > 0.70f
        val isSoftcore = (sexyScore > 0.80f) && !isHardcore

        android.util.Log.d("Nupe", "Scores - Hentai: $hentaiScore, Porn: $pornScore, Sexy: $sexyScore, Hardcore: $isHardcore, Softcore: $isSoftcore")

        return@withContext AnalysisResult(
            isHardcore = isHardcore,
            isSoftcore = isSoftcore,
            score = if (isHardcore) totalExplicitScore else sexyScore
        )
    }

    /**
     * Legacy method for backward compatibility - returns simple float score
     */
    @Deprecated("Use analyze() instead for better detection")
    suspend fun analyzeImage(bitmap: Bitmap): Float {
        val result = analyze(bitmap)
        return if (result.isHardcore) result.score else 0f
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputImageWidth) {
            for (j in 0 until inputImageHeight) {
                val value = intValues[pixel++]

                // Normalization: 0..255 -> 0.0..1.0
                byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f) // R
                byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)  // G
                byteBuffer.putFloat((value and 0xFF) / 255.0f)         // B
            }
        }
        return byteBuffer
    }
}

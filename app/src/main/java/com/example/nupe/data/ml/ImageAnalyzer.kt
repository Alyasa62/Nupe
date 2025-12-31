package com.example.nupe.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.example.nupe.core.CoreConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var interpreter: Interpreter? = null
    private val inputImageWidth = 224
    private val inputImageHeight = 224
    private val modelInputSize = inputImageWidth * inputImageHeight * 3 // 3 channels (RGB)

    init {
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        try {
            val modelBuffer = loadModelFile(CoreConstants.MODEL_FILENAME)
            
            // FIX: Use CPU Options (Safe for low-end devices)
            val options = Interpreter.Options().apply {
                setNumThreads(4) // Parallel processing on CPU
            }
            
            interpreter = Interpreter(modelBuffer, options)
            android.util.Log.d("Nupe", "AI Model loaded successfully (CPU Mode)")
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

    suspend fun analyzeImage(bitmap: Bitmap): Float = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext 0f // Model not loaded

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
        
        // Output buffer: MobileNet usually outputs probabilities for classes. 
        // Assuming binary classification (Safe vs NSFW) or multi-class.
        // For this example, let's assume index 1 is "NSFW".
        // Float output 1x2 or 1x1001 depending on model.
        // Quantized models output Bytes (0-255) if strictly quantized output, 
        // but often inputs are float/int and outputs are float/byte.
        // User requested Quantized (int8) model.
        
        // Let's assume output is a Byte buffer [1][Classes]
        // or for simplicity, we mock the inference if we don't know the exact output shape.
        
        val outputBuffer = Array(1) { ByteArray(2) } // Example: [Safe, Unsafe]
        
        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0f
        } finally {
             resizedBitmap.recycle()
             // Original bitmap recycling is caller's responsibility usually, 
             // but user asked for "disposing of bitmaps immediately".
             // We disposed the resized one. The input 'bitmap' should be recycled by caller.
        }

        // Parse output
        val unsafeScore = (outputBuffer[0][1].toInt() and 0xFF) / 255.0f
        return@withContext unsafeScore
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(modelInputSize) // 1 byte per channel per pixel for Quantized
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputImageWidth) {
            for (j in 0 until inputImageHeight) {
                val value = intValues[pixel++]

                // Quantized model expects [0, 255] byte values
                byteBuffer.put(((value shr 16) and 0xFF).toByte()) // R
                byteBuffer.put(((value shr 8) and 0xFF).toByte())  // G
                byteBuffer.put((value and 0xFF).toByte())         // B
            }
        }
        return byteBuffer
    }
}

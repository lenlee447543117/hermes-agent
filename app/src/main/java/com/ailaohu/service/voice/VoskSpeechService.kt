package com.ailaohu.service.voice

import android.content.Context
import android.util.Log
import com.ailaohu.service.termux.TermuxBridge
import com.ailaohu.service.termux.TermuxCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class VoskResult(
    val text: String,
    val confidence: Float = 0f,
    val isFinal: Boolean = false
)

@Singleton
class VoskSpeechService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termuxBridge: TermuxBridge
) {
    companion object {
        private const val TAG = "VoskSpeech"
        private const val MODEL_DIR = "vosk-model-small-cn"
    }

    private var model: Model? = null
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized && model != null) return true

        return try {
            val modelPath = File(context.filesDir, MODEL_DIR)
            if (!modelPath.exists() || !modelPath.isDirectory || modelPath.listFiles()?.isEmpty() != false) {
                Log.w(TAG, "Vosk model not found at ${modelPath.absolutePath}")
                Log.i(TAG, "Download: https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip")
                Log.i(TAG, "Extract to: ${modelPath.absolutePath}")
                return false
            }

            model = Model(modelPath.absolutePath)
            initialized = true
            Log.i(TAG, "Vosk model loaded from ${modelPath.absolutePath}")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Vosk native library not found: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed: ${e.message}")
            false
        }
    }

    fun isAvailable(): Boolean = initialized && model != null

    suspend fun recognizeFile(wavPath: String): VoskResult? = withContext(Dispatchers.IO) {
        if (!initialize()) {
            Log.w(TAG, "Vosk not available, falling back to Termux STT")
            return@withContext recognizeViaTermux()
        }

        try {
            val fis = FileInputStream(wavPath)
            fis.skip(44)

            val recognizer = Recognizer(model!!, 16000f)
            val buffer = ByteArray(4000)

            while (true) {
                val bytesRead = fis.read(buffer)
                if (bytesRead <= 0) break
                recognizer.acceptWaveForm(buffer, bytesRead)
            }

            val resultJson = recognizer.finalResult
            fis.close()
            recognizer.close()

            val json = JSONObject(resultJson)
            val text = json.optString("text", "")
            Log.d(TAG, "Vosk recognized: $text")

            if (text.isNotEmpty()) {
                VoskResult(text = text, isFinal = true)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk recognize failed: ${e.message}")
            recognizeViaTermux()
        }
    }

    private suspend fun recognizeViaTermux(): VoskResult? {
        if (!termuxBridge.isTermuxAvailable()) {
            Log.w(TAG, "Termux not available for STT fallback")
            return null
        }

        val result = termuxBridge.executeCommand(TermuxCommand("SPEECH_TO_TEXT"))
        return if (result.success && result.output.isNotEmpty()) {
            VoskResult(text = result.output, isFinal = true)
        } else {
            null
        }
    }

    fun createRecognizer(sampleRate: Float = 16000f): Recognizer? {
        if (!initialize()) return null
        return try {
            Recognizer(model!!, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Create recognizer failed: ${e.message}")
            null
        }
    }

    fun destroy() {
        model?.close()
        model = null
        initialized = false
        Log.i(TAG, "Vosk service destroyed")
    }
}

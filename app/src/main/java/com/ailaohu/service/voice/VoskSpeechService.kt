package com.ailaohu.service.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

        private const val SAMPLE_RATE = 16000
        private const val SILENCE_THRESHOLD_DB = -35
        private const val MIN_SPEECH_DURATION_MS = 300
        private const val SILENCE_TIMEOUT_MS = 1500
        private const val WAKE_WORD = "沪沪"

        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var model: Model? = null
    private var initialized = false
    private var isListening = false
    private var listeningThread: Thread? = null
    private var lastPartialResult: String = ""

    private val amplitudeThreshold: Int
        get() {
            val refAmplitude = 32767.0
            return (refAmplitude * Math.pow(10.0, SILENCE_THRESHOLD_DB / 20.0)).toInt()
        }

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

            val recognizer = Recognizer(model!!, SAMPLE_RATE.toFloat())
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
            val confidence = json.optDouble("confidence", 0.0).toFloat()
            Log.d(TAG, "Vosk recognized: $text (confidence: $confidence)")

            if (text.isNotEmpty()) {
                VoskResult(text = text, confidence = confidence, isFinal = true)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk recognize failed: ${e.message}")
            recognizeViaTermux()
        }
    }

    fun startStreamingRecognition(
        onPartialResult: (String) -> Unit,
        onFinalResult: (VoskResult) -> Unit,
        onWakeWordDetected: (() -> Unit)? = null
    ) {
        if (!initialize() || isListening) return

        isListening = true
        listeningThread = Thread {
            var recognizer: Recognizer? = null
            var audioRecord: AudioRecord? = null

            try {
                recognizer = Recognizer(model!!, SAMPLE_RATE.toFloat())
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * BUFFER_SIZE_FACTOR

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize / 2)

                var isSpeaking = false
                var speechStartTime = 0L
                var silenceStartTime = 0L
                var hasSpeech = false

                Log.i(TAG, "Streaming recognition started (VAD: -${-SILENCE_THRESHOLD_DB}dB, min=${MIN_SPEECH_DURATION_MS}ms, silence=${SILENCE_TIMEOUT_MS}ms)")

                while (isListening) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead <= 0) continue

                    val amplitude = calculateAmplitude(buffer, bytesRead)
                    val isVoiceActive = amplitude > amplitudeThreshold

                    val now = System.currentTimeMillis()

                    if (isVoiceActive) {
                        if (!isSpeaking) {
                            isSpeaking = true
                            speechStartTime = now
                            Log.d(TAG, "VAD: Speech started")
                        }
                        silenceStartTime = 0
                        hasSpeech = true
                    } else if (isSpeaking) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = now
                        }
                        val silenceDuration = now - silenceStartTime
                        if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                            val speechDuration = now - speechStartTime
                            isSpeaking = false

                            if (speechDuration >= MIN_SPEECH_DURATION_MS) {
                                val finalResult = recognizer?.finalResult ?: ""
                                val json = JSONObject(finalResult)
                                val text = json.optString("text", "")
                                val confidence = json.optDouble("confidence", 0.0).toFloat()

                                if (text.isNotEmpty()) {
                                    Log.d(TAG, "VAD: Final result: $text")
                                    onFinalResult(VoskResult(text, confidence, true))
                                }
                            } else {
                                Log.d(TAG, "VAD: Speech too short (${speechDuration}ms < ${MIN_SPEECH_DURATION_MS}ms), discarding")
                            }

                            recognizer?.close()
                            recognizer = Recognizer(model!!, SAMPLE_RATE.toFloat())
                            hasSpeech = false
                        }
                    }

                    if (bytesRead > 0) {
                        recognizer?.acceptWaveForm(buffer, bytesRead)

                        while (true) {
                            val partial = recognizer?.partialResult ?: ""
                            val partialJson = JSONObject(partial)
                            val partialText = partialJson.optString("partial", "")
                            if (partialText.isNotEmpty() && partialText != lastPartialResult) {
                                lastPartialResult = partialText
                                onPartialResult(partialText)

                                if (onWakeWordDetected != null && partialText.contains(WAKE_WORD)) {
                                    Log.i(TAG, "Wake word detected: $WAKE_WORD")
                                    onWakeWordDetected()
                                }
                            } else {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming recognition error: ${e.message}")
            } finally {
                recognizer?.close()
                audioRecord?.let {
                    try {
                        it.stop()
                        it.release()
                    } catch (_: Exception) {}
                }
                isListening = false
                Log.i(TAG, "Streaming recognition stopped")
            }
        }.apply {
            name = "VoskStreaming"
            isDaemon = true
            start()
        }
    }

    fun stopStreamingRecognition() {
        isListening = false
        listeningThread?.interrupt()
        listeningThread = null
        lastPartialResult = ""
    }

    private fun calculateAmplitude(buffer: ShortArray, length: Int): Int {
        var sum = 0L
        var max = 0
        for (i in 0 until length) {
            val abs = Math.abs(buffer[i].toInt())
            sum += abs
            if (abs > max) max = abs
        }
        return if (length > 0) (sum / length).toInt() else 0
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

    fun createRecognizer(sampleRate: Float = SAMPLE_RATE.toFloat()): Recognizer? {
        if (!initialize()) return null
        return try {
            Recognizer(model!!, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Create recognizer failed: ${e.message}")
            null
        }
    }

    fun destroy() {
        stopStreamingRecognition()
        model?.close()
        model = null
        initialized = false
        Log.i(TAG, "Vosk service destroyed")
    }
}

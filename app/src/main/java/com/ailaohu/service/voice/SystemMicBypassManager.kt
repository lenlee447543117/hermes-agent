package com.ailaohu.service.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class SystemMicBypassManager(private val context: Context) {
    companion object {
        private const val TAG = "SystemMicBypass"
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    @SuppressLint("MissingPermission")
    fun startRecording(outputFile: File): Boolean {
        if (isRecording) return false

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }

            val sessionId = audioRecord!!.audioSessionId
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hardware effects failed to bind: ${e.message}")
            }

            audioRecord!!.startRecording()
            isRecording = true

            recordingThread = Thread({
                writePcmToWav(outputFile)
            }, "BypassRecordingThread")
            recordingThread!!.start()

            Log.i(TAG, "VoIP Bypass recording started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Start bypass recording failed", e)
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            recordingThread?.join(500)

            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
            audioRecord = null

            aec?.release()
            ns?.release()
            aec = null
            ns = null

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "VoIP Bypass recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop bypass recording failed", e)
        }
    }

    private fun writePcmToWav(file: File) {
        var fos: FileOutputStream? = null
        val buffer = ByteArray(bufferSize)

        try {
            fos = FileOutputStream(file)
            writeWavHeader(fos, 0)

            var totalPayloadSize = 0

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read != null && read > 0) {
                    val rms = calculateRms(buffer, read)
                    if (rms < 50.0) {
                        Log.d(TAG, "Bypass: Silence detected (RMS $rms)")
                    }
                    fos.write(buffer, 0, read)
                    totalPayloadSize += read
                } else if (read != null && read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }
            fos.close()
            if (totalPayloadSize > 0) {
                updateWavHeader(file, totalPayloadSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing bypass file", e)
            fos?.close()
        }
    }

    private fun calculateRms(buffer: ByteArray, size: Int): Double {
        var sumSq = 0.0
        var i = 0
        while (i + 1 < size) {
            var sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            if (sample > 32767) sample -= 65536
            sumSq += sample.toDouble() * sample.toDouble()
            i += 2
        }
        return sqrt(sumSq / (size / 2))
    }

    private fun writeWavHeader(fos: FileOutputStream, payloadSize: Int) {
        val totalSize = payloadSize + 36
        val byteRate = sampleRate * 2

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(payloadSize)

        fos.write(header.array())
    }

    private fun updateWavHeader(file: File, payloadSize: Int) {
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(4)
            val totalSize = payloadSize + 36
            val sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalSize).array()
            raf.write(sizeBuffer)

            raf.seek(40)
            val dataSizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadSize).array()
            raf.write(dataSizeBuffer)
        } finally {
            raf.close()
        }
    }
}

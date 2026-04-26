package com.ailaohu.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 标准 WAV 录音机
 * 16kHz 单声道 16-bit PCM，自动添加 WAV 头部
 */
class WavRecorder(private val context: Context) {
    companion object {
        private const val TAG = "WavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val pcmStream = ByteArrayOutputStream()

    /**
     * 开始录音
     */
    fun start(): Boolean {
        if (!checkPermission()) {
            Log.e(TAG, "没有麦克风权限")
            return false
        }

        pcmStream.reset()
        isRecording = true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufferSize * 2
            )
            audioRecord?.startRecording()

            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        pcmStream.write(buffer, 0, read)
                    }
                }
            }.start()

            Log.d(TAG, "开始录音")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}", e)
            isRecording = false
            return false
        }
    }

    /**
     * 停止录音并获取 WAV 格式数据
     */
    suspend fun stopAndGetWav(): ByteArray? = withContext(Dispatchers.IO) {
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val pcmData = pcmStream.toByteArray()
            Log.d(TAG, "录音停止，PCM 数据大小: ${pcmData.size} 字节")

            if (pcmData.isEmpty()) {
                Log.w(TAG, "录音数据为空")
                return@withContext null
            }

            return@withContext addWavHeader(pcmData)
        } catch (e: Exception) {
            Log.e(TAG, "录音停止失败: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * 给 PCM 数据添加 WAV 头部
     */
    private fun addWavHeader(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 2 // 16 bit mono
        val header = ByteArray(44)

        // RIFF 标识
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // 总数据长度
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()

        // WAVE 标识
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt 标识
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // 格式块大小
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // 格式类型 (PCM)
        header[20] = 1
        header[21] = 0

        // 声道数
        header[22] = 1
        header[23] = 0

        // 采样率
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = (SAMPLE_RATE shr 8 and 0xff).toByte()
        header[26] = (SAMPLE_RATE shr 16 and 0xff).toByte()
        header[27] = (SAMPLE_RATE shr 24 and 0xff).toByte()

        // 字节率
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()

        // 块对齐
        header[32] = 2
        header[33] = 0

        // 位深度
        header[34] = 16
        header[35] = 0

        // data 标识
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // 数据长度
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = (pcmData.size shr 8 and 0xff).toByte()
        header[42] = (pcmData.size shr 16 and 0xff).toByte()
        header[43] = (pcmData.size shr 24 and 0xff).toByte()

        val wavStream = ByteArrayOutputStream()
        wavStream.write(header)
        wavStream.write(pcmData)

        val wavData = wavStream.toByteArray()
        Log.d(TAG, "WAV 文件生成成功，总大小: ${wavData.size} 字节")

        return wavData
    }

    /**
     * 检查麦克风权限
     */
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}

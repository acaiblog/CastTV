package com.casttv.sender.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.OutputStream
import java.net.Socket

/**
 * 系统音频采集管理器
 *
 * 使用 AudioPlaybackCapture API (Android 10+) 采集系统音频
 * 通过 TCP Socket 实时发送给电视端播放
 *
 * 使用方式：
 * 1. setTargetTv(ip, port) 设置电视地址
 * 2. startCapture(mediaProjection) 开始采集
 * 3. stopCapture() 停止采集
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isCapturing = false

    private var tvIp: String = ""
    private var tvAudioPort: Int = 0

    /**
     * 设置电视端音频接收地址
     */
    fun setTargetTv(ip: String, audioPort: Int) {
        tvIp = ip
        tvAudioPort = audioPort
        Log.d(TAG, "电视音频接收地址: $ip:$audioPort")
    }

    /**
     * 开始采集并发送系统音频
     * @param mediaProjection MediaProjection 对象（来自 MediaProjectionManager 授权）
     */
    fun startCapture(mediaProjection: android.media.projection.MediaProjection) {
        if (isCapturing) {
            Log.w(TAG, "已在采集中")
            return
        }
        if (tvIp.isEmpty() || tvAudioPort == 0) {
            Log.e(TAG, "电视地址未设置，请先调用 setTargetTv()")
            return
        }

        captureThread = Thread {
            try {
                // 连接电视端音频接收服务器
                Log.d(TAG, "连接电视音频端口: $tvIp:$tvAudioPort")
                socket = Socket(tvIp, tvAudioPort)
                outputStream = socket!!.getOutputStream()
                Log.d(TAG, "TCP 音频连接成功")

                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = minBufferSize * BUFFER_FACTOR
                Log.d(TAG, "AudioRecord buffer size: $bufferSize (min=$minBufferSize)")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+：使用 AudioPlaybackCapture 采集系统音频
                    // 关键：必须调用 addMatchingUsage() 指定要采集的音频类型
                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)       // 音乐/视频
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)        // 游戏
                        .addMatchingUsage(AudioAttributes.USAGE_ALARM)       // 闹钟
                        .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION) // 通知
                        .build()

                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .build()
                } else {
                    // Android 9 及以下：降级使用麦克风
                    Log.w(TAG, "Android < 10，无法采集系统音频，降级使用麦克风")
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                }

                if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 初始化失败")
                    stopCaptureInternal()
                    return@Thread
                }

                audioRecord!!.startRecording()
                isCapturing = true
                Log.d(TAG, "开始采集系统音频... sampleRate=$SAMPLE_RATE")

                val buffer = ByteArray(4096)
                var totalBytes = 0L
                while (isCapturing) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        try {
                            outputStream?.write(buffer, 0, read)
                            totalBytes += read
                            if (totalBytes % (4096 * 100) == 0L) {
                                Log.d(TAG, "已发送音频数据: ${totalBytes / 1024} KB")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "发送音频数据失败: ${e.message}")
                            break
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read() 错误: $read")
                        break
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "权限不足：无法采集系统音频，需要 MediaProjection 授权。${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "音频采集异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                Log.d(TAG, "音频采集线程结束，清理资源")
                stopCaptureInternal()
            }
        }
        captureThread?.start()
    }

    /**
     * 停止采集并释放资源
     */
    fun stopCapture() {
        if (!isCapturing) return
        Log.d(TAG, "停止音频采集...")
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null
        stopCaptureInternal()
    }

    /**
     * 内部清理方法（可在任意线程调用）
     */
    private fun stopCaptureInternal() {
        isCapturing = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        outputStream = null
        socket = null
        Log.d(TAG, "音频采集资源已释放")
    }
}

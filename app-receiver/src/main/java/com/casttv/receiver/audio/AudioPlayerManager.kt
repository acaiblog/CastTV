package com.casttv.receiver.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 电视端音频播放管理器
 *
 * 在独立端口监听手机发来的 PCM 音频数据，
 * 用 AudioTrack 实时播放。
 *
 * 使用方式：
 * 1. startAudioServer(port) 启动音频接收服务器
 * 2. stopAudioServer() 停止
 */
class AudioPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var serverSocket: ServerSocket? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var acceptThread: Thread? = null
    private var receiveThread: Thread? = null

    /**
     * 启动音频接收服务器
     * @param port 监听端口（建议 8001，8000 已被信令占用）
     */
    fun startAudioServer(port: Int = 8001) {
        if (isPlaying) {
            Log.w(TAG, "音频服务器已在运行")
            return
        }

        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "音频接收服务器启动，端口: $port")

                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = serverSocket!!.accept()
                    Log.d(TAG, "手机音频连接来自: ${clientSocket.inetAddress.hostAddress}")

                    // 如果已有连接，先断开旧的
                    stopCurrentPlayback()
                    receiveAudio(clientSocket)
                }
            } catch (e: Exception) {
                if (isPlaying) {
                    Log.e(TAG, "音频服务器异常: ${e.message}")
                }
            }
        }
        acceptThread?.start()
    }

    /**
     * 接收并播放音频数据
     */
    private fun receiveAudio(socket: Socket) {
        receiveThread = Thread {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
                )
                Log.d(TAG, "AudioTrack minBufferSize: $minBufferSize")

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .build()

                audioTrack!!.play()
                // setMode() 不是 Builder 的方法，AudioTrack 默认就是 MODE_STREAM
                isPlaying = true
                Log.d(TAG, "AudioTrack 开始播放，sampleRate=$SAMPLE_RATE")

                val inputStream: InputStream = socket.getInputStream()
                val buffer = ByteArray(4096)
                var totalBytes = 0L

                while (isPlaying) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break

                    // 写入 AudioTrack 播放
                    val written = audioTrack!!.write(buffer, 0, read)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write() 错误: $written")
                        break
                    }
                    totalBytes += written
                    if (totalBytes % (4096 * 100) == 0L) {
                        Log.d(TAG, "已播放音频: ${totalBytes / 1024} KB")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "音频接收异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                Log.d(TAG, "音频接收结束，清理资源")
                stopCurrentPlayback()
                try { socket.close() } catch (e: Exception) {}
            }
        }
        receiveThread?.start()
    }

    /**
     * 停止当前播放（不断开服务器）
     */
    private fun stopCurrentPlayback() {
        isPlaying = false
        try { audioTrack?.pause() } catch (e: Exception) {}
        try { audioTrack?.flush() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
        receiveThread?.interrupt()
        receiveThread = null
    }

    /**
     * 停止音频服务器并释放所有资源
     */
    fun stopAudioServer() {
        isPlaying = false
        stopCurrentPlayback()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        Log.d(TAG, "音频服务器已停止")
    }
}

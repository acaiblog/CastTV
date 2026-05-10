package com.casttv.sender.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.VideoCapturer

/**
 * 屏幕采集前台服务
 *
 * Android 10+ 要求 MediaProjection 必须在前台服务中启动。
 * 此服务负责：
 * 1. 显示前台通知（系统要求）
 * 2. 在自身上下文中创建 ScreenCapturerAndroid（此时 getMediaProjection() 能通过系统检查）
 * 3. 通过 companion object 回调通知调用方 Capturer 已就绪
 *
 * 使用方式：
 * 1. 在 companion object 中设置 resultCode / resultData
 * 2. 设置 onCapturerReady 回调
 * 3. 调用 startForegroundService() 启动此服务
 * 4. onCapturerReady 回调触发后，获取 VideoCapturer 并传给 SenderWebRTCManager
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "casttv_screen_capture"
        private const val NOTIFICATION_ID = 10001

        /** 由 Activity 在启动服务前设置：MediaProjection 授权结果码 */
        var resultCode: Int = 0

        /** 由 Activity 在启动服务前设置：MediaProjection 授权 Intent */
        var resultData: Intent? = null

        /** 当 ScreenCapturerAndroid 创建完成后回调，调用方在此获取 VideoCapturer */
        var onCapturerReady: ((VideoCapturer) -> Unit)? = null
    }

    private var videoCapturer: VideoCapturer? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        val notification = buildNotification("CastTV 正在投屏...")
        // Android 14+ 必须在 startForeground() 中传入 foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "onCreate: 前台服务已启动")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // 如果已经创建过，直接通知
        if (videoCapturer != null) {
            Log.d(TAG, "Capturer 已存在，直接通知回调")
            onCapturerReady?.invoke(videoCapturer!!)
            return START_STICKY
        }

        val data = resultData
        if (data == null) {
            Log.e(TAG, "resultData 为 null，无法创建 ScreenCapturer")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // 此时本服务已是前台服务，getMediaProjection() 能通过系统检查
            videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection 已停止")
                }
            })
            Log.d(TAG, "ScreenCapturerAndroid 创建成功")

            onCapturerReady?.invoke(videoCapturer!!)
        } catch (e: Exception) {
            Log.e(TAG, "创建 ScreenCapturerAndroid 失败: ${e.message}")
            stopSelf()
        }

        return START_STICKY
    }

    /** 供调用方获取已创建的 VideoCapturer */
    fun getVideoCapturer(): VideoCapturer? = videoCapturer

    /** 开始屏幕采集 */
    fun startCapture(width: Int, height: Int, fps: Int) {
        try {
            videoCapturer?.startCapture(width, height, fps)
            Log.d(TAG, "屏幕采集已启动: ${width}x${height}@${fps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "startCapture 失败: ${e.message}")
        }
    }

    /** 停止屏幕采集 */
    fun stopCapture() {
        try {
            videoCapturer?.stopCapture()
            Log.d(TAG, "屏幕采集已停止")
        } catch (e: Exception) {
            Log.e(TAG, "stopCapture 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopCapture()
        videoCapturer = null
        onCapturerReady = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕投屏",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "CastTV 屏幕投屏服务"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CastTV 投屏中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}

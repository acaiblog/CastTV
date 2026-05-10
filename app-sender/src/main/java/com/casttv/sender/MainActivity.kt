package com.casttv.sender

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.casttv.sender.databinding.ActivityMainBinding
import com.casttv.sender.signaling.SignalingClient
import com.casttv.sender.webrtc.SenderWebRTCManager
import com.casttv.sender.projection.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CastTV 发送端主界面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CastTV-Sender"
        private const val DISCOVERY_PORT = 5000
        private const val SIGNALING_PORT = 9000  // 手机信令端口（接收TV回传的ICE候选）
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: SenderWebRTCManager
    private lateinit var audioCaptureManager: com.casttv.sender.audio.AudioCaptureManager

    private var discoveredDevices = mutableListOf<TvDevice>()
    private var selectedDevice: TvDevice? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    // 日志
    private val logBuilder = StringBuilder()
    private val logHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startDiscovery()
        } else {
            appendLog("❌ 权限被拒绝")
            Toast.makeText(this, "需要网络权限", Toast.LENGTH_SHORT).show()
        }
    }

    private         val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val device = selectedDevice
        if (result.resultCode == RESULT_OK && device != null) {
            val intent = result.data
            if (intent != null) {
                // 保存 MediaProjection 授权数据（用于音频采集）
                mediaProjectionResultCode = result.resultCode
                mediaProjectionData = intent

                appendLog("✅ 屏幕采集权限已授予")

                // 存储授权结果到 Service
                ScreenCaptureService.resultCode = result.resultCode
                ScreenCaptureService.resultData = intent

                // 设置回调：Service 创建好 Capturer 后通知这里
                ScreenCaptureService.onCapturerReady = { capturer ->
                    webRTCManager.createVideoTrack(capturer) {
                        appendLog("📡 正在连接 ${device.ipAddress}:${device.port}...")
                        appendLog("📤 发送 Offer (signaling_port=$SIGNALING_PORT)...")
                        webRTCManager.connect(device)
                        runOnUiThread { binding.btnStartCast.isEnabled = true }
                    }
                }

                // 启动前台服务（Service 的 onCreate 里会调用 startForeground）
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                appendLog("🚀 ScreenCaptureService 已启动")
            }
        } else {
            appendLog("❌ 屏幕采集权限被拒绝，无法投屏")
            runOnUiThread { binding.btnStartCast.isEnabled = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initWebRTC()
        setupUI()
        setupDeviceList()
        checkPermissions()

        // 设置手机信令端口（必须在connect之前设置）
        webRTCManager.phoneSignalingPort = SIGNALING_PORT
        appendLog("📡 手机信令端口: $SIGNALING_PORT")
        startSignalingServer()

        // 初始化音频采集管理器
        audioCaptureManager = com.casttv.sender.audio.AudioCaptureManager(this)
        appendLog("🔊 音频采集管理器已初始化")
    }

    private fun initWebRTC() {
        appendLog("🔧 初始化 WebRTC...")
        signalingClient = SignalingClient()
        webRTCManager = SenderWebRTCManager(this, signalingClient)
        webRTCManager.initialize()
        appendLog("✅ WebRTC 初始化完成")

        webRTCManager.onConnectionStateChange = { state ->
            runOnUiThread {
                when (state) {
                    org.webrtc.PeerConnection.PeerConnectionState.CONNECTING -> {
                        binding.tvStatus.text = "连接中..."
                        appendLog("🔄 正在连接电视...")
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.CONNECTED -> {
                        binding.tvStatus.text = getString(R.string.status_casting)
                        binding.btnStartCast.text = getString(R.string.btn_stop_cast)
                        appendLog("✅ 投屏成功！")
                        // 启动音频采集
                        startAudioCapture()
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        binding.tvStatus.text = getString(R.string.status_idle)
                        binding.btnStartCast.text = getString(R.string.btn_start_cast)
                        appendLog("⚠️ 连接断开")
                        audioCaptureManager.stopCapture()
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                        binding.tvStatus.text = getString(R.string.status_idle)
                        binding.btnStartCast.text = getString(R.string.btn_start_cast)
                        appendLog("❌ 连接失败")
                        audioCaptureManager.stopCapture()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 初始化 RecyclerView 设备列表
     */
    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            selectedDevice = device
            // 分两行显示：IP 和 分辨率
            binding.tvTvIp.text = "电视IP: ${device.ipAddress}"
            binding.tvResolution.text = "电视分辨率: ${device.width}x${device.height}"
            // 更新选中状态
            deviceAdapter?.setSelected(device.ipAddress)
            appendLog("📺 已选择: ${device.name} (${device.ipAddress})")
            Toast.makeText(this, "已选择: ${device.name}", Toast.LENGTH_SHORT).show()
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupUI() {
        // 显示版本号（顶栏右上角）
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvVersion.text = "版本号： v$versionName"
        } catch (e: Exception) {
            binding.tvVersion.text = "版本号： v1.4"
        }

        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            startDiscovery()
        }

        // 投屏按钮（支持开始/停止切换）
        binding.btnStartCast.setOnClickListener {
            if (binding.btnStartCast.text == getString(R.string.btn_stop_cast)) {
                stopCast()
            } else {
                if (selectedDevice == null) {
                    Toast.makeText(this, "请先选择要投屏的电视", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startCast()
            }
        }

        // 显示本机 IP
        val localIp = getLocalIpAddress()
        binding.tvLocalIp.text = "本机IP: $localIp"
        appendLog("📱 本机IP: $localIp")
        appendLog("🔍 监听端口: $DISCOVERY_PORT (UDP)")
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "未知"
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            appendLog("✅ 权限已授予")
            startDiscovery()
        } else {
            appendLog("📝 请求权限...")
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startDiscovery() {
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.tvNoDevices.visibility = View.GONE
        appendLog("🔍 开始搜索电视设备...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appendLog("📡 创建UDP套接字 (端口 $DISCOVERY_PORT)...")

                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    soTimeout = 5000  // 5秒超时
                    broadcast = true
                }

                appendLog("✅ UDP监听已启动")

                val buffer = ByteArray(4096)
                val devices = mutableListOf<TvDevice>()
                val endTime = System.currentTimeMillis() + 8000  // 增加到8秒

                var packetCount = 0
                while (System.currentTimeMillis() < endTime) {
                    if (socket.isClosed) break
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val senderIp = packet.address.hostAddress
                        packetCount++

                        appendLog("📨 收到数据包 #$packetCount from $senderIp")
                        appendLog("   原始数据: ${json.take(100)}")

                        val obj = JSONObject(json)
                        val device = TvDevice(
                            name = obj.optString("name", "Unknown TV"),
                            ipAddress = obj.optString("ip", senderIp ?: ""),
                            port = obj.optInt("port", 8000),
                            audioPort = obj.optInt("audio_port", 8001),
                            width = obj.optInt("width", 1920),
                            height = obj.optInt("height", 1080),
                            manufacturer = obj.optString("manufacturer", "Unknown"),
                        )

                        if (device.ipAddress.isNotEmpty() && devices.none { it.ipAddress == device.ipAddress }) {
                            devices.add(device)
                            appendLog("✅ 发现新设备: ${device.name}")
                            appendLog("   IP: ${device.ipAddress}:${device.port}")
                            appendLog("   分辨率: ${device.width}x${device.height}")
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        if (!msg.contains("timeout", ignoreCase = true)) {
                            appendLog("⚠️ 接收异常: ${msg}")
                        }
                    }
                }

                socket.close()
                appendLog("📡 UDP监听已关闭 (共收到 $packetCount 个包)")

                withContext(Dispatchers.Main) {
                    discoveredDevices = devices
                    updateDeviceList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "发现失败", e)
                appendLog("❌ 发现失败: ${e.message}")
                appendLog("   原因: ${getErrorReason(e)}")

                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = getString(R.string.no_tv_found)
                }
            }
        }
    }

    private fun getErrorReason(e: Exception): String {
        return when {
            e.message?.contains("Permission denied") == true ->
                "权限不足！请确保应用有网络权限"
            e.message?.contains("Address already in use") == true ->
                "端口被占用，尝试其他端口"
            e.message?.contains("timeout") == true ->
                "没有收到电视的广播包，请确保电视端已开启"
            else -> "网络异常: ${e.message}"
        }
    }

    private fun updateDeviceList() {
        if (discoveredDevices.isEmpty()) {
            binding.tvNoDevices.visibility = View.VISIBLE
            binding.tvNoDevices.text = "未发现电视设备\n\n请检查：\n1. 电视是否在同一局域网\n2. 电视端 CastTV 应用是否运行\n3. 电视是否已开始广播"
            binding.tvTvIp.text = "电视IP: 未发现设备"
            binding.tvResolution.text = "电视分辨率: 未知"
            deviceAdapter?.updateData(emptyList())
            appendLog("⚠️ 未发现任何电视设备")
        } else {
            binding.tvNoDevices.visibility = View.GONE
            // 更新 RecyclerView 数据
            deviceAdapter?.updateData(discoveredDevices)
            // 自动选择第一个设备
            if (selectedDevice == null || discoveredDevices.none { it.ipAddress == selectedDevice!!.ipAddress }) {
                selectedDevice = discoveredDevices.first()
                val device = selectedDevice!!
                // 分两行显示：IP 和 分辨率
                binding.tvTvIp.text = "电视IP: ${device.ipAddress}"
                binding.tvResolution.text = "电视分辨率: ${device.width}x${device.height}"
                deviceAdapter?.setSelected(device.ipAddress)
                Toast.makeText(this, "发现: ${device.name}", Toast.LENGTH_SHORT).show()
            }
            appendLog("🎉 发现 ${discoveredDevices.size} 台设备，已选择: ${selectedDevice?.name}")
        }
        binding.tvStatus.text = if (discoveredDevices.isEmpty()) {
            getString(R.string.no_tv_found)
        } else {
            getString(R.string.status_idle)
        }
    }

    private fun startCast() {
        val device = selectedDevice ?: return
        appendLog("📺 准备投屏到: ${device.name} (${device.ipAddress}:${device.port})")
        appendLog("📡 信令端口: $SIGNALING_PORT")
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.btnStartCast.isEnabled = false

        // 采集分辨率：使用电视的标准 16:9 分辨率
        // 关键：手机屏幕是 19.5:9（瘦长），直接采集会导致 TVs 端显示有黑边或裁切
        // 正确做法：采集为 16:9 分辨率，这样在 TV 上可以全屏无黑边显示
        // 参考西瓜视频等 App 的投屏策略：发送端按接收端分辨率采集
        val targetW = 1920
        val targetH = 1080

        appendLog("📐 电视分辨率: ${device.width}x${device.height}，采集将使用: ${targetW}x${targetH} (16:9)")
        webRTCManager.setTargetResolution(targetW, targetH)

        // 请求屏幕采集权限（系统弹窗），用户授权后再建立 WebRTC 连接
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        appendLog("📷 请求屏幕采集权限...")
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun appendLog(message: String) {
        val time = dateFormat.format(Date())
        logHandler.post {
            logBuilder.append("[$time] $message\n")
            binding.tvLogs.text = logBuilder.toString()
            // 自动滚动到底部
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ===================== 手机端 HTTP 信令服务器 =====================
    // 接收电视端发来的 ICE 候选（电视 POST 到 http://手机IP:9000/signaling/candidate）
    private var phoneServerSocket: ServerSocket? = null

    private fun startSignalingServer() {
        Thread {
            try {
                phoneServerSocket = ServerSocket(SIGNALING_PORT)
                Log.d(TAG, "手机信令服务器启动，端口: $SIGNALING_PORT")
                while (!Thread.currentThread().isInterrupted) {
                    val socket = phoneServerSocket?.accept() ?: break
                    Thread { handleSignalingRequest(socket) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "手机信令服务器异常: ${e.message}")
            }
        }.start()
    }

    private fun stopSignalingServer() {
        try { phoneServerSocket?.close() } catch (e: Exception) {}
        phoneServerSocket = null
        Log.d(TAG, "手机信令服务器已停止")
    }

    private fun handleSignalingRequest(clientSocket: java.net.Socket) {
        try {
            val reader = clientSocket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "手机信令收到: $requestLine 来自: ${clientSocket.inetAddress.hostAddress}")

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isBlank()) break
                if (line!!.lowercase().startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                reader.read(bodyChars)
                String(bodyChars)
            } else ""

            when {
                requestLine.contains("/signaling/candidate") && requestLine.contains("POST") -> {
                    handlePhoneCandidate(body, clientSocket)
                }
                else -> {
                    sendPhoneResponse(clientSocket, 404, "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理手机信令请求失败: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun handlePhoneCandidate(body: String, clientSocket: java.net.Socket) {
        try {
            val json = JSONObject(body)
            val candidate = json.optString("candidate", "")
            val sdpMid = json.optString("sdpMid", "")
            val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
            if (candidate.isNotEmpty()) {
                Log.d(TAG, "收到 TV ICE 候选: sdpMid=$sdpMid")
                webRTCManager.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                sendPhoneResponse(clientSocket, 200, "OK")
            } else {
                sendPhoneResponse(clientSocket, 400, "Missing candidate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 TV ICE 候选失败: ${e.message}")
            sendPhoneResponse(clientSocket, 500, "Error: ${e.message}")
        }
    }

    private fun sendPhoneResponse(socket: java.net.Socket, statusCode: Int, message: String) {
        try {
            val body = """{"type":"ok","message":"$message"}"""
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val statusText = if (statusCode == 200) "OK" else "Error"
            val response = "HTTP/1.1 $statusCode $statusText\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body
            val os = socket.getOutputStream()
            os.write(response.toByteArray(Charsets.UTF_8))
            os.flush()
            Thread.sleep(100)
        } catch (e: Exception) {
            Log.e(TAG, "发送手机信令响应失败: ${e.message}")
        }
    }
    // ===================== 手机端 HTTP 信令服务器结束 =====================

    /**
     * 停止投屏并清理资源
     */
    private fun stopCast() {
        appendLog("停止投屏...")
        audioCaptureManager.stopCapture()
        // 停止屏幕采集前台服务
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        // 释放 WebRTC 并重新初始化，以便下次投屏
        webRTCManager.release()
        initWebRTC()
        // 重启信令服务器（端口可能已被关闭）
        startSignalingServer()
        webRTCManager.phoneSignalingPort = SIGNALING_PORT
        appendLog("📡 手机信令端口已重置: $SIGNALING_PORT")
        // 恢复 UI 状态
        binding.tvStatus.text = getString(R.string.status_idle)
        binding.btnStartCast.text = getString(R.string.btn_start_cast)
        mediaProjectionResultCode = 0
        mediaProjectionData = null
        appendLog("投屏已停止")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.btnStartCast.text == getString(R.string.btn_stop_cast)) {
            stopCast()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopSignalingServer()
        audioCaptureManager.stopCapture()
        webRTCManager.release()
        super.onDestroy()
    }

    // ===================== 音频采集 =====================
    /**
     * 在 WebRTC 连接成功后调用，启动系统音频采集并发送到电视
     */
    private fun startAudioCapture() {
        val device = selectedDevice ?: return
        val data = mediaProjectionData ?: return
        if (mediaProjectionResultCode == 0) {
            appendLog("⚠️ MediaProjection 数据无效，无法启动音频采集")
            return
        }

        try {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mediaProjectionManager.getMediaProjection(
                mediaProjectionResultCode, data
            )

            audioCaptureManager.setTargetTv(device.ipAddress, device.audioPort)
            audioCaptureManager.startCapture(mediaProjection)
            appendLog("🔊 音频采集已启动，发送到 ${device.ipAddress}:${device.audioPort}")
        } catch (e: Exception) {
            appendLog("❌ 启动音频采集失败: ${e.message}")
        }
    }
    // ===================== 音频采集结束 =====================
}

/**
 * 设备列表 Adapter
 */
class DeviceAdapter(
    private val onDeviceClick: (TvDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val items = mutableListOf<TvDevice>()
    private var selectedIp: String? = null

    fun updateData(newItems: List<TvDevice>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelected(ip: String?) {
        selectedIp = ip
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
            textSize = 15f
            setGravity(Gravity.CENTER_VERTICAL)
            setBackgroundResource(android.R.drawable.btn_default)
        }
        return DeviceViewHolder(tv)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = items[position]
        val isSelected = device.ipAddress == selectedIp
        holder.textView.text = "📺 ${device.name}\n   ${device.ipAddress} · ${device.width}x${device.height}"
        holder.textView.isSelected = isSelected
        // 选中高亮
        if (isSelected) {
            holder.textView.setBackgroundColor(0xFFE8F0FE.toInt())  // 浅蓝背景
            holder.textView.setTextColor(0xFF185FA5.toInt())
        } else {
            holder.textView.setTextColor(0xFF2C2C2A.toInt())
            holder.textView.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = items.size

    class DeviceViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}

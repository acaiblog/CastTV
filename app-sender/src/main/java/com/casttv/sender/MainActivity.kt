package com.casttv.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.casttv.sender.databinding.ActivityMainBinding
import com.casttv.sender.signaling.SignalingClient
import com.casttv.sender.webrtc.SenderWebRTCManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: SenderWebRTCManager

    private var discoveredDevices = mutableListOf<TvDevice>()
    private var selectedDevice: TvDevice? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initWebRTC()
        setupUI()
        checkPermissions()
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
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        binding.tvStatus.text = getString(R.string.status_idle)
                        binding.btnStartCast.text = getString(R.string.btn_start_cast)
                        appendLog("⚠️ 连接断开")
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                        binding.tvStatus.text = getString(R.string.status_idle)
                        binding.btnStartCast.text = getString(R.string.btn_start_cast)
                        appendLog("❌ 连接失败")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun setupUI() {
        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            startDiscovery()
        }

        // 投屏按钮
        binding.btnStartCast.setOnClickListener {
            if (selectedDevice == null) {
                Toast.makeText(this, "请先选择要投屏的电视", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCast()
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
            Manifest.permission.ACCESS_WIFI_STATE
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
                val endTime = System.currentTimeMillis() + 6000

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
                            manufacturer = obj.optString("manufacturer", "Unknown"),
                            width = obj.optInt("width", 1920),
                            height = obj.optInt("height", 1080)
                        )

                        if (device.ipAddress.isNotEmpty() && devices.none { it.ipAddress == device.ipAddress }) {
                            devices.add(device)
                            appendLog("✅ 发现新设备: ${device.name}")
                            appendLog("   IP: ${device.ipAddress}")
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
                appendLog("📡 UDP监听已关闭")

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
            else -> "网络异常"
        }
    }

    private fun updateDeviceList() {
        if (discoveredDevices.isEmpty()) {
            binding.tvNoDevices.visibility = View.VISIBLE
            binding.tvNoDevices.text = "未发现电视设备\n\n请检查：\n1. 电视是否在同一局域网\n2. 电视端 CastTV 应用是否运行\n3. 电视是否已开始广播"
            binding.tvResolution.text = "电视分辨率：未发现"
            appendLog("⚠️ 未发现任何电视设备")
        } else {
            binding.tvNoDevices.visibility = View.GONE
            // 自动选择第一个设备
            selectedDevice = discoveredDevices.first()
            val device = selectedDevice!!
            binding.tvResolution.text = "电视分辨率：${device.width}x${device.height}"
            Toast.makeText(this, "发现: ${device.name}", Toast.LENGTH_SHORT).show()
            appendLog("🎉 发现 ${discoveredDevices.size} 台设备，已选择: ${device.name}")
        }
        binding.tvStatus.text = if (discoveredDevices.isEmpty()) {
            getString(R.string.no_tv_found)
        } else {
            getString(R.string.status_idle)
        }
    }

    private fun startCast() {
        val device = selectedDevice ?: return
        appendLog("📺 准备投屏到: ${device.name}")
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.btnStartCast.isEnabled = false

        // 设置目标分辨率
        webRTCManager.setTargetResolution(device.width, device.height)

        // 创建视频轨道并连接
        webRTCManager.createVideoTrack { _, _ ->
            appendLog("📡 正在连接 ${device.ipAddress}:8000...")
            webRTCManager.connect(device)
            runOnUiThread {
                binding.btnStartCast.isEnabled = true
            }
        }
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

    override fun onDestroy() {
        webRTCManager.release()
        super.onDestroy()
    }
}

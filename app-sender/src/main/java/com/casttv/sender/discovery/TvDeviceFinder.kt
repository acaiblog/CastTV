package com.casttv.sender.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.casttv.sender.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 索尼电视 / Android TV 设备发现
 *
 * 工作原理：
 * 1. Sender 端监听 UDP 广播（发现端口 5000）
 * 2. Receiver 端定时广播自己的存在 + 分辨率信息
 * 3. Sender 端收到后，提取电视 IP、分辨率等
 *
 * 索尼 Android TV 默认会响应 DIAL/SSDP 发现，
 * 这里用简化版 UDP 广播实现，不需要额外服务器
 */
class TvDeviceFinder(private val context: Context) {

    companion object {
        private const val TAG = "TvDeviceFinder"
        private const val DISCOVERY_PORT = 5000          // 监听发现广播的端口
        private const val BROADCAST_INTERVAL_MS = 1000    // Receiver 广播间隔
        private const val DISCOVERY_TIMEOUT_MS = 5000    // 发现超时时间
        private const val MAX_PACKET_SIZE = 4096
    }

    // 已发现的设备缓存（IP -> TvDevice）
    private val discoveredDevices = mutableMapOf<String, TvDevice>()

    // 本机监听的 ServerSocket（用于 Receiver 连接）
    private var serverSocket: ServerSocket? = null
    var signalingPort: Int = 0
        private set

    /**
     * 启动信令端口，供 Receiver 连接
     * 返回本机 IP 地址
     */
    @Synchronized
    fun startSignalingServer(): String {
        if (serverSocket == null || serverSocket!!.isClosed) {
            serverSocket = ServerSocket(0)  // 自动分配空闲端口
            signalingPort = serverSocket!!.localPort
            Log.d(TAG, "信令服务器启动，端口: $signalingPort")
        }
        return getLocalIpAddress()
    }

    /**
     * 获取本机局域网 IP
     */
    private fun getLocalIpAddress(): String {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }

    /**
     * 异步发现局域网内的电视设备
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun discoverDevices(timeoutMs: Int = DISCOVERY_TIMEOUT_MS): List<TvDevice> =
        withContext(Dispatchers.IO) {
            discoveredDevices.clear()
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    soTimeout = timeoutMs
                }

                val buffer = ByteArray(MAX_PACKET_SIZE)
                val deadline = System.currentTimeMillis() + timeoutMs

                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parseDeviceInfo(json, packet.address.hostAddress)
                    } catch (e: Exception) {
                        // 超时，继续等待
                        if (e.message?.contains("timeout") == true) {
                            // 正常超时
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设备发现失败: ${e.message}")
            } finally {
                socket?.close()
            }

            discoveredDevices.values.toList()
        }

    /**
     * 解析设备广播的 JSON 信息
     * 格式: {"name":"Sony TV","width":1920,"height":1080,"port":8000,"manufacturer":"Sony"}
     */
    private fun parseDeviceInfo(json: String, ip: String?) {
        try {
            val obj = JSONObject(json)
            val device = TvDevice(
                name = obj.optString("name", "未知电视"),
                ipAddress = ip ?: obj.optString("ip", ""),
                port = obj.optInt("port", 8000),
                width = obj.optInt("width", 1920),
                height = obj.optInt("height", 1080),
                manufacturer = obj.optString("manufacturer", "Unknown")
            )
            discoveredDevices[device.ipAddress] = device
            Log.d(TAG, "发现设备: ${device.name} (${device.ipAddress}) - ${device.resolutionLabel}")
        } catch (e: Exception) {
            Log.w(TAG, "解析设备信息失败: $json")
        }
    }

    /**
     * 停止信令服务器
     */
    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }
}

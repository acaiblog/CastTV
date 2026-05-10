package com.casttv.receiver.signaling

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 电视端信令辅助类
 *
 * 注意：HTTP 服务器实际在 MainActivity 中实现（ServerSocket），
 * 此类仅提供工具方法（获取本机 IP、向手机发送信令等）。
 */
class SignalingServer(
    private val port: Int,
    private val onOfferReceived: (String) -> Unit,
    private val onCandidateReceived: (String, String?, Int) -> Unit
) {

    companion object {
        private const val TAG = "SignalingServer"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // 获取本机局域网 IP
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addresses = java.util.Collections.list(intf.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is InetAddress) {
                            val host = addr.hostAddress
                            if (host != null && !host.contains(":")) {
                                return host
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取 IP 失败: ${e.message}")
            }
            return null
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // 返回信令服务器地址（供手机连接）
    var signalingUrl: String = ""
        private set

    /**
     * 记录本机信令地址（由 MainActivity 在 HTTP 服务器启动后调用）
     */
    fun start(myIp: String, myPort: Int) {
        signalingUrl = "http://$myIp:$myPort"
        Log.d(TAG, "信令地址: $signalingUrl")
    }

    /**
     * 停止信令服务（空实现，实际由 MainActivity 关闭 ServerSocket）
     */
    fun stop() {
        Log.d(TAG, "SignalingServer.stop() 被调用（HTTP 服务器由 MainActivity 管理）")
    }

    /**
     * 向手机信令端发送 Offer 并获取 Answer（旧接口，保留兼容）
     * 实际未使用——当前架构由 MainActivity 的 HTTP 服务器直接处理 Offer
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun receiveOfferAndSendAnswer(offerUrl: String, sdpOffer: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdpOffer)
                }

                val request = Request.Builder()
                    .url(offerUrl)
                    .post(json.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string()
                    val answer = JSONObject(body ?: "")
                    answer.optString("sdp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "信令交换失败: ${e.message}")
                null
            }
        }
}

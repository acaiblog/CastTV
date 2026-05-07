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
 * 电视端 HTTP 信令服务器
 *
 * 运行在索尼电视上，提供以下端点：
 * - POST /signaling/offer  → 接收手机发来的 SDP Offer，返回 SDP Answer
 * - POST /signaling/candidate → 接收 ICE Candidate
 *
 * 电视端需要先把自己的信息（IP + 端口 + 分辨率）广播给手机，
 * 这在 ReceiverWebRTCManager 中处理。
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
     * 启动信令服务器（使用 NanoHTTPD 或内置 HttpServer）
     * 这里使用简化版：直接轮询手机端（手机先启动 HTTP Server）
     *
     * 实际方案：电视端作为 HTTP 客户端，主动拉取手机的 SDP Offer
     */
    fun start(myIp: String, myPort: Int) {
        signalingUrl = "http://$myIp:$myPort"
        Log.d(TAG, "信令地址: $signalingUrl")
    }

    /**
     * 接收手机发来的 SDP Offer，然后连接手机信令端获取 Answer
     * 这个由 WebRTCManager 在建立连接后调用
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

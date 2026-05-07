package com.casttv.sender.signaling

import android.util.Log
import com.casttv.sender.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebRTC 信令客户端（发送端）
 *
 * 信令流程：
 * 1. 发送端生成 SDP Offer → POST 到电视端
 * 2. 电视端返回 SDP Answer
 * 3. ICE Candidate 交换
 *
 * 所有通信走 HTTP（局域网直连，无需外网）
 */
class SignalingClient {

    companion object {
        private const val TAG = "SignalingClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 发送 SDP Offer 给电视端
     * @param sdpOffer 生成的 WebRTC SDP Offer
     * @param device 目标电视设备
     * @return 电视端返回的 SDP Answer
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun sendOffer(sdpOffer: String, device: TvDevice): String? =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdpOffer)
                }

                val request = Request.Builder()
                    .url("http://${device.ipAddress}:${device.port}/signaling/offer")
                    .post(json.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "发送 Offer 失败: ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string()
                    val answer = JSONObject(body ?: "")
                    Log.d(TAG, "收到 Answer，type=${answer.optString("type")}")
                    answer.optString("sdp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "信令交换失败: ${e.message}")
                null
            }
        }

    /**
     * 发送 ICE Candidate 给电视端
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int, device: TvDevice): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", candidate)
                    sdpMid?.let { put("sdpMid", it) }
                    put("sdpMLineIndex", sdpMLineIndex)
                }

                val request = Request.Builder()
                    .url("http://${device.ipAddress}:${device.port}/signaling/candidate")
                    .post(json.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.e(TAG, "ICE Candidate 发送失败: ${e.message}")
                false
            }
        }
}

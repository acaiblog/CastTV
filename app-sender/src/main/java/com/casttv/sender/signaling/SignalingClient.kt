package com.casttv.sender.signaling

import android.util.Log
import com.casttv.sender.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
     * 发送 SDP Offer 给电视端，并从 HTTP 响应中读取 Answer
     */
    suspend fun sendOffer(sdpOffer: String, device: TvDevice, signalingPort: Int): String? =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdpOffer)
                    put("signaling_port", signalingPort)
                }

                val request = Request.Builder()
                    .url("http://${device.ipAddress}:${device.port}/signaling/offer")
                    .post(json.toString().toRequestBody(JSON))
                    .build()
                Log.d(TAG, "发送 Offer 到: http://${device.ipAddress}:${device.port}/signaling/offer")
                Log.d(TAG, "Offer SDP 前100字符: ${sdpOffer.take(100)}")

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Offer 响应码: ${response.code}")
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        Log.e(TAG, "发送 Offer 失败: ${response.code}, body: $errorBody")
                        return@withContext null
                    }
                    val body = response.body?.string()
                    Log.d(TAG, "Offer 响应体前200字符: ${body?.take(200)}")
                    val answer = JSONObject(body ?: "")
                    Log.d(TAG, "收到 Answer，type=${answer.optString("type")}, sdp长度=${answer.optString("sdp", "").length}")
                    return@withContext answer.optString("sdp", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "信令交换失败: ${e.message}")
                null
            }
        }

    /**
     * 发送 ICE Candidate 给电视端
     */
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

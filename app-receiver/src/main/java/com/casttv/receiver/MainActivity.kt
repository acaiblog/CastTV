package com.casttv.receiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.casttv.receiver.databinding.ActivityMainBinding
import com.casttv.receiver.signaling.SignalingServer
import com.casttv.receiver.webrtc.ReceiverWebRTCManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * CastTV 电视接收端主界面
 *
 * 运行在索尼 Android TV 上，流程如下：
 * 1. 启动后显示等待画面
 * 2. 定时 UDP 广播自己的存在（IP + 分辨率）
 * 3. 监听局域网 HTTP 请求（接收手机的 SDP Offer）
 * 4. 收到 Offer 后建立 WebRTC 连接，开始渲染手机画面
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "TVMainActivity"
        private const val LISTEN_PORT = 8000  // 监听手机发来的 HTTP 请求
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webRTCManager: ReceiverWebRTCManager
    private lateinit var signalingServer: SignalingServer
    private var serverSocket: java.net.ServerSocket? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initWebRTC()
        startListening()
    }

    private fun initWebRTC() {
        signalingServer = SignalingServer(
            port = LISTEN_PORT,
            onOfferReceived = { sdpOffer ->
                Log.d(TAG, "收到 SDP Offer")
            },
            onCandidateReceived = { candidate, sdpMid, sdpMLineIndex ->
                Log.d(TAG, "收到 ICE Candidate")
            }
        )

        webRTCManager = ReceiverWebRTCManager(this, signalingServer)
        webRTCManager.initialize(binding.surfaceRenderer)
        webRTCManager.tvWidth = 1920
        webRTCManager.tvHeight = 1080

        // 显示分辨率
        binding.tvResolution.text = getString(
            R.string.resolution_label,
            "${webRTCManager.tvWidth}x${webRTCManager.tvHeight}"
        )

        webRTCManager.onConnectionStateChange = { state ->
            mainHandler.post {
                when (state) {
                    org.webrtc.PeerConnection.PeerConnectionState.CONNECTING -> {
                        binding.tvStatus.text = "连接中…"
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.CONNECTED -> {
                        binding.waitingView.visibility = View.GONE
                        binding.statusBar.visibility = View.VISIBLE
                        binding.tvStatus.text = getString(R.string.connected)
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED,
                    org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                        binding.waitingView.visibility = View.VISIBLE
                        binding.statusBar.visibility = View.GONE
                        binding.tvStatus.text = getString(R.string.disconnected)
                    }
                    else -> {}
                }
            }
        }

        // 开始广播自己的存在
        webRTCManager.startBroadcasting()
    }

    /**
     * 启动 HTTP 服务器，监听手机发来的信令
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun startListening() {
        Thread {
            try {
                serverSocket = java.net.ServerSocket(LISTEN_PORT)
                Log.d(TAG, "HTTP 信令服务器启动，端口: $LISTEN_PORT")

                while (!Thread.currentThread().isInterrupted) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        handleRequest(socket)
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务器异常: ${e.message}")
            }
        }.start()
    }

    /**
     * 处理 HTTP 请求
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleRequest(clientSocket: java.net.Socket) {
        try {
            val reader = clientSocket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "收到请求: $requestLine")

            // 解析请求
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // 读取请求体（如果有）
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

            // 路由处理 - 在主线程更新 UI
            val finalPath = path
            val finalMethod = method
            val finalBody = body
            mainHandler.post {
                when {
                    finalPath.startsWith("/signaling/offer") && finalMethod == "POST" -> {
                        handleOfferSync(finalBody, clientSocket)
                    }
                    finalPath.startsWith("/signaling/answer") && finalMethod == "POST" -> {
                        handleAnswer(finalBody)
                    }
                    finalPath.startsWith("/signaling/candidate") && finalMethod == "POST" -> {
                        handleCandidate(finalBody)
                    }
                    else -> {
                        sendResponse(clientSocket, 404, "Not Found")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求处理失败: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleOffer(body: String, clientSocket: java.net.Socket) {
        try {
            val json = JSONObject(body)
            val sdpOffer = json.optString("sdp", "")
            val senderIp = clientSocket.inetAddress.hostAddress

            Log.d(TAG, "收到来自 $senderIp 的 Offer")

            // 获取本机 IP 用于回复
            val myIp = SignalingServer.getLocalIpAddress() ?: return
            val replyUrl = "http://$senderIp:${clientSocket.port}/signaling/answer"

            // 建立 WebRTC 连接
            webRTCManager.receiveCall(replyUrl, sdpOffer)

            sendResponse(clientSocket, 200, "OK")
        } catch (e: Exception) {
            Log.e(TAG, "处理 Offer 失败: ${e.message}")
            sendResponse(clientSocket, 500, "Error")
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleOfferSync(body: String, clientSocket: java.net.Socket) {
        try {
            val json = JSONObject(body)
            val sdpOffer = json.optString("sdp", "")
            val senderIp = clientSocket.inetAddress.hostAddress

            Log.d(TAG, "收到来自 $senderIp 的 Offer")

            // 获取本机 IP 用于回复
            val myIp = SignalingServer.getLocalIpAddress() ?: return
            val replyUrl = "http://$senderIp:${clientSocket.port}/signaling/answer"

            // 建立 WebRTC 连接
            webRTCManager.receiveCall(replyUrl, sdpOffer)

            sendResponse(clientSocket, 200, "OK")
        } catch (e: Exception) {
            Log.e(TAG, "处理 Offer 失败: ${e.message}")
            sendResponse(clientSocket, 500, "Error")
        }
    }

    private fun handleAnswer(body: String) {
        try {
            val json = JSONObject(body)
            val sdpAnswer = json.optString("sdp", "")
            Log.d(TAG, "收到 Answer: ${sdpAnswer.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "处理 Answer 失败: ${e.message}")
        }
    }

    private fun handleCandidate(body: String) {
        try {
            val json = JSONObject(body)
            val candidate = json.optString("candidate", "")
            Log.d(TAG, "收到 ICE Candidate")
        } catch (e: Exception) {
            Log.e(TAG, "处理 Candidate 失败: ${e.message}")
        }
    }

    private fun sendResponse(socket: java.net.Socket, statusCode: Int, message: String) {
        try {
            val body = """{"type":"ok","message":"$message"}"""
            val response = """
                HTTP/1.1 ${statusCode} ${message}
                Content-Type: application/json
                Content-Length: ${body.length}
                Connection: close

                $body
            """.trimIndent()
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "发送响应失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        webRTCManager.release()
        try { serverSocket?.close() } catch (e: Exception) {}
        super.onDestroy()
    }
}

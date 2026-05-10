package com.casttv.receiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.casttv.receiver.databinding.ActivityMainBinding
import com.casttv.receiver.signaling.SignalingServer
import com.casttv.receiver.webrtc.ReceiverWebRTCManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.ServerSocket
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
        private const val LISTEN_PORT = 8000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webRTCManager: ReceiverWebRTCManager
    private lateinit var signalingServer: SignalingServer
    private lateinit var audioPlayerManager: com.casttv.receiver.audio.AudioPlayerManager
    private var serverSocket: ServerSocket? = null

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

        // 沉浸模式：隐藏状态栏和导航栏，真正全屏
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        // 显示版本号（右上角）
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvVersion.text = "v$versionName"
        } catch (e: Exception) {
            binding.tvVersion.text = "v1.1"
        }

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

        // 初始化音频播放管理器并启动服务器
        audioPlayerManager = com.casttv.receiver.audio.AudioPlayerManager(this)
        val audioPort = 8001
        audioPlayerManager.startAudioServer(audioPort)
        webRTCManager.setAudioPort(audioPort)
        Log.d(TAG, "音频播放服务器已启动，端口: $audioPort")

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
                        // 连接成功后隐藏状态栏和版本号，实现全屏观看
                        binding.statusBar.visibility = View.GONE
                        binding.tvVersion.visibility = View.GONE
                        binding.tvStatus.text = getString(R.string.connected)
                        Log.d(TAG, "投屏已连接，已隐藏状态栏和版本号")
                    }
                    org.webrtc.PeerConnection.PeerConnectionState.DISCONNECTED,
                    org.webrtc.PeerConnection.PeerConnectionState.FAILED -> {
                        binding.waitingView.visibility = View.VISIBLE
                        binding.statusBar.visibility = View.GONE
                        binding.tvVersion.visibility = View.VISIBLE
                        binding.tvStatus.text = getString(R.string.disconnected)
                    }
                    else -> {}
                }
            }
        }

        webRTCManager.startBroadcasting()
    }

    /**
     * 启动 HTTP 服务器，监听手机发来的信令
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun startListening() {
        Thread {
            try {
                serverSocket = ServerSocket(LISTEN_PORT)
                Log.d(TAG, "HTTP 信令服务器启动，端口: $LISTEN_PORT")

                while (!Thread.currentThread().isInterrupted) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handleRequest(socket) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "服务器异常: ${e.message}")
            }
        }.start()
    }

    /**
     * 处理 HTTP 请求
     *
     * 直接在调用线程（后台线程）执行，不使用 mainHandler.post，
     * 确保 sendResponse 在 socket 被关闭之前执行。
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleRequest(clientSocket: java.net.Socket) {
        try {
            val reader = clientSocket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "收到请求: $requestLine 来自: ${clientSocket.inetAddress.hostAddress}")

            val parts = requestLine.split(" ")
            if (parts.size < 2) { sendResponse(clientSocket, 400, "Bad Request"); return }
            val method = parts[0]
            val path = parts[1]

            // 读取请求头
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

            // 直接调用处理器（不切换到主线程）
            when {
                path.startsWith("/signaling/offer") && method == "POST" -> {
                    handleOfferSync(body, clientSocket)
                }
                path.startsWith("/signaling/answer") && method == "POST" -> {
                    handleAnswer(body)
                    sendResponse(clientSocket, 200, "OK")
                }
                path.startsWith("/signaling/candidate") && method == "POST" -> {
                    handleCandidate(body)
                    sendResponse(clientSocket, 200, "OK")
                }
                else -> {
                    sendResponse(clientSocket, 404, "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求处理失败: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    /**
     * 同步处理 Offer：生成 Answer 并写入 HTTP 响应
     * 在 handleRequest 的后台线程中直接调用，sendResponse 写入后
     * handleRequest 的 finally 块会关闭 socket。
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleOfferSync(body: String, clientSocket: java.net.Socket) {
        try {
            val json = JSONObject(body)
            val sdpOffer = json.optString("sdp", "")
            val senderIp = clientSocket.inetAddress.hostAddress
            val phoneSignalingPort = json.optInt("signaling_port", 0)

            Log.d(TAG, "收到来自 $senderIp 的 Offer (signaling_port=$phoneSignalingPort)，SDP 前100字符: ${sdpOffer.take(100)}")

            // 构建手机信令 URL（用于后续发送 ICE 候选）
            // 注意：sendIceCandidateToSender 会追加 "/signaling/candidate"，所以这里只写到端口
            val phoneSignalingUrl = if (phoneSignalingPort > 0) {
                "http://$senderIp:$phoneSignalingPort"
            } else {
                null
            }

            // 同步接收 Offer 并获取 Answer（阻塞当前线程，直到 Answer 生成）
            val answerSdp = webRTCManager.receiveCallAndGetAnswer(sdpOffer, phoneSignalingUrl)

            // 将 Answer 写入 HTTP 响应
            if (answerSdp != null) {
                val responseBody = JSONObject().apply {
                    put("type", "answer")
                    put("sdp", answerSdp)
                }.toString()
                sendRawJsonResponse(clientSocket, responseBody)
                Log.d(TAG, "Answer 已写入 HTTP 响应")
            } else {
                sendResponse(clientSocket, 500, "Failed to create answer")
                Log.e(TAG, "生成 Answer 失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 Offer 失败: ${e.message}")
            try { sendResponse(clientSocket, 500, "Error: ${e.message}") } catch (e2: Exception) {}
        }
    }

    /**
     * 异步处理 Offer（兼容旧代码，实际已改用 handleOfferSync）
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    private fun handleOffer(body: String, clientSocket: java.net.Socket) {
        try {
            val json = JSONObject(body)
            val sdpOffer = json.optString("sdp", "")
            val senderIp = clientSocket.inetAddress.hostAddress

            Log.d(TAG, "收到来自 $senderIp 的 Offer (async)")

            val myIp = SignalingServer.getLocalIpAddress() ?: return
            val replyUrl = "http://$senderIp:${clientSocket.port}/signaling/answer"

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
            Log.e(TAG, "发送响应失败: ${e.message}")
        }
    }

    /**
     * 发送原始 JSON 响应（用于返回 Answer）
     * 注意：Content-Length 必须使用字节数，不能用字符数
     */
    private fun sendRawJsonResponse(socket: java.net.Socket, jsonBody: String) {
        try {
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: application/json\r\n" +
                         "Content-Length: ${bodyBytes.size}\r\n" +
                         "Connection: close\r\n" +
                         "\r\n" +
                         jsonBody
            val os = socket.getOutputStream()
            os.write(response.toByteArray(Charsets.UTF_8))
            os.flush()
            // 短暂延迟确保数据发送完毕再关闭 socket
            Thread.sleep(100)
            Log.d(TAG, "HTTP 响应已发送（${bodyBytes.size} 字节）")
        } catch (e: Exception) {
            Log.e(TAG, "发送 JSON 响应失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        webRTCManager.release()
        try { serverSocket?.close() } catch (e: Exception) {}
        super.onDestroy()
    }
}

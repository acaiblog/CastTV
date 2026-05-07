package com.casttv.receiver.webrtc

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.casttv.receiver.signaling.SignalingServer
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 电视端 WebRTC 管理器
 *
 * 职责：
 * 1. 初始化 EGL 和 PeerConnectionFactory
 * 2. 创建视频渲染器（SurfaceViewRenderer）
 * 3. 等待手机端连接，接收并渲染视频流
 * 4. 定时广播自己的存在（IP + 分辨率）让手机发现
 */
class ReceiverWebRTCManager(
    private val context: Context,
    private val signalingServer: SignalingServer
) {

    companion object {
        private const val TAG = "ReceiverWebRTC"
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        private const val BROADCAST_PORT = 5000          // 广播自己的端口
        private const val BROADCAST_INTERVAL_MS = 1000L // 广播频率
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var broadcastJob: Job? = null

    // 电视分辨率
    var tvWidth: Int = 1920
    var tvHeight: Int = 1080

    // 信令
    private var senderSignalingUrl: String? = null
    private val client = OkHttpClient.Builder().build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 电视端渲染器（供 Activity 设置）
     */
    lateinit var surfaceViewRenderer: SurfaceViewRenderer
        private set

    /**
     * 连接状态回调
     */
    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    /**
     * 初始化 WebRTC 和 EGL 渲染器
     */
    fun initialize(renderer: SurfaceViewRenderer) {
        surfaceViewRenderer = renderer

        // 获取电视分辨率
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        tvWidth = metrics.widthPixels
        tvHeight = metrics.heightPixels
        Log.d(TAG, "电视分辨率: ${tvWidth}x${tvHeight}")

        // EGL 初始化（用于渲染）- 使用 EglBase.create() 创建标准 EGL 上下文
        eglBase = EglBase.create()

        // 初始化渲染器
        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)

        // WebRTC 全局初始化
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // 创建编码/解码工厂
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "电视端 WebRTC 初始化完成")
    }

    /**
     * 开始广播自己的存在（UDP），供手机发现
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun startBroadcasting() {
        val myIp = SignalingServer.getLocalIpAddress() ?: return
        signalingServer.start(myIp, 0)  // 电视作为 HTTP 客户端连接手机

        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket()
                socket.broadcast = true

                // 定时广播自己的分辨率信息
                while (isActive) {
                    val info = """
                        {
                            "name": "Sony TV (CastTV)",
                            "ip": "$myIp",
                            "port": 0,
                            "width": $tvWidth,
                            "height": $tvHeight,
                            "manufacturer": "Sony"
                        }
                    """.trimIndent()

                    val packet = java.net.DatagramPacket(
                        info.toByteArray(Charsets.UTF_8),
                        info.length,
                        java.net.InetAddress.getByName("255.255.255.255"),
                        BROADCAST_PORT
                    )
                    socket.send(packet)
                    Log.d(TAG, "广播自己: $myIp - ${tvWidth}x${tvHeight}")

                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "广播失败: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    /**
     * 停止广播
     */
    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    /**
     * 接收手机端的 WebRTC 连接
     * @param offerUrl 手机端 HTTP 信令服务器地址
     * @param sdpOffer SDP Offer
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun receiveCall(offerUrl: String, sdpOffer: String) {
        senderSignalingUrl = offerUrl
        val factory = peerConnectionFactory ?: return

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                state?.let {
                    Log.d(TAG, "连接状态: $it")
                    CoroutineScope(Dispatchers.Main).launch {
                        onConnectionStateChange?.invoke(it)
                    }
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    executor.execute {
                        sendIceCandidateToSender(it)
                    }
                }
            }
            override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {}
            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "收到视频流！轨道数: ${stream?.videoTracks?.size}")
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    CoroutineScope(Dispatchers.Main).launch {
                        track.addSink(surfaceViewRenderer)
                        surfaceViewRenderer.setVisibility(android.view.View.VISIBLE)
                    }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(rtpReceiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                rtpReceiver?.let { receiver ->
                    streams?.firstOrNull()?.videoTracks?.firstOrNull()?.let { track ->
                        CoroutineScope(Dispatchers.Main).launch {
                            track.addSink(surfaceViewRenderer)
                        }
                    }
                }
            }
            override fun onRemoveTrack(rtpReceiver: RtpReceiver?) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
        }

        peerConnection = factory.createPeerConnection(ICE_SERVERS, observer)

        // 设置远程 SDP（手机发来的 Offer）
        executor.execute {
            try {
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onSetSuccess() {
                        // 远程 SDP 设置成功，生成 Answer
                        executor.execute {
                            createAndSendAnswer()
                        }
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "设置远程 SDP 失败: $error")
                    }
                    override fun onCreateFailure(error: String?) {}
                }, SessionDescription(SessionDescription.Type.OFFER, sdpOffer))
            } catch (e: Exception) {
                Log.e(TAG, "接收 Offer 失败: ${e.message}")
            }
        }
    }

    private fun createAndSendAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            // 将 Answer 发送给手机
                            sendAnswerToSender(it.description)
                        }
                        override fun onSetFailure(error: String?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
            override fun onCreateFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun sendAnswerToSender(answerSdp: String) {
        val url = senderSignalingUrl ?: return
        executor.execute {
            try {
                val json = JSONObject().apply {
                    put("type", "answer")
                    put("sdp", answerSdp)
                }
                val request = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Answer 发送结果: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送 Answer 失败: ${e.message}")
            }
        }
    }

    private fun sendIceCandidateToSender(candidate: IceCandidate) {
        val url = senderSignalingUrl ?: return
        try {
            val json = JSONObject().apply {
                put("type", "candidate")
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { }
        } catch (e: Exception) {
            Log.e(TAG, "ICE Candidate 发送失败: ${e.message}")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopBroadcasting()
        peerConnection?.close()
        peerConnection = null
        surfaceViewRenderer.release()
        surfaceViewRenderer.clearImage()
        eglBase?.release()
        eglBase = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        executor.shutdown()
        Log.d(TAG, "电视端 WebRTC 资源释放")
    }
}

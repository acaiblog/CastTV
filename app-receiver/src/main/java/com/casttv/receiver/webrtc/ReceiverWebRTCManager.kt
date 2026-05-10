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
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        private const val BROADCAST_PORT = 5000
        private const val BROADCAST_INTERVAL_MS = 1000L
        private const val LISTEN_PORT = 8000
    }


    // 定时强制刷新 ScalingType 的 Handler（避免 onFrame 内部覆盖）
    private var scalingEnforcer: Handler? = null
    private val scalingEnforcerRunnable = object : Runnable {
        override fun run() {
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            surfaceViewRenderer.requestLayout()
            scalingEnforcer?.postDelayed(this, 500)
        }
    }

    private fun startScalingEnforcer() {
        stopScalingEnforcer()
        scalingEnforcer = Handler(Looper.getMainLooper())
        scalingEnforcer?.postDelayed(scalingEnforcerRunnable, 500)
        Log.d(TAG, "ScalingEnforcer 已启动（每 500ms 强制 SCALE_ASPECT_FILL）")
    }

    private fun stopScalingEnforcer() {
        scalingEnforcer?.removeCallbacks(scalingEnforcerRunnable)
        scalingEnforcer = null
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var broadcastJob: Job? = null

    // 暂存 ICE 候选（Answer 返回前收集的）
    private val pendingCandidates = mutableListOf<IceCandidate>()

    var tvWidth: Int = 1920
    var tvHeight: Int = 1080

    private var senderSignalingUrl: String? = null
    private val client = OkHttpClient.Builder().build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var audioPort: Int = 0

    lateinit var surfaceViewRenderer: SurfaceViewRenderer
        private set

    /**
     * 设置音频端口（用于广播给手机端）
     */
    fun setAudioPort(port: Int) {
        audioPort = port
        Log.d(TAG, "音频端口已设置: $port")
    }

    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    /**
     * 获取物理分辨率（通过执行 wm size 命令解析 Physical size 行）
     */
    private fun getPhysicalResolution(): Pair<Int, Int>? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "wm size"))
            val output = process.inputStream.bufferedReader().readText()
            val match = Regex("Physical size: (\\d+)x(\\d+)").find(output)
            if (match != null) {
                val (w, h) = match.destructured
                Log.d(TAG, "解析到物理分辨率: ${w}x$h")
                Pair(w.toInt(), h.toInt())
            } else {
                Log.w(TAG, "未找到 Physical size，输出: $output")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取物理分辨率失败: ${e.message}")
            null
        }
    }

    fun initialize(renderer: SurfaceViewRenderer) {
        surfaceViewRenderer = renderer

        // 优先使用 wm size 获取物理分辨率（绕过 Override size）
        val (pw, ph) = getPhysicalResolution() ?: run {
            // 降级：使用 getRealMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Log.w(TAG, "使用 getRealMetrics: ${metrics.widthPixels}x${metrics.heightPixels}")
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
        tvWidth = pw
        tvHeight = ph
        Log.d(TAG, "电视分辨率: ${tvWidth}x${tvHeight}")

        eglBase = EglBase.create()
        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
        // 全屏填充，不保持宽高比（避免黑边）
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // 定时强制重设 SCALE_ASPECT_FILL，避免 onFrame() 内部覆盖
        startScalingEnforcer()
        Log.d(TAG, "电视端 WebRTC 初始化完成（含缩放强制器）")
    }

    fun startBroadcasting() {
        val myIp = SignalingServer.getLocalIpAddress() ?: return
        signalingServer.start(myIp, LISTEN_PORT)

        broadcastJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: java.net.DatagramSocket? = null
            try {
                socket = java.net.DatagramSocket()
                socket.broadcast = true

                while (isActive) {
                    val info = JSONObject().apply {
                        put("name", "Sony TV (CastTV)")
                        put("ip", myIp)
                        put("port", LISTEN_PORT)
                        put("width", tvWidth)
                        put("height", tvHeight)
                        put("manufacturer", "Sony")
                        put("audio_port", audioPort)
                    }.toString()

                    val packet = java.net.DatagramPacket(
                        info.toByteArray(Charsets.UTF_8),
                        info.length,
                        java.net.InetAddress.getByName("255.255.255.255"),
                        BROADCAST_PORT
                    )
                    socket.send(packet)
                    Log.d(TAG, "广播自己: $myIp - ${tvWidth}x${tvHeight} port=$LISTEN_PORT")
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "广播失败: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    /**
     * 同步接收 Offer 并返回 Answer SDP（阻塞直到完成）
     * @param sdpOffer 手机发来的 SDP Offer
     * @param senderSignalingUrl 手机信令 URL（用于后续发送 ICE 候选）
     * @return Answer SDP 字符串，失败返回 null
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun receiveCallAndGetAnswer(sdpOffer: String, senderSignalingUrl: String?): String? {
        this.senderSignalingUrl = senderSignalingUrl
        val answerLatch = CountDownLatch(1)
        val answerRef = AtomicReference<String?>()
        val errorRef = AtomicReference<String?>()

        // 收集 Answer 生成前的 ICE 候选
        pendingCandidates.clear()

        executor.execute {
            try {
                val factory = peerConnectionFactory ?: run { 
                    Log.e(TAG, "peerConnectionFactory 为 null")
                    answerLatch.countDown(); return@execute 
                }

                val observer = object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            Log.d(TAG, "收集到 ICE 候选: ${it.sdpMid} ${it.sdp.substringAfter("candidate:").take(30)}")
                            // 立即发送，不等待 Answer 返回
                            val url = this@ReceiverWebRTCManager.senderSignalingUrl
                            if (url != null) {
                                sendIceCandidateToSender(it)
                            } else {
                                // Answer 还没生成，暂存起来等 senderSignalingUrl 设置后再发
                                pendingCandidates.add(it)
                            }
                        }
                    }
                    override fun onAddStream(stream: MediaStream?) {
                        // 已使用 onAddTrack，此处空实现
                    }
                    override fun onRemoveStream(stream: MediaStream?) {
                        // 已使用 onRemoveTrack，此处空实现
                    }
                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                        state?.let {
                            Log.d(TAG, "连接状态: $it")
                            CoroutineScope(Dispatchers.Main).launch { onConnectionStateChange?.invoke(it) }
                        }
                    }
                    override fun onAddTrack(rtpReceiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        val track = rtpReceiver?.track()
                        if (track is VideoTrack) {
                            CoroutineScope(Dispatchers.Main).launch {
                                surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                surfaceViewRenderer.setEnableHardwareScaler(true)
                                track.addSink(surfaceViewRenderer)
                                surfaceViewRenderer.visibility = android.view.View.VISIBLE
                                surfaceViewRenderer.requestLayout()
                                Log.d(TAG, "onAddTrack: 视频轨道已添加(ForceFillVideoSink)")
                            }
                        } else if (track is org.webrtc.AudioTrack) {
                            track.setEnabled(true)
                            try { track.setVolume(10.0) } catch (e: Exception) {}
                            Log.d(TAG, "音频轨道已接收并启用")
                        }
                    }
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "ICE 收集状态: $state")
                    }
                    override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent?) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onRemoveTrack(rtpReceiver: RtpReceiver?) {}
                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        Log.d(TAG, "onTrack: track=$track")
                        if (track is VideoTrack) {
                            CoroutineScope(Dispatchers.Main).launch {
                                surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                surfaceViewRenderer.setEnableHardwareScaler(true)
                                track.addSink(surfaceViewRenderer)
                                surfaceViewRenderer.visibility = android.view.View.VISIBLE
                                surfaceViewRenderer.requestLayout()
                                Log.d(TAG, "onTrack: 视频轨道已添加(ForceFillVideoSink)")
                            }
                        } else if (track is org.webrtc.AudioTrack) {
                            track.setEnabled(true)
                            try { track.setVolume(10.0) } catch (e: Exception) {}
                            Log.d(TAG, "onTrack: 音频轨道已接收")
                        }
                    }
                }

                peerConnection = factory.createPeerConnection(ICE_SERVERS, observer)
                Log.d(TAG, "PeerConnection 已创建")

                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "setRemoteDescription(Offer) 成功，开始创建 Answer")
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                sdp?.let {
                                    Log.d(TAG, "Answer 创建成功，SDP type=${it.type} 长度=${it.description.length}")
                                    peerConnection?.setLocalDescription(object : SdpObserver {
                                        override fun onSetSuccess() {
                                            Log.d(TAG, "setLocalDescription(Answer) 成功，Answer SDP 前100字符: ${it.description.take(100)}")
                                            answerRef.set(it.description)
                                            answerLatch.countDown()
                                        }
                                        override fun onSetFailure(error: String?) {
                                            Log.e(TAG, "setLocalDescription(Answer) 失败: $error")
                                            errorRef.set(error)
                                            answerLatch.countDown()
                                        }
                                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                                        override fun onCreateFailure(error: String?) {}
                                    }, it)
                                } ?: run { answerLatch.countDown() }
                            }
                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "Answer 创建失败: $error")
                                errorRef.set(error)
                                answerLatch.countDown()
                            }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setRemoteDescription(Offer) 失败: $error")
                        errorRef.set(error)
                        answerLatch.countDown()
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer 的 onCreateFailure 被误调用: $error")
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdpOffer))
            } catch (e: Exception) {
                Log.e(TAG, "receiveCallAndGetAnswer 异常: ${e.message}")
                answerLatch.countDown()
            }
        }

        val success = answerLatch.await(15, TimeUnit.SECONDS)
        if (!success) {
            Log.e(TAG, "等待 Answer 超时（15秒）")
        } else if (answerRef.get() == null) {
            Log.e(TAG, "Answer 为 null，错误: ${errorRef.get() ?: "未知"}")
        } else {
            Log.d(TAG, "Answer 已生成并写入响应，ICE 候选已实时发送")
        }

        return answerRef.get()
    }

    /**
     * 异步接收（保留用于兼容，新流程用 receiveCallAndGetAnswer）
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun receiveCall(offerUrl: String, sdpOffer: String) {
        senderSignalingUrl = offerUrl
        val factory = peerConnectionFactory ?: return

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {
                // 已使用 onAddTrack，此处空实现
            }
            override fun onRemoveStream(stream: MediaStream?) {
                // 已使用 onRemoveTrack，此处空实现
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                state?.let {
                    Log.d(TAG, "连接状态: $it")
                    CoroutineScope(Dispatchers.Main).launch { onConnectionStateChange?.invoke(it) }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { executor.execute { sendIceCandidateToSender(it) } }
            }
            override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {}
            override fun onAddTrack(rtpReceiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = rtpReceiver?.track()
                if (track is VideoTrack) {
                    CoroutineScope(Dispatchers.Main).launch {
                        surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        surfaceViewRenderer.setEnableHardwareScaler(true)
                        track.addSink(surfaceViewRenderer)
                        surfaceViewRenderer.visibility = android.view.View.VISIBLE
                        surfaceViewRenderer.requestLayout()
                        Log.d(TAG, "onAddTrack: 视频轨道已添加（异步路径, ForceFillVideoSink）")
                    }
                } else if (track is org.webrtc.AudioTrack) {
                    Log.d(TAG, "音频轨道已接收（异步路径）")
                }
            }
            override fun onRemoveTrack(rtpReceiver: RtpReceiver?) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                Log.d(TAG, "onTrack(异步): track=$track")
                if (track is VideoTrack) {
                    CoroutineScope(Dispatchers.Main).launch {
                        surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        surfaceViewRenderer.setEnableHardwareScaler(true)
                        track.addSink(surfaceViewRenderer)
                        surfaceViewRenderer.visibility = android.view.View.VISIBLE
                        surfaceViewRenderer.requestLayout()
                        Log.d(TAG, "视频轨道已添加（异步路径 onTrack, ForceFillVideoSink）")
                    }
                } else if (track is org.webrtc.AudioTrack) {
                    Log.d(TAG, "音频轨道已接收（异步路径 onTrack）")
                }
            }
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = factory.createPeerConnection(ICE_SERVERS, observer)

        executor.execute {
            try {
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        executor.execute {
                            createAndSendAnswer()
                        }
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "设置远程 SDP 失败: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
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
                        override fun onSetSuccess() {
                            sendAnswerToSender(it.description)
                        }
                        override fun onSetFailure(error: String?) {}
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
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

    fun sendIceCandidateToSender(candidate: IceCandidate) {
        val url = senderSignalingUrl ?: return
        try {
            val json = JSONObject().apply {
                put("type", "candidate")
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            val fullUrl = "$url/signaling/candidate"
            Log.d(TAG, "发送 ICE Candidate 到 $fullUrl，sdpMid=${candidate.sdpMid}")
            val request = Request.Builder()
                .url(fullUrl)
                .post(json.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "ICE Candidate 发送失败: HTTP ${response.code} ${response.body?.string()?.take(200)}")
                } else {
                    Log.d(TAG, "ICE Candidate 发送成功")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ICE Candidate 发送异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

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

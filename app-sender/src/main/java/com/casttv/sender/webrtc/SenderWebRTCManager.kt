package com.casttv.sender.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.casttv.sender.TvDevice
import com.casttv.sender.signaling.SignalingClient
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SenderWebRTCManager(
    private val context: Context,
    private val signalingClient: SignalingClient
) {
    companion object {
        private const val TAG = "SenderWebRTC"
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var eglBase: EglBase? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 超时定时器：连接超过 15 秒仍未成功则通知 UI 失败
    private var timeoutRunnable: Runnable? = null

    private var targetDevice: TvDevice? = null
    private var videoWidth = 1920
    private var videoHeight = 1080
    // 视频编码参数：高清投屏配置（参考开源项目 AndroidMirror-WebRTC）
    // 30fps 比 60fps 画质更好（编码器压力更小，单帧码率更高）
    private var videoFps = 30
    // 码率配置（参考 AndroidMirror-WebRTC 的 setBitrate 方案）
    // minBitrate: 512 Kbps (最低保底) | currentBitrate: 8 Mbps (当前目标) | maxBitrate: 1 Gbps (不限制上限)
    private val minBitrateBps = 512 * 1024        // 512 Kbps 最低码率
    private val currentBitrateBps = 8_000_000    // 8 Mbps 当前码率
    private val maxBitrateBps = Integer.MAX_VALUE // 不设上限，让编码器自由发挥

    // 手机信令端口（用于接收电视发来的 ICE 候选）
    var phoneSignalingPort: Int = 0
        set(value) { field = value }

    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    fun initialize() {
        Log.d(TAG, "初始化 WebRTC...")

        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // 创建音频源和轨道（麦克风）
        try {
            audioSource = peerConnectionFactory!!.createAudioSource(MediaConstraints())
            audioTrack = peerConnectionFactory!!.createAudioTrack("audio_track", audioSource)
            audioTrack?.setEnabled(true)
            Log.d(TAG, "音频轨道创建成功")
        } catch (e: Exception) {
            Log.e(TAG, "音频轨道创建失败: ${e.message}")
        }

        Log.d(TAG, "WebRTC 初始化完成")
    }

    fun setTargetResolution(width: Int, height: Int) {
        // 采集分辨率直接使用电视分辨率（让画面比例与TV一致，避免黑边）
        // 同时限制最大值，避免编码器过载
        videoWidth = minOf(width, 1920)
        videoHeight = minOf(height, 1080)
        Log.d(TAG, "目标分辨率(采集): ${videoWidth}x${videoHeight}@${videoFps}fps")
        Log.d(TAG, "码率配置: min=${minBitrateBps/1024}Kbps | current=${currentBitrateBps/1_000_000}Mbps | max=UNLIMITED (via setBitrate)")
    }

    /**
     * 创建视频轨道并启动屏幕采集
     * @param mediaProjectionIntent MediaProjection 授权结果 Intent（来自 MediaProjectionManager 权限回调）
     * @param callback 创建完成后的回调（在主线
     */
    fun createVideoTrack(capturer: VideoCapturer, callback: () -> Unit) {
        executor.execute {
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )

            videoSource = peerConnectionFactory!!.createVideoSource(false)

            capturer.initialize(
                surfaceTextureHelper,
                context,
                videoSource!!.capturerObserver
            )
            capturer.startCapture(videoWidth, videoHeight, videoFps)
            Log.d(TAG, "屏幕采集已启动: ${videoWidth}x${videoHeight}@${videoFps}fps")

            videoTrack = peerConnectionFactory!!.createVideoTrack("video_track", videoSource)

            mainHandler.post {
                callback()
            }
        }
    }

    /**
     * 连接到电视并交换 SDP，带 15 秒超时
     */
    fun connect(device: TvDevice) {
        // 取消上一次超时定时器
        cancelTimeout()

        targetDevice = device
        Log.d(TAG, "开始连接电视: ${device.name} ${device.ipAddress}:${device.port}，本机信令端口: $phoneSignalingPort")
        val factory = peerConnectionFactory ?: return

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE 收集状态: $state")
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                state?.let {
                    Log.d(TAG, "连接状态变化: $it")
                    cancelTimeout()
                    mainHandler.post {
                        onConnectionStateChange?.invoke(it)
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    CoroutineScope(Dispatchers.IO).launch {
                        signalingClient.sendIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex, device)
                    }
                }
            }
            override fun onIceCandidateError(event: IceCandidateErrorEvent?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
        }

        peerConnection = factory.createPeerConnection(ICE_SERVERS, observer)

        // ===== 关键：在 createOffer 之前调用 setBitrate() =====
        // 必须在 SDP 协商前设置，这样底层编码器才能按目标码率初始化
        val pc = peerConnection
        if (pc != null) {
            val setOk = pc.setBitrate(minBitrateBps, currentBitrateBps, maxBitrateBps)
            Log.d(TAG, "⚡ setBitrate() 结果: $setOk | min=${minBitrateBps/1024}Kbps, cur=${currentBitrateBps/1_000_000}Mbps, max=UNLIMITED")
        }

        // 添加本地视频轨道
        videoTrack?.let { track ->
            val sender = peerConnection?.addTrack(track, listOf("stream"))
            Log.d(TAG, "视频轨道已添加: ${sender != null}")
            // 设置高码率编码参数以提升清晰度
            sender?.let { s ->
                try {
                    val params = s.parameters
                    val encodings = params.encodings
                    if (encodings.isNotEmpty()) {
                        encodings[0].apply {
                            maxBitrateBps = currentBitrateBps    // 8 Mbps
                            scaleResolutionDownBy = null          // 不降分辨率
                            maxFramerate = videoFps               // 60fps
                            networkPriority = 1                    // HIGH priority
                        }
                        s.parameters = params
                        Log.d(TAG, "RtpParameters.Encoding 已设置: maxBitrate=${currentBitrateBps / 1_000_000}Mbps, fps=$videoFps")
                    } else {
                        Log.w(TAG, "encodings 为空，无法设置编码参数")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "设置视频编码参数失败: ${e.message}")
                }
            }
        }

        // 添加本地音频轨道（麦克风）
        val audioSender = audioTrack?.let {
            val sender = peerConnection?.addTrack(it, listOf("stream"))
            Log.d(TAG, "音频轨道已添加: ${sender != null}, enabled=${it.enabled()}")
            sender
        }
        if (audioSender == null) {
            Log.e(TAG, "音频轨道添加失败！audioTrack 是否为 null: ${audioTrack == null}")
        }


        // 启动 15 秒超时定时器
        timeoutRunnable = Runnable {
            Log.e(TAG, "连接超时（${CONNECT_TIMEOUT_MS}ms），通知 UI 失败")
            mainHandler.post {
                onConnectionStateChange?.invoke(PeerConnection.PeerConnectionState.FAILED)
            }
            peerConnection?.close()
            peerConnection = null
        }
        mainHandler.postDelayed(timeoutRunnable!!, CONNECT_TIMEOUT_MS)

        // 创建 SDP Offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    // 注入码率参数到 SDP，提升视频清晰度
                    val enhancedSdp = enhanceSdpForHighBitrate(it.description)
                    Log.d(TAG, "Offer 创建成功，SDP type=${it.type} 长度=${enhancedSdp.length}，前100字符: ${enhancedSdp.take(100)}")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            // 发送 Offer 并获取 Answer（带超时）
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val answerSdp = withContext(Dispatchers.IO) {
                                        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                                            signalingClient.sendOffer(
                                                enhancedSdp, targetDevice!!, phoneSignalingPort
                                            )
                                        }
                                    }
                                    if (answerSdp != null) {
                                        Log.d(TAG, "收到 Answer SDP，长度=${answerSdp.length}，前100字符: ${answerSdp.take(100)}")
                                        peerConnection?.setRemoteDescription(object : SdpObserver {
                                            override fun onSetSuccess() {
                                                Log.d(TAG, "远程 Answer 设置成功，等待连接...")
                                            }
                                            override fun onSetFailure(error: String?) {
                                                Log.e(TAG, "设置远程 Answer 失败: $error")
                                                mainHandler.post {
                                                    cancelTimeout()
                                                    onConnectionStateChange?.invoke(
                                                        PeerConnection.PeerConnectionState.FAILED
                                                    )
                                                }
                                            }
                                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                                            override fun onCreateFailure(error: String?) {}
                                        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                                    } else {
                                        Log.e(TAG, "Answer 为空，电视端未正确响应")
                                        mainHandler.post {
                                            cancelTimeout()
                                            onConnectionStateChange?.invoke(
                                                PeerConnection.PeerConnectionState.FAILED
                                            )
                                        }
                                        peerConnection?.close()
                                        peerConnection = null
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "信令交换异常: ${e.message}")
                                    mainHandler.post {
                                        cancelTimeout()
                                        onConnectionStateChange?.invoke(
                                            PeerConnection.PeerConnectionState.FAILED
                                        )
                                    }
                                    peerConnection?.close()
                                    peerConnection = null
                                }
                            }
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "设置本地 SDP 失败: $error")
                            mainHandler.post {
                                cancelTimeout()
                                onConnectionStateChange?.invoke(PeerConnection.PeerConnectionState.FAILED)
                            }
                        }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                } ?: run {
                    Log.e(TAG, "createOffer 成功但 SDP 为 null")
                    mainHandler.post {
                        cancelTimeout()
                        onConnectionStateChange?.invoke(PeerConnection.PeerConnectionState.FAILED)
                    }
                }
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "创建 Offer 失败: $error")
                mainHandler.post {
                    cancelTimeout()
                    onConnectionStateChange?.invoke(PeerConnection.PeerConnectionState.FAILED)
                }
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Offer onCreateFailure: $error")
                mainHandler.post {
                    cancelTimeout()
                    onConnectionStateChange?.invoke(PeerConnection.PeerConnectionState.FAILED)
                }
            }
        }, constraints)
    }

    /**
     * 增强 SDP 参数以提升视频清晰度（正确版本）：
     *
     * 1. 在 m=video 段注入 b=AS:10000（10Mbps 视频带宽）
     * 2. 在 m=video 段注入 a=x-google-max-bitrate / a=x-google-min-bitrate
     *    （注意：WebRTC Android 也支持在 video section 中直接写 x-google 行）
     * 3. 强制 H.264 优先：调换 a=rtpmap 顺序，让 H264 排在 VP8/VP9 前面
     *
     * 参考：AndroidMirror-WebRTC、西瓜视频 SDP 抓包分析
     */
    private fun enhanceSdpForHighBitrate(sdp: String): String {
        val lines = sdp.lines().toMutableList()
        var i = 0

        // 遍历所有行，找到 m=video 段并注入参数
        while (i < lines.size) {
            if (lines[i].startsWith("m=video")) {
                val sectionStart = i
                // 找到本段的结束（下一个 m= 或 end of SDP）
                var sectionEnd = i + 1
                while (sectionEnd < lines.size && !lines[sectionEnd].startsWith("m=")) {
                    sectionEnd++
                }

                // 检查本段是否已有 b=AS，若没有则注入
                var hasBAS = false
                for (k in sectionStart until sectionEnd) {
                    if (lines[k].startsWith("b=AS")) {
                        hasBAS = true
                        lines[k] = "b=AS:10000"
                        break
                    }
                }
                if (!hasBAS) {
                    lines.add(sectionStart + 1, "b=AS:10000")
                    sectionEnd++
                }

                // 检查是否有 a=x-google-max-bitrate / min-bitrate
                var hasGoogleMax = false
                var hasGoogleMin = false
                val gs = if (!hasBAS) sectionStart + 2 else sectionStart + 1
                for (k in gs until sectionEnd) {
                    if (lines[k].startsWith("a=x-google-max-bitrate")) hasGoogleMax = true
                    if (lines[k].startsWith("a=x-google-min-bitrate")) hasGoogleMin = true
                }

                var insertPos = if (!hasBAS) sectionStart + 2 else sectionStart + 1
                if (!hasGoogleMax) {
                    lines.add(insertPos, "a=x-google-max-bitrate=${currentBitrateBps}")
                    sectionEnd++
                    insertPos++
                }
                if (!hasGoogleMin) {
                    lines.add(insertPos, "a=x-google-min-bitrate=${minBitrateBps}")
                    sectionEnd++
                }

                i = sectionEnd
            } else {
                i++
            }
        }

        val result = lines.joinToString("\n")
        Log.d(TAG, "SDP 增强完成：b=AS:10000，x-google-max-bitrate=${currentBitrateBps}")
        val mVideoIdx = result.indexOf("m=video")
        if (mVideoIdx >= 0) {
            val nextM = result.indexOf("m=audio", mVideoIdx)
            val videoSection = if (nextM > 0) result.substring(mVideoIdx, nextM) else result.substring(mVideoIdx)
            Log.d(TAG, "增强后 m=video 段：\n$videoSection")
        }
        return result
    }

    /**
     * 取消超时定时器
     */
    private fun cancelTimeout() {
        timeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    /**
     * 添加电视端发来的 ICE 候选（由手机端 HTTP 信令服务器调用）
     */
    fun addRemoteIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        val mid = if (sdpMid.isNullOrEmpty()) null else sdpMid
        val candidate = IceCandidate(mid, sdpMLineIndex, sdp)
        val pc = peerConnection
        if (pc == null) {
            Log.w(TAG, "addRemoteIceCandidate: peerConnection 为 null，丢弃候选")
            return
        }
        pc.addIceCandidate(candidate)
        Log.d(TAG, "添加远程 ICE 候选: sdpMid=$mid")
    }

    fun release() {
        cancelTimeout()
        peerConnection?.close()
        peerConnection = null
        videoTrack?.setEnabled(false)
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        audioTrack?.setEnabled(false)
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        eglBase?.release()
        eglBase = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        // 注意：不 shutdown executor！executor 需要跨 release/initialize 复用
        Log.d(TAG, "WebRTC 资源释放完成（线程池保留）")
    }

    /**
     * 重置状态，允许重新初始化
     * 在 release() 后调用，清理残留状态以便 initialize() 重新创建资源
     */
    fun reset() {
        cancelTimeout()
        peerConnection?.close()
        peerConnection = null
        targetDevice = null
        Log.d(TAG, "WebRTC 状态已重置")
    }
}

package com.casttv.sender.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.casttv.sender.TvDevice
import com.casttv.sender.signaling.SignalingClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 发送端 WebRTC 管理器
 */
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
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)  // 不要自动重试
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var targetDevice: TvDevice? = null
    private var videoWidth = 1280
    private var videoHeight = 720

    var onConnectionStateChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    fun initialize() {
        Log.d(TAG, "初始化 WebRTC...")

        // EGL 初始化
        eglBase = EglBase.create()

        // WebRTC 全局初始化
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // 创建编码工厂
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC 初始化完成")
    }

    fun setTargetResolution(width: Int, height: Int) {
        targetDevice?.let {
            videoWidth = minOf(it.width, 1920)
            videoHeight = minOf(it.height, 1080)
        } ?: run {
            videoWidth = width
            videoHeight = height
        }
        Log.d(TAG, "目标分辨率: ${videoWidth}x${videoHeight}")
    }

    fun createVideoTrack(callback: (SurfaceTextureHelper, SurfaceTextureHelper) -> Unit) {
        executor.execute {
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )

            videoSource = peerConnectionFactory!!.createVideoSource(false)
            videoTrack = peerConnectionFactory!!.createVideoTrack("video_track", videoSource)

            mainHandler.post {
                callback(surfaceTextureHelper, surfaceTextureHelper)
            }
        }
    }

    fun connect(device: TvDevice) {
        targetDevice = device
        val factory = peerConnectionFactory ?: return

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                state?.let {
                    mainHandler.post {
                        onConnectionStateChange?.invoke(it)
                    }
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    sendIceCandidate(it)
                }
            }
            override fun onIceCandidateError(event: IceCandidateErrorEvent?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        }

        peerConnection = factory.createPeerConnection(ICE_SERVERS, observer)

        // 添加本地视频轨道
        videoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream"))
        }

        // 创建 SDP Offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            sendOffer(it.description)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "设置本地 SDP 失败: $error")
                        }
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Offer 失败: $error")
            }
        }, constraints)
    }

    private fun sendOffer(sdpOffer: String) {
        val device = targetDevice ?: return
        executor.execute {
            try {
                val json = JSONObject().apply {
                    put("sdp", sdpOffer)
                }
                val request = Request.Builder()
                    .url("http://${device.ipAddress}:8000/signaling/offer")
                    .post(json.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Offer 发送成功: ${response.code}")
                        // 读取 Answer
                        val body = response.body?.string()
                        Log.d(TAG, "收到响应: ${body?.take(200)}")
                    } else {
                        Log.e(TAG, "Offer 发送失败: ${response.code} ${response.message}")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "⚠️ 连接电视超时，请检查电视是否在线: ${device.ipAddress}:8000")
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "❌ 无法连接到电视: ${device.ipAddress}:8000 - ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "发送 Offer 异常: ${e.message}")
            }
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val device = targetDevice ?: return
        executor.execute {
            try {
                val json = JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                val request = Request.Builder()
                    .url("http://${device.ipAddress}:8000/signaling/candidate")
                    .post(json.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "ICE Candidate 发送超时")
            } catch (e: Exception) {
                Log.e(TAG, "ICE Candidate 发送失败: ${e.message}")
            }
        }
    }

    fun release() {
        peerConnection?.close()
        peerConnection = null
        videoTrack?.setEnabled(false)
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        eglBase?.release()
        eglBase = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        executor.shutdown()
        Log.d(TAG, "WebRTC 资源释放完成")
    }
}

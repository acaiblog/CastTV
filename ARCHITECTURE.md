# CastTV 技术架构文档

> 更新时间：2026-05-10  
> 版本：Sender v1.8 / Receiver v1.9

---

## 一、项目概述

| 项目 | 说明 |
|------|------|
| 语言/框架 | Kotlin + Android SDK |
| 最小 SDK | Sender: API 24 / Receiver: API 26 |
| 目标 SDK | API 34 |
| WebRTC 引擎 | `io.getstream:stream-webrtc-android:1.1.1`（BSD 许可，免费） |
| 网络库 | OkHttp 4.12.0 |
| 异步框架 | Kotlin Coroutines |

CastTV 是一款局域网屏幕投屏应用，由**发送端（手机）**和**接收端（电视）**两个独立 APK 组成，通过 WebRTC 实现低延迟音视频传输。

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────┐
│                   手机（Sender）                      │
│                                                     │
│  ┌─────────────┐    ┌──────────────────────────┐   │
│  │ MainActivity │    │  ScreenCaptureService    │   │
│  │  (UI/发现)  │    │  (前台服务, 屏幕采集)    │   │
│  └──────┬──────┘    └──────────┬───────────────┘   │
│         │                        │                     │
│         ▼                        ▼                     │
│  ┌─────────────────────────────────────────────┐      │
│  │           SenderWebRTCManager              │      │
│  │  创建 Offer / 添加音视频 Track / 建立连接  │      │
│  └──────────────────┬──────────────────────────┘      │
│                     │                                  │
│  ┌──────────────────▼──────────────────────────┐      │
│  │          SignalingClient (OkHttp)           │      │
│  │  发送 Offer → TV / 发送 ICE Candidate      │      │
│  └─────────────────────────────────────────────┘      │
│                                                     │
│  ┌─────────────────────────────────────────────┐      │
│  │          AudioCaptureManager                │      │
│  │  AudioPlaybackCapture → TCP → TV:8001     │      │
│  └─────────────────────────────────────────────┘      │
│                                                     │
│  ┌─────────────────────────────────────────────┐      │
│  │          TvDeviceFinder (UDP)              │      │
│  │  监听 UDP 5000 广播，发现局域网电视设备     │      │
│  └─────────────────────────────────────────────┘      │
└───────────────────────┬─────────────────────────────┘
                        │
              ┌─────────▼─────────┐
              │  局域网 (Wi-Fi)    │
              └─────────┬─────────┘
                        │
┌───────────────────────▼─────────────────────────────┐
│                   电视（Receiver）                    │
│                                                     │
│  ┌─────────────────────────────────────────────┐      │
│  │           MainActivity                      │      │
│  │  UI显示 / HTTP ServerSocket (端口 8000)   │      │
│  │  处理 /signaling/offer → 调用 WebRTCMgr   │      │
│  └──────────────────┬──────────────────────────┘      │
│                     │                                  │
│  ┌──────────────────▼──────────────────────────┐      │
│  │        ReceiverWebRTCManager                 │      │
│  │  接收 Offer / 生成 Answer / 渲染视频       │      │
│  │  ScalingEnforcer (定时强制 FILL 缩放)      │      │
│  └──────────────────┬──────────────────────────┘      │
│                     │                                  │
│  ┌──────────────────▼──────────────────────────┐      │
│  │        AudioPlayerManager                   │      │
│  │  TCP Server (端口 8001) / AudioTrack     │      │
│  │  接收手机 PCM 音频并实时播放               │      │
│  └─────────────────────────────────────────────┘      │
│                                                     │
│  ┌─────────────────────────────────────────────┐      │
│  │   UDP 定时广播 (端口 5000)                 │      │
│  │   广播内容: {name,ip,port,width,height}   │      │
│  └─────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────┘
```

---

## 三、模块详解

### 3.1 发送端（app-sender）

| 类 | 职责 |
|----|------|
| `MainActivity` | 主界面，触发设备发现、发起连接、处理权限回调 |
| `TvDevice` | 数据模型：电视名称、IP、端口、分辨率 |
| `TvDeviceFinder` | UDP 5000 端口监听电视广播，解析 `TvDevice` |
| `SenderWebRTCManager` | WebRTC 核心：创建 Offer、添加音视频 Track、设置码率 |
| `SignalingClient` | OkHttp 客户端，发送 Offer/Answer/Candidate |
| `ScreenCaptureService` | 前台服务（Android 10+ 强制要求），创建 `ScreenCapturerAndroid` |
| `AudioCaptureManager` | 采集系统音频（Android 10+ `AudioPlaybackCapture`），TCP 发送至电视 |

**连接流程（Sender 侧）：**
1. `TvDeviceFinder.discoverDevices()` 监听 UDP 5000 广播，发现电视
2. 用户选择电视 → `SenderWebRTCManager.connect(device)`
3. 创建 `ScreenCapturerAndroid` 采集屏幕
4. 创建 SDP Offer → 通过 `SignalingClient.sendOffer()` POST 到 `http://tv_ip:8000/signaling/offer`
5. 收到 Answer → `setRemoteDescription()`
6. ICE Candidate 通过 `SignalingClient.sendIceCandidate()` 发送
7. `AudioCaptureManager` 连接电视 `ip:8001` 发送 PCM 音频

---

### 3.2 接收端（app-receiver）

| 类 | 职责 |
|----|------|
| `MainActivity` | 主界面，启动 HTTP ServerSocket、显示连接状态、全屏沉浸 |
| `ReceiverWebRTCManager` | WebRTC 核心：接收 Offer、生成 Answer、渲染视频帧 |
| `SignalingServer` | 信令辅助类（工具方法，HTTP 服务实际在 `MainActivity` 实现） |
| `AudioPlayerManager` | TCP Server（端口 8001），接收 PCM 并用 `AudioTrack` 播放 |

**连接流程（Receiver 侧）：**
1. 启动后定时 UDP 广播自己的 IP/分辨率（端口 5000）
2. `MainActivity.startListening()` 启动 `ServerSocket(8000)` 监听 HTTP 请求
3. 收到 `POST /signaling/offer` → 调用 `ReceiverWebRTCManager.receiveCallAndGetAnswer()`
4. 生成 Answer → 写入 HTTP 响应返回给手机
5. ICE Candidate 实时通过 HTTP 发送给手机
6. 视频 Track 通过 `addSink(surfaceViewRenderer)` 渲染到 `SurfaceViewRenderer`
7. `ScalingEnforcer`（Handler 定时任务）每 500ms 强制 `SCALE_ASPECT_FILL` 避免黑边
8. 音频 TCP 连接进入 `AudioPlayerManager`，`AudioTrack` 实时播放

---

## 四、信令协议

### 4.1 设备发现（UDP 广播）

```
电视 → 广播 → 手机
端口：5000（广播）/ 5000（接收）
格式：JSON UTF-8

{
  "name": "Sony TV (CastTV)",
  "ip": "192.168.1.119",
  "port": 8000,
  "width": 1920,
  "height": 1080,
  "manufacturer": "Sony",
  "audio_port": 8001
}
```

### 4.2 WebRTC 信令（HTTP）

| 端点 | 方法 | 说明 |
|------|------|------|
| `POST /signaling/offer` | POST | 手机发送 SDP Offer，电视返回 SDP Answer（同步，响应体即 Answer JSON） |
| `POST /signaling/candidate` | POST | 双向交换 ICE Candidate |

**Offer 请求体：**
```json
{
  "type": "offer",
  "sdp": "v=0\r\no=-...",
  "signaling_port": 42315
}
```

**Offer 响应体（Answer）：**
```json
{
  "type": "answer",
  "sdp": "v=0\r\no=-..."
}
```

### 4.3 音频传输（TCP）

| 方向 | 协议 | 端口 |
|------|------|------|
| 手机 → 电视 | TCP/PCM 44100Hz/stereo/16bit | 8001 |

---

## 五、视频参数配置

| 参数 | 值 | 说明 |
|------|-----|------|
| 采集分辨率 | 电视物理分辨率（最大 1920×1080） | 通过 `wm size` 获取物理分辨率 |
| 帧率 | 30 fps | 比 60fps 画质更好（编码压力小） |
| 码率 | 8 Mbps（当前）/ 最小 512 Kbps / 最大不限制 | 通过 `setBitrate()` + SDP 注入 `b=AS:10000` |
| 缩放模式 | `SCALE_ASPECT_FILL` | 全屏填充，定时强制重设避免被覆盖 |
| 编码器 | H.264 优先（通过 SDP 调整 rtpmap 顺序） | `DefaultVideoEncoderFactory(hardwareAccelerated=true)` |

---

## 六、已知问题与限制

| 问题 | 状态 | 说明 |
|------|------|------|
| v1.8 电视端 ForceFillVideoSink 导致 native crash | ✅ 已修复（v1.9 移除代理类，改用 Handler 定时重设） | JNI 递归调用导致 crash_dump32 |
| 电视端 HTTP 服务器未在 `SignalingServer` 中实现 | ⚠️ 已知（`MainActivity` 中直接实现 ServerSocket） | 架构上可重构，功能正常 |
| `DELETE_FAILED_INTERNAL_ERROR` | ⚠️ Sony TV 限制 | 需使用 `adb install -r` 覆盖安装 |
| 音频采集需要 Android 10+ | ⚠️ 限制 | Android 9 及以下降级使用麦克风 |
| WebRTC 硬件编码兼容性 | ⚠️ 依赖设备 | 部分设备可能回退到软编 |

---

## 七、版本历史

| 版本 | 模块 | versionCode | 主要变更 |
|------|------|-------------|----------|
| v1.8 | sender | 9 | 初始功能：屏幕采集 + WebRTC 投屏 |
| v1.8 | receiver | 9 | 引入 ForceFillVideoSink → native crash |
| v1.9 | receiver | 10 | 修复 native crash，改用 ScalingEnforcer Handler |

---

## 八、设备信息

| 设备 | IP | adb 端口 | 角色 |
|------|-----|-----------|------|
| Sony TV | 192.168.1.119 | 5555 | Receiver |
| 手机 (V2453A) | 192.168.1.121 | 40231 | Sender |

# CastTV - 免费开源电视投屏 App

> 完全免费，无需任何第三方 SDK，基于 WebRTC + MediaProjection 构建

## 项目结构

```
CastTV/
├── app-sender/          ← 手机端 App（发送投屏）
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/casttv/sender/
│   │   │   ├── MainActivity.kt          ← 主界面 + 设备列表
│   │   │   ├── TvDevice.kt              ← 设备数据模型
│   │   │   ├── discovery/
│   │   │   │   └── TvDeviceFinder.kt    ← UDP 电视发现
│   │   │   ├── projection/
│   │   │   │   └── ScreenCaptureService.kt  ← MediaProjection 截屏服务
│   │   │   ├── signaling/
│   │   │   │   └── SignalingClient.kt   ← WebRTC SDP 信令客户端
│   │   │   └── webrtc/
│   │   │       └── SenderWebRTCManager.kt ← 发送端 WebRTC 管理
│   │   └── res/                         ← 布局 + 资源
│   └── build.gradle.kts
│
├── app-receiver/        ← 电视端 App（接收画面）
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/casttv/receiver/
│   │   │   ├── MainActivity.kt          ← 电视主界面
│   │   │   ├── signaling/
│   │   │   │   └── SignalingServer.kt   ← HTTP 信令服务器
│   │   │   └── webrtc/
│   │   │       └── ReceiverWebRTCManager.kt ← 接收端 WebRTC 管理
│   │   └── res/
│   └── build.gradle.kts
│
├── build.gradle.kts     ← 根级构建配置
├── settings.gradle.kts
└── gradle.properties
```

## 技术方案

| 技术 | 用途 | 说明 |
|------|------|------|
| **MediaProjection** | 屏幕捕获 | Android 5+ 官方 API，系统级截屏 |
| **VirtualDisplay** | 分辨率适配 | 以电视分辨率创建虚拟屏幕，实现自适应 |
| **WebRTC** | 视频传输 | org.webrtc:google-webrtc，完全免费 |
| **H.264 硬编码** | 视频压缩 | MediaCodec 硬件编码，CPU 零负担 |
| **UDP 广播** | 设备发现 | 电视定时广播，零配置发现 |
| **HTTP 信令** | SDP 交换 | 局域网直连，无外网依赖 |

## 分辨率自适应原理

```
电视端启动 → UDP 广播 {width, height} → 手机发现 →

手机截屏编码 → VirtualDisplay(w=电视宽, h=电视高) →

WebRTC P2P 流 → 电视解码渲染 → 逐帧对齐电视分辨率
```

## 构建说明

### 前置条件
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17+
- Android SDK 34

### 构建步骤

**方法一：Android Studio 直接打开**
1. Android Studio → File → Open → 选择 `CastTV` 文件夹
2. 等待 Gradle Sync 完成
3. 同步完成后 Build → Build Bundle(s) / APK(s)

**方法二：命令行构建**
```bash
# 首次构建（自动下载 Gradle + Android SDK）
cd CastTV
gradlew assembleDebug

# APK 输出位置
# app-sender/build/outputs/apk/debug/app-sender-debug.apk
# app-receiver/build/outputs/apk/debug/app-receiver-debug.apk
```

### 安装到设备

**手机端（发送端）：**
```bash
adb install app-sender/build/outputs/apk/debug/app-sender-debug.apk
```

**电视端（接收端）：**
```bash
# 通过 ADB 连接索尼电视（同一局域网）
adb connect <电视IP>:5555
adb install app-receiver/build/outputs/apk/debug/app-receiver-debug.apk
```

### 使用方法

1. 在索尼 Android TV 上安装并启动 `CastTV 接收端`
2. 在手机上安装并启动 `CastTV 投屏`
3. 手机 App 自动发现局域网内的索尼电视
4. 选择电视 → 点击"开始投屏" → 授权截屏权限
5. 画面即可投屏到电视，分辨率自动匹配电视

## 索尼电视特殊说明

- 索尼 Android TV 需要开启"安全与限制"中的"未知来源"
- 如果 ADB 连接不上：在电视设置 → 网络 → IP 地址 确认
- 默认 ADB 连接端口：5555

## 免费说明

本项目所有依赖均为免费开源：
- `io.getstream:stream-webrtc-android:1.1.1` — BSD+专利许可
- `io.getstream:stream-webrtc-android` — 支持 H.264 硬件编解码
- 无任何授权费用，无广告，完全免费

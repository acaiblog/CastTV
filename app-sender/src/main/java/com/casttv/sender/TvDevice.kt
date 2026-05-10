package com.casttv.sender

/**
 * 发现的电视设备
 */
data class TvDevice(
    val name: String,        // 显示名称，如 "索尼电视"
    val ipAddress: String,   // IP 地址
    val port: Int = 8000,    // 信令端口
    val audioPort: Int = 8001, // 音频端口
    val width: Int,          // 电视分辨率宽
    val height: Int,         // 电视分辨率高
    val manufacturer: String = "Unknown"  // 厂商
) {
    val resolution: String
        get() = "${width}x${height}"

    val resolutionLabel: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            else -> "${height}p"
        }
}

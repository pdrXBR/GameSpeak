package com.example.voip.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress

fun Context.getLocalIpAddress(): String? {
    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ip = wifiManager.connectionInfo?.ipAddress ?: return null
    return InetAddress.getByAddress(byteArrayOf(
        (ip and 0xFF).toByte(),
        (ip shr 8 and 0xFF).toByte(),
        (ip shr 16 and 0xFF).toByte(),
        (ip shr 24 and 0xFF).toByte()
    )).hostAddress
}
package me.weishu.kernelsu.ui.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

/**
 * 设备码：与 GateActivity 卡密验证服务端绑定的唯一设备标识。
 * 算法必须与 sbkey 服务端约定保持一致，不可随意改动，
 * 否则已激活设备会因设备码漂移而验证失败。
 */
object DeviceCode {
    private const val SALT = "sb-manager-v1"

    @JvmStatic
    fun get(context: Context): String {
        return try {
            val id = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val md = MessageDigest.getInstance("SHA-256")
            val d = md.digest("$SALT|$id".toByteArray(Charsets.UTF_8))
            val hex = StringBuilder()
            for (i in 0 until 6) hex.append(String.format(Locale.US, "%02X", d[i].toInt() and 0xFF))
            val s = hex.toString()
            "${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8, 12)}"
        } catch (e: Exception) {
            "0000-0000-0000"
        }
    }
}

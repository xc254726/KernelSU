package me.weishu.kernelsu.hide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.weishu.kernelsu.MainActivityKowsu

/**
 * 拨号盘暗号入口。
 *
 * 系统拨号盘输入 `*#*#7891#*#*` 后，电话应用会发一条
 * `act=android.provider.Telephony.SECRET_CODE dat=android_secret_code://7891` 的广播。
 * 这里在 manifest 里只声明了 scheme（不写 host），所以**任意数字**都能收到，
 * 具体匹配哪个由用户自己设的暗号决定，改暗号不用重装。
 *
 * 收到且匹配 → 直接拉起主界面（GateActivity 被隐藏时也能进来）。
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return

        val code = intent.data?.host ?: return
        if (!code.equals(HidePrefs.code(context), ignoreCase = true)) return

        val launch = Intent(context, MainActivityKowsu::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { context.startActivity(launch) }
    }

    private companion object {
        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
    }
}

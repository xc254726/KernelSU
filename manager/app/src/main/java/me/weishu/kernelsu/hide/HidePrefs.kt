package me.weishu.kernelsu.hide

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import me.weishu.kernelsu.GateActivity

/**
 * 「隐藏面具」的本地状态。
 *
 * 只做两件事：
 *   1. 记住拨号暗号（纯数字，默认 7891）；
 *   2. 记住桌面图标是否隐藏，并真正去 enable / disable 那个 LAUNCHER 组件。
 *
 * 隐藏图标不是卸载：包还在、root 授权还在、模块管理照样能用，
 * 只是桌面上、以及桌面搜索里都找不到入口，只能靠拨号暗号进来。
 */
object HidePrefs {

    private const val PREFS = "chen_hide"
    private const val KEY_CODE = "secret_code"
    private const val KEY_ICON_HIDDEN = "icon_hidden"

    const val DEFAULT_CODE = "7891"
    const val MIN_LEN = 3
    const val MAX_LEN = 10

    fun isCodeValid(code: String): Boolean =
        code.length in MIN_LEN..MAX_LEN && code.all { it.isDigit() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun code(context: Context): String =
        prefs(context).getString(KEY_CODE, DEFAULT_CODE) ?: DEFAULT_CODE

    fun setCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code).apply()
    }

    /** 唯一带 LAUNCHER 的组件（GateActivity），隐藏它等于「从桌面消失」。 */
    private fun launcherComponent(context: Context) =
        ComponentName(context, GateActivity::class.java)

    fun isIconHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(launcherComponent(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    fun setIconHidden(context: Context, hidden: Boolean): Boolean {
        val pm = context.packageManager
        val target = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        return try {
            pm.setComponentEnabledSetting(
                launcherComponent(context),
                target,
                PackageManager.DONT_KILL_APP
            )
            // 部分桌面（尤其是 ColorOS）需要一点时间才刷新，这里顺便记下来，
            // 设置页下次打开就能显示正确的状态。
            prefs(context).edit().putBoolean(KEY_ICON_HIDDEN, hidden).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}

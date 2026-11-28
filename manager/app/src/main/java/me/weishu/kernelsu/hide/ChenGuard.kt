package me.weishu.kernelsu.hide

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Base64
import me.weishu.kernelsu.ui.util.createRootShell

/**
 * 「隐藏与找回」的落地实现。
 *
 * 思路：Chen 卸载之后，靠一个**不依赖任何 app** 的 root 守护（/data/adb/chen_guard）
 * 把 Chen 装回来。守护由 /data/adb/service.d 在开机时以 root 拉起，Chen 在不在都不影响它。
 *
 *   布置守护 : 把 guard.sh / 开机入口 / 自身 APK 副本落到 /data/adb/chen_guard，
 *              写配置（触发应用 + 触发方式），并立刻把守护起起来
 *   隐藏     : 让守护备份 Chen 的应用数据后 pm uninstall 自己（Chen 从系统里消失）
 *   找回     : 打开指定的应用（+ 按音量键）→ 守护 pm install + 恢复数据 + am start
 */
object ChenGuard {

    const val DIR = "/data/adb/chen_guard"
    private const val CONF = "$DIR/conf"
    private const val BOOT = "/data/adb/service.d/chen-guard.sh"
    private const val LATE = "/data/adb/late-load.d/chen-guard.sh"

    const val TRIGGER_UP = "volup"
    const val TRIGGER_DOWN = "voldown"
    const val TRIGGER_ANY = "any"
    const val TRIGGER_AUTO = "auto"

    private const val PREFS = "chen_hide"
    private const val KEY_TARGET = "guard_target"
    private const val KEY_TRIGGER = "guard_trigger"
    private const val KEY_HOLD = "guard_hold"
    private const val KEY_WAIT = "guard_wait"

    /** 长按判定时长（秒）：按住音量键这么久才算数。 */
    const val DEFAULT_HOLD = 3

    /** 「打开就触发」的等待时长（秒）：进入触发应用后等这么久再动手。 */
    const val DEFAULT_WAIT = 10

    data class Status(
        val running: Boolean = false,
        val installed: Boolean = true,
        val target: String = "",
        val trigger: String = TRIGGER_UP,
        val hold: Int = DEFAULT_HOLD,
        val waitSec: Int = DEFAULT_WAIT,
        val enabled: Boolean = true,
        val apkReady: Boolean = false,
        val backup: Boolean = false,
        val logTail: String = "",
        val output: String = "",
    )

    // ------------------------------------------------------------ 配置（本地记忆）

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(context: Context): String =
        prefs(context).getString(KEY_TARGET, "") ?: ""

    fun trigger(context: Context): String =
        prefs(context).getString(KEY_TRIGGER, TRIGGER_UP) ?: TRIGGER_UP

    fun hold(context: Context): Int =
        prefs(context).getInt(KEY_HOLD, DEFAULT_HOLD).coerceIn(1, 20)

    fun waitSec(context: Context): Int =
        prefs(context).getInt(KEY_WAIT, DEFAULT_WAIT).coerceIn(1, 120)

    fun setSelection(context: Context, target: String, trigger: String) {
        prefs(context).edit().putString(KEY_TARGET, target).putString(KEY_TRIGGER, trigger).apply()
    }

    fun setTiming(context: Context, hold: Int, waitSec: Int) {
        prefs(context).edit()
            .putInt(KEY_HOLD, hold.coerceIn(1, 20))
            .putInt(KEY_WAIT, waitSec.coerceIn(1, 120))
            .apply()
    }

    fun triggerLabel(context: Context, trigger: String): String = when (trigger) {
        TRIGGER_DOWN -> context.getString(me.weishu.kernelsu.R.string.hide_trigger_down)
        TRIGGER_ANY -> context.getString(me.weishu.kernelsu.R.string.hide_trigger_any)
        TRIGGER_AUTO -> context.getString(me.weishu.kernelsu.R.string.hide_trigger_auto)
        else -> context.getString(me.weishu.kernelsu.R.string.hide_trigger_up)
    }

    // ------------------------------------------------------------ root

    private fun run(script: String): String {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        runCatching {
            createRootShell().use { shell ->
                shell.newJob().add(script).to(out, err).exec()
            }
        }.onFailure { err.add(it.message ?: it.toString()) }
        return (out + err).joinToString("\n").trim()
    }

    private fun assetB64(context: Context, name: String): String =
        Base64.encodeToString(
            context.assets.open("hide/$name").use { it.readBytes() },
            Base64.NO_WRAP,
        )

    private fun writeAsset(context: Context, name: String, dest: String, mode: String): String =
        "printf %s '${assetB64(context, name)}' | base64 -d > '$dest' && chmod $mode '$dest'"

    // ------------------------------------------------------------ 查询状态

    fun status(context: Context): Status {
        val script = buildString {
            append("echo @@RUN\n")
            append("p=\$(cat $DIR/run/pid 2>/dev/null | tr -dc '0-9'); ")
            append("[ -n \"\$p\" ] && [ -d /proc/\$p ] && echo 1 || echo 0\n")
            append("echo @@INST\n")
            // 用「用户可见」判断，而不是 pm path：被 hide / disable-user 之后
            // pm path 照样有输出，但桌面和应用列表里早就没有了
            append("pm list packages -e --user 0 2>/dev/null | grep -qx 'package:${context.packageName}' && echo 1 || echo 0\n")
            append("echo @@APK\n")
            append("[ -f $DIR/chen.apk ] && echo 1 || echo 0\n")
            append("echo @@BAK\n")
            append("[ -d $DIR/data ] && echo 1 || echo 0\n")
            append("echo @@CONF\n")
            append("cat $CONF 2>/dev/null\n")
            append("echo @@LOG\n")
            append("tail -n 4 $DIR/guard.log 2>/dev/null\n")
        }
        val raw = run(script)
        val sections = HashMap<String, StringBuilder>()
        var current = ""
        raw.lineSequence().forEach { line ->
            if (line.startsWith("@@")) {
                current = line.removePrefix("@@")
                sections.getOrPut(current) { StringBuilder() }
            } else {
                sections.getOrPut(current) { StringBuilder() }.append(line).append('\n')
            }
        }
        val conf = sections["CONF"]?.toString().orEmpty()
        fun confValue(key: String): String =
            conf.lineSequence().firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')?.trim().orEmpty()

        return Status(
            running = sections["RUN"]?.toString()?.trim() == "1",
            installed = sections["INST"]?.toString()?.trim() != "0",
            apkReady = sections["APK"]?.toString()?.trim() == "1",
            backup = sections["BAK"]?.toString()?.trim() == "1",
            target = confValue("TARGET"),
            trigger = confValue("TRIGGER").ifEmpty { trigger(context) },
            hold = confValue("HOLD").toIntOrNull() ?: this.hold(context),
            waitSec = confValue("WAIT").toIntOrNull() ?: this.waitSec(context),
            enabled = confValue("ENABLED") != "0",
            logTail = sections["LOG"]?.toString()?.trim().orEmpty(),
            output = raw,
        )
    }

    // ------------------------------------------------------------ 布置守护

    fun arm(
        context: Context,
        target: String,
        trigger: String,
        holdSec: Int = this.hold(context),
        waitSec: Int = this.waitSec(context),
    ): String {
        if (target.isBlank()) return "请先选择触发应用"
        setSelection(context, target, trigger)
        setTiming(context, holdSec, waitSec)

        val pkg = context.packageName
        val main = "$pkg/.GateActivity"
        val sourceDir = context.applicationInfo.sourceDir
        val splits = context.applicationInfo.splitSourceDirs ?: emptyArray()

        val script = buildString {
            append("mkdir -p $DIR /data/adb/service.d /data/adb/late-load.d\n")
            append(writeAsset(context, "guard.sh", "$DIR/guard.sh", "755")).append('\n')
            append(writeAsset(context, "boot.sh", BOOT, "755")).append('\n')
            append(writeAsset(context, "boot.sh", LATE, "755")).append('\n')

            // 自身 APK 留一份，卸载后就靠它装回来（大小一致就不重复拷，省时间）
            append("if [ \"\$(stat -c %s '$sourceDir' 2>/dev/null)\" != \"\$(stat -c %s '$DIR/chen.apk' 2>/dev/null)\" ]; then\n")
            append("  cp -f '$sourceDir' '$DIR/chen.apk' && chmod 644 '$DIR/chen.apk'\n")
            append("fi\n")
            append("rm -f $DIR/split*.apk 2>/dev/null\n")
            splits.forEachIndexed { index, path ->
                append("cp -f '$path' '$DIR/split${index + 1}.apk' && chmod 644 '$DIR/split${index + 1}.apk'\n")
            }

            append(
                "printf '%s\\n' 'TARGET=$target' 'TRIGGER=$trigger' 'HOLD=${holdSec.coerceIn(1, 20)}'" +
                    " 'WAIT=${waitSec.coerceIn(1, 120)}' 'ENABLED=1' 'PKG=$pkg' 'MAIN=$main' > $CONF\n",
            )
            append("chmod 600 $CONF\n")
            append("rm -f $DIR/uninstall.req 2>/dev/null\n")
            append("sh $DIR/guard.sh start\n")
        }
        val out = run(script)
        val installed = run("[ -f $DIR/guard.sh ] && echo GUARD_OK; [ -f $DIR/chen.apk ] && echo APK_OK")
        return listOf(out, installed).filter { it.isNotBlank() }.joinToString("\n")
    }

    // ------------------------------------------------------------ 测试 / 卸载 / 停止

    fun testRestore(): String = run("sh $DIR/guard.sh once")

    /** 让守护备份数据后把 Chen 卸载掉（守护自己是 init 侧进程，不受影响）。 */
    fun requestUninstall(): String =
        run("touch $DIR/uninstall.req && echo OK")

    fun disarm(): String = run(
        buildString {
            append("sh $DIR/guard.sh stop\n")
            append("rm -f $BOOT $LATE 2>/dev/null\n")
            append("sed -i 's/^ENABLED=.*/ENABLED=0/' $CONF 2>/dev/null\n")
            append("echo DISARMED")
        },
    )

    /** 只隐藏桌面图标（不卸载）。 */
    fun setIconHidden(context: Context, hidden: Boolean): Boolean =
        HidePrefs.setIconHidden(context, hidden)

    // ------------------------------------------------------------ 应用列表

    data class AppEntry(val pkg: String, val label: String)

    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvers = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        return resolvers
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                AppEntry(
                    pkg = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun labelOf(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return runCatching {
            val info: ApplicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
            info.loadLabel(context.packageManager).toString()
        }.getOrDefault(pkg)
    }
}

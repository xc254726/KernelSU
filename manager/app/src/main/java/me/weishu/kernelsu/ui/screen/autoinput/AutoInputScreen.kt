package me.weishu.kernelsu.ui.screen.autoinput

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.file.RootFileEntry
import me.weishu.kernelsu.data.file.RootFileRepository
import me.weishu.kernelsu.data.shell.KsuCliRepository
import me.weishu.kernelsu.ui.component.PackageIcon
import me.weishu.kernelsu.ui.component.SwipeableSnackbarHost
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.settings.AppBackButton
import me.weishu.kernelsu.ui.component.settings.SegmentedColumn
import me.weishu.kernelsu.ui.component.settings.SettingsBaseWidget
import me.weishu.kernelsu.ui.component.settings.SettingsJumpPageWidget
import me.weishu.kernelsu.ui.component.settings.SettingsSwitchWidget
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.blurEffect
import me.weishu.kernelsu.ui.theme.blurSource
import me.weishu.kernelsu.ui.util.AppInfo
import me.weishu.kernelsu.ui.util.adaptiveScaffoldWindowInsets
import me.weishu.kernelsu.ui.util.showReplacingSnackbar

/**
 * 「自动输入」设置页。
 *
 * 后台逻辑来自 Auto Input 模块（已调好的前台监听 / 脚本喂入 / 屏幕注入），
 * 页面的职责只有三件：把脚本落到 /data/adb、读写 /data/adb/auto_input 配置、显示运行状态。
 *
 *  脚本常驻   : /data/adb/auto_input/bin 下的 daemon.sh 等脚本
 *  开机拉起   : /data/adb/service.d/chen-auto-input.sh
 *  配置文件   : /data/adb/auto_input/{pkg,input,script_path,delay,speed,min_interval,input_mode,keep_script,status}.txt
 *
 * 改动 assets 里的脚本时把 BUNDLED_VERSION 加一，进页面会自动覆盖到设备上。
 */
private const val CONF_DIR = "/data/adb/auto_input"
private const val BIN_DIR = "$CONF_DIR/bin"
private const val BOOT_SCRIPT = "/data/adb/service.d/chen-auto-input.sh"
// 越狱模式（Magica `ksud late-load`）只执行 late-load.d 里的脚本，不会执行 service.d，
// 所以启动脚本要双份安装；否则越狱重启后监听守护不会自己起来。
private const val LATE_LOAD_SCRIPT = "/data/adb/late-load.d/chen-auto-input.sh"
private const val BUNDLED_VERSION = "31"

// 调试卡片（实时前台 / 最近运行记录）默认隐藏；需要排查时置 true 恢复显示
private val SHOW_DEBUG_CARDS = false

// 后台脚本原样取自 auto-input.zip 原模块，只有触发链路做了三处「防卡死」修补（见 B24 说明）：
//   1) get_foreground_pkg 先问 dumpsys window，失败才退回 activity 全量，并套 timeout + 2s 缓存
//   2) 轮询间隔 1s -> 2s
//   3) 每条间隔下限 1s；input / keyevent 套 timeout 10
// 其余逻辑（触发判定、冷却、脚本喂入、保持运行）与原模块保持一致。
private val SCRIPT_NAMES = listOf(
    "common.sh",
    "daemon.sh",
    "supervisor.sh",
    "keeper.sh",
    "ensure.sh",
    "restart.sh",
    "status.sh",
    "diag.sh",
    "test_input.sh",
    "applist.sh",
)

// 状态轮询脚本：每几秒跑一次，必须**绝对轻量**。
//
// 这里曾经包含 get_foreground_pkg（dumpsys activity activities）和
// dumpsys input_method —— 那两条命令在部分 ROM 上单次就要好几秒、输出几 MB。
// 它们原本只是喂「实时前台 / 最近运行记录」两张调试卡片（现已隐藏），却仍在
// 每轮执行：结果是把 root shell 的队列彻底堵死，之后连 `id -u` 都排不上，
// 整个 app 表现为「进一次监听页就丢 root」（首页顶红条、监听页显示需要 root）。
// 所以：只保留读配置文件 + status.sh，任何 dumpsys 都不许进这里。
private const val STATUS_SCRIPT = """
CONF=/data/adb/auto_input
echo UID=${'$'}(id -u)
[ -d ${'$'}CONF/bin ] && echo DIR=1 || echo DIR=0
{ [ -f /data/adb/service.d/chen-auto-input.sh ] || [ -f /data/adb/late-load.d/chen-auto-input.sh ]; } && echo BOOT=1 || echo BOOT=0
[ -f ${'$'}CONF/DISABLED ] && echo DIS=1 || echo DIS=0
timeout 5 sh ${'$'}CONF/bin/status.sh 2>/dev/null
"""

private const val READ_CONFIG_SCRIPT = """
CONF=/data/adb/auto_input
echo @@PKG
cat ${'$'}CONF/pkg.txt 2>/dev/null
echo
echo @@IN
cat ${'$'}CONF/input.txt 2>/dev/null
echo
echo @@SP
cat ${'$'}CONF/script_path.txt 2>/dev/null
echo
echo @@SHOW
echo "delay=${'$'}(cat ${'$'}CONF/delay.txt 2>/dev/null)"
echo "speed=${'$'}(cat ${'$'}CONF/speed.txt 2>/dev/null)"
echo "interval=${'$'}(cat ${'$'}CONF/min_interval.txt 2>/dev/null)"
echo "mode=${'$'}(cat ${'$'}CONF/input_mode.txt 2>/dev/null)"
echo "keep=${'$'}(cat ${'$'}CONF/keep_script.txt 2>/dev/null)"
"""

/** 原模块的 test_input.sh：script 模式跑脚本并喂预输入，screen 模式模拟屏幕输入 */
private const val TEST_INPUT_SCRIPT = """
if command -v setsid >/dev/null 2>&1; then
    setsid sh /data/adb/auto_input/bin/test_input.sh </dev/null >/dev/null 2>&1 &
else
    nohup sh /data/adb/auto_input/bin/test_input.sh </dev/null >/dev/null 2>&1 &
fi
echo "STARTED"
"""

private const val REMOVE_SCRIPT = """
CONF=/data/adb/auto_input
for p in ${'$'}(pgrep -f 'auto_input/bin/[s]upervisor\.sh' 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
for p in ${'$'}(pgrep -f 'auto_input/bin/[k]eeper\.sh' 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
for p in ${'$'}(pgrep -f 'auto_input/bin/[d]aemon\.sh' 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
hp=${'$'}(cat ${'$'}CONF/.script_hold_pid 2>/dev/null)
[ -n "${'$'}hp" ] && kill -9 ${'$'}hp 2>/dev/null
cp=${'$'}(cat ${'$'}CONF/.script_child 2>/dev/null)
[ -n "${'$'}cp" ] && kill -9 ${'$'}cp 2>/dev/null
rm -f /data/adb/service.d/chen-auto-input.sh
rm -f /data/adb/late-load.d/chen-auto-input.sh
rm -rf ${'$'}CONF
echo REMOVED
"""

private data class AutoInputStatus(
    // 有没有 root：没有 root 时所有落盘/执行都会静默失败，必须单独区分出来告诉用户，
    // 否则只会看到「未安装」还点不动（用户会以为是安装包缺东西）
    val hasRoot: Boolean = true,
    val installed: Boolean = false,
    val bootRegistered: Boolean = false,
    val enabled: Boolean = true,
    val daemonRunning: Boolean = false,
    val supervisorRunning: Boolean = false,
    // keeper = 开机侧常驻看护（父进程在 init 侧，与 chen 无关）。
    // 它在，daemon 被划掉/被杀都会被 10 秒内补回来；它不在，就只能靠 app 自己补。
    val keeperRunning: Boolean = false,
    // 安全熔断：boot.sh 发现上次开机动不动就重启（疑似卡死），会写 DISABLED 文件停掉自启。
    // 有这个标记时开关虽然还在「开」，但守护进程不会起来，必须让用户看懂原因。
    val breaker: Boolean = false,
    val foregroundPkg: String = "",
    val inputFocused: Boolean = false,
)

private data class AutoInputConfig(
    val pkgs: List<String> = emptyList(),
    val input: String = "",
    val scriptPath: String = "",
    val delaySec: Int = 5,
    val speedSec: Int = 3,
    val minInterval: Int = 8,
    val mode: String = "screen",
    val keepScript: Boolean = true,
)

private enum class NumberKind { Delay, Speed, Interval }

private data class PmApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val launcher: Boolean = false,
)

private fun loadPmApps(context: Context): List<PmApp> {
    val pm = context.packageManager
    // 有桌面入口的包（launcher）优先展示，避免系统服务把列表淹没
    val launcherPkgs = runCatching {
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    return pm.getInstalledPackages(0)
        .mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            PmApp(
                pkg = info.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }
                    .getOrDefault(info.packageName),
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                launcher = info.packageName in launcherPkgs,
            )
        }
        .filter { it.pkg != "android" }
        // 桌面应用 + 用户自己装的应用；纯系统组件不列（基本不会去监听它们）
        .filter { it.launcher || !it.system }
        .sortedWith(compareByDescending<PmApp> { it.launcher }.thenBy { it.label.lowercase() })
}

private fun runRoot(ksuCli: KsuCliRepository, script: String): String {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    runCatching { ksuCli.getRootShell().newJob().add(script).to(out, err).exec() }
    return out.joinToString("\n")
}

/**
 * 一次性 root 命令：每次新建独立 shell，用完立刻关闭。
 *
 * 页面里**周期性**的探测必须走这里，不能复用 KsuCli.SHELL：一旦某条命令卡住
 * （历史教训：dumpsys），共享 shell 的命令队列会越排越长，之后连 `id -u` 都
 * 排不上，表现就是「进一次监听页，root 就没了」。独立 shell 之间互不影响，
 * 单次卡住只影响那一次探测。
 */
private fun runRootIsolated(ksuCli: KsuCliRepository, script: String): String {
    val out = ArrayList<String>()
    val err = ArrayList<String>()
    runCatching {
        ksuCli.newRootShell().use { sh ->
            sh.newJob().add(script).to(out, err).exec()
        }
    }
    return out.joinToString("\n")
}

private fun assetBase64(context: Context, name: String): String =
    Base64.encodeToString(
        context.assets.open("autoinput/$name").use { it.readBytes() },
        Base64.NO_WRAP,
    )

private fun writeFile(ksuCli: KsuCliRepository, path: String, content: String): String {
    // 原版 WebUI 写文件时总是补一个尾换行（is_target 用 while read 读 pkg.txt，
    // 末行缺换行符会被直接丢掉——选 1 个应用时就永远匹配不上）。这里对齐原版行为。
    val body = if (content.endsWith("\n")) content else content + "\n"
    val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
    return runRoot(ksuCli, "mkdir -p ${CONF_DIR}\nprintf %s '$b64' | base64 -d > '$path'\necho OK")
}

private fun installScripts(context: Context, ksuCli: KsuCliRepository): String {
    val script = buildString {
        append("mkdir -p $BIN_DIR /data/adb/service.d /data/adb/late-load.d\n")
        SCRIPT_NAMES.forEach { name ->
            append("printf %s '").append(assetBase64(context, "bin/$name"))
                .append("' | base64 -d > $BIN_DIR/$name\n")
            append("chmod 755 $BIN_DIR/$name\n")
        }
        // 清掉旧版本遗留的脚本（现已完全采用原模块，这几个是以前自加的）
        append("rm -f $BIN_DIR/flow_test.sh $BIN_DIR/probe.sh $BIN_DIR/script_probe.sh\n")
        // 开机入口装两份：service.d（正常 KSU/Magisk 开机）+ late-load.d（越狱模式）
        val bootB64 = assetBase64(context, "boot.sh")
        append("printf %s '").append(bootB64)
            .append("' | base64 -d > $BOOT_SCRIPT\n")
        append("chmod 755 $BOOT_SCRIPT\n")
        append("printf %s '").append(bootB64)
            .append("' | base64 -d > $LATE_LOAD_SCRIPT\n")
        append("chmod 755 $LATE_LOAD_SCRIPT\n")
        append("echo '$BUNDLED_VERSION' > $CONF_DIR/.bundled_version\n")
        // 旧版本会在判定卡死时自动写 DISABLED 熔断，一旦命中监听就永远不自启。
        // 升级时把这个历史标记清掉（用户自己写的 DISABLED 内容不同，会保留）。
        // 用 grep -E：toybox grep 对 BRE 的 \| 支持不稳定，不能依赖
        append("if [ -f $CONF_DIR/DISABLED ] && grep -qE '安全熔断|负载过高|监听把系统拖' $CONF_DIR/DISABLED 2>/dev/null; then rm -f $CONF_DIR/DISABLED; fi\n")
        // 配置只补齐缺失项，不覆盖用户已有设置
        append("[ -f $CONF_DIR/pkg.txt ] || : > $CONF_DIR/pkg.txt\n")
        append("[ -f $CONF_DIR/input.txt ] || : > $CONF_DIR/input.txt\n")
        append("[ -f $CONF_DIR/script_path.txt ] || : > $CONF_DIR/script_path.txt\n")
        append("[ -f $CONF_DIR/delay.txt ] || echo 5 > $CONF_DIR/delay.txt\n")
        append("[ -f $CONF_DIR/speed.txt ] || echo 3 > $CONF_DIR/speed.txt\n")
        append("[ -f $CONF_DIR/min_interval.txt ] || echo 8 > $CONF_DIR/min_interval.txt\n")
        append("[ -f $CONF_DIR/input_mode.txt ] || echo script > $CONF_DIR/input_mode.txt\n")
        // 老版本默认是屏幕输入：统一迁到脚本喂入
        append("if grep -qi '^screen' $CONF_DIR/input_mode.txt 2>/dev/null; then echo script > $CONF_DIR/input_mode.txt; fi\n")
        append("[ -f $CONF_DIR/keep_script.txt ] || echo 1 > $CONF_DIR/keep_script.txt\n")
        append("[ -f $CONF_DIR/order.txt ] || echo script_first > $CONF_DIR/order.txt\n")
        append("[ -f $CONF_DIR/status ] || echo 1 > $CONF_DIR/status\n")
        append("chmod 755 $CONF_DIR 2>/dev/null\n")
        // 旧版 UI 写入的 pkg.txt 末尾可能缺换行：is_target 用 while read 会丢掉最后一行，
        // 表现为「选了应用却永远匹配不上、target=no」。这里统一补上尾换行。
        append("[ -s $CONF_DIR/pkg.txt ] && [ -n \"\$(tail -c1 $CONF_DIR/pkg.txt 2>/dev/null)\" ] && echo >> $CONF_DIR/pkg.txt\n")
        // 间隔 0 = 每 0.2 秒发一条 input（每条都会拉起一个 JVM），实测会把整机拖死。
        // 迁移历史配置：0 或空一律改回 3 秒。
        append("[ \"\$(cat $CONF_DIR/speed.txt 2>/dev/null | tr -dc '0-9')\" = \"0\" ] && echo 3 > $CONF_DIR/speed.txt\n")
        append("echo INSTALLED\n")
    }
    val result = runRoot(ksuCli, script)
    Log.i("ChenAutoInput", "install: " + result.replace("\n", " | "))
    return result
}

private fun parseStatus(raw: String): AutoInputStatus = AutoInputStatus(
    // 脚本里 echo UID=$(id -u)：只有真拿到 root shell 才是 0。
    // 没有 root 时 libsu 会静默回落到普通 sh，此时所有命令都 Permission denied，
    // 不能只看 DIR=0，否则用户只会看到「未安装」而不知道是缺 root。
    hasRoot = raw.lineSequence().any { it.trim() == "UID=0" },
    installed = raw.contains("DIR=1"),
    bootRegistered = raw.contains("BOOT=1"),
    enabled = raw.lineSequence().firstOrNull { it.startsWith("status=") }
        ?.removePrefix("status=")?.trim() != "0",
    daemonRunning = raw.lineSequence().firstOrNull { it.startsWith("daemon=") }
        ?.removePrefix("daemon=")?.trim() == "1",
    supervisorRunning = raw.lineSequence().firstOrNull { it.startsWith("supervisor=") }
        ?.removePrefix("supervisor=")?.trim() == "1",
    keeperRunning = raw.lineSequence().firstOrNull { it.startsWith("keeper=") }
        ?.removePrefix("keeper=")?.trim() == "1",
    breaker = raw.contains("DIS=1"),
    foregroundPkg = raw.lineSequence().firstOrNull { it.startsWith("FG=") }
        ?.removePrefix("FG=")?.trim().orEmpty(),
    inputFocused = raw.contains("FOCUS=1"),
)

private fun parseConfig(raw: String): AutoInputConfig {
    val sections = LinkedHashMap<String, StringBuilder>()
    var current = "head"
    sections[current] = StringBuilder()
    raw.lineSequence().forEach { line ->
        if (line.startsWith("@@")) {
            current = line.removePrefix("@@").trim()
            sections[current] = StringBuilder()
        } else {
            sections.getOrPut(current) { StringBuilder() }.append(line).append('\n')
        }
    }
    fun text(key: String): String = sections[key]?.toString()?.trimEnd('\n') ?: ""
    fun num(key: String, fallback: Int): Int =
        sections["SHOW"]?.toString()?.lineSequence()
            ?.firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.trim()?.toIntOrNull() ?: fallback
    fun str(key: String): String =
        sections["SHOW"]?.toString()?.lineSequence()
            ?.firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")?.trim()?.takeIf { it.isNotEmpty() } ?: ""

    val scriptPath = text("SP").trim()
    val rawMode = str("mode").ifEmpty { "screen" }

    return AutoInputConfig(
        pkgs = text("PKG").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
        input = text("IN"),
        scriptPath = scriptPath,
        delaySec = num("delay", 5),
        // 间隔下限 1 秒：0 会让 daemon 每 0.2 秒发一条 input（每条拉起一个 JVM），
        // 10 次/秒的量级足以让系统卡死重启，这里强制夹住。
        speedSec = num("speed", 3).coerceAtLeast(1),
        minInterval = num("interval", 8),
        mode = rawMode,
        keepScript = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AutoInputScreen() {
    val ksuCli = remember { KsuCliRepository() }
    val rootFiles = remember(ksuCli) { RootFileRepository(ksuCli) }
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackBarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val confirmDialog = rememberConfirmDialog()

    var status by remember { mutableStateOf(AutoInputStatus()) }
    var config by remember { mutableStateOf(AutoInputConfig()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pmApps by remember { mutableStateOf<List<PmApp>>(emptyList()) }

    var showAppPicker by remember { mutableStateOf(false) }
    var showInputEditor by remember { mutableStateOf(false) }
    var showScriptEditor by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var numberKind by remember { mutableStateOf<NumberKind?>(null) }
    var logText by remember { mutableStateOf("") }
    var lastRun by remember { mutableStateOf("") }
    var diagText by remember { mutableStateOf("") }
    // keeper 补拉的节流时间戳（毫秒）
    var lastKeeperFix by remember { mutableStateOf(0L) }

    val msgSaved = stringResource(R.string.autoinput_saved)
    val msgStarted = stringResource(R.string.autoinput_started)
    val msgStopped = stringResource(R.string.autoinput_stopped)
    val msgRemoved = stringResource(R.string.autoinput_removed)
    val msgTested = stringResource(R.string.autoinput_test_done)
    val msgRunNow = stringResource(R.string.autoinput_run_now_done)
    val msgScriptStarted = stringResource(R.string.autoinput_script_started)
    val msgScriptNoPath = stringResource(R.string.autoinput_script_no_path)
    val msgScriptNotFound = stringResource(R.string.autoinput_script_not_found)
    val msgNoRoot = stringResource(R.string.autoinput_need_root_toast)

    suspend fun refresh(readConfig: Boolean = true): AutoInputStatus {
        // 状态探测走独立 shell：这条路径每几秒一次，绝不能和写配置 / 重启守护
        // 共用同一条 shell —— 一旦某次命令卡住，队列会越排越长，最终连 id -u
        // 都排不上，整个 app 就表现为「拿不到 root」。
        val rawStatus = withContext(Dispatchers.IO) { runRootIsolated(ksuCli, STATUS_SCRIPT) }
        val st = parseStatus(rawStatus)
        status = st
        // 配置只在首次进入 / 保存后读；轮询时跳过，少一次 root 往返
        if (readConfig) {
            config = parseConfig(
                withContext(Dispatchers.IO) { runRootIsolated(ksuCli, READ_CONFIG_SCRIPT) },
            )
        }
        // 最近运行记录调试卡片默认隐藏，隐藏时不再每轮拉日志
        lastRun = if (SHOW_DEBUG_CARDS) {
            withContext(Dispatchers.IO) {
                runRoot(ksuCli, "tail -n 8 $CONF_DIR/daemon.log 2>/dev/null")
            }.trim()
        } else {
            ""
        }
        loaded = true
        return st
    }

    // keeper（开机侧常驻看护）不在就补拉一次。
    //
    // 只补 keeper，绝不顺手重起 daemon —— 那正是老版本「一进监听页就把监听打断、
    // 状态一直转圈」的原因。典型场景是刚装完新包还没重启手机：那时 keeper 只能先
    // 用 app 的 root shell 拉起来，重启一次之后就由开机脚本从 init 侧拉起，
    // 从此与 chen 彻底无关（划掉后台也不会被带走）。
    suspend fun ensureKeeper(st: AutoInputStatus) {
        if (!st.installed || !st.enabled || st.breaker || st.keeperRunning) return
        val now = System.currentTimeMillis()
        if (now - lastKeeperFix < 60_000) return
        lastKeeperFix = now
        withContext(Dispatchers.IO) {
            runRoot(ksuCli, "sh $BIN_DIR/ensure.sh keeper >/dev/null 2>&1")
        }
    }

    suspend fun persist(next: AutoInputConfig) {        busy = true
        withContext(Dispatchers.IO) {
            writeFile(ksuCli, "$CONF_DIR/pkg.txt", next.pkgs.joinToString("\n"))
            writeFile(ksuCli, "$CONF_DIR/input.txt", next.input)
            writeFile(ksuCli, "$CONF_DIR/script_path.txt", next.scriptPath)
            writeFile(ksuCli, "$CONF_DIR/delay.txt", next.delaySec.toString())
            writeFile(ksuCli, "$CONF_DIR/speed.txt", next.speedSec.coerceAtLeast(1).toString())
            writeFile(ksuCli, "$CONF_DIR/min_interval.txt", next.minInterval.toString())
            // 输入方式只保留「脚本喂入」（屏幕输入对游戏/加固应用没意义，还容易把系统拖死）
            writeFile(ksuCli, "$CONF_DIR/input_mode.txt", "script")
            writeFile(ksuCli, "$CONF_DIR/keep_script.txt", "1")
            writeFile(ksuCli, "$CONF_DIR/order.txt", "script_first")
        }
        config = next
        busy = false
        snackBarHost.showReplacingSnackbar(msgSaved)
    }

    LaunchedEffect(Unit) {
        // 先探一次 root：状态脚本里 echo 了 id -u，不是 UID=0 就说明没拿到 root shell。
        // 没有 root 时写 /data/adb 一律 Permission denied，先不跑安装流程，只显示「需要 root」，
        // 并每 5 秒重探一次——用户去 KernelSU/Magisk 里点了授权后会自动接上，不用退出重进。
        var probe = withContext(Dispatchers.IO) { runRootIsolated(ksuCli, STATUS_SCRIPT) }
        fun probeHasRoot() = probe.lineSequence().any { it.trim() == "UID=0" }
        if (!probeHasRoot()) {
            pmApps = withContext(Dispatchers.IO) { loadPmApps(context) }
            status = AutoInputStatus(hasRoot = false, enabled = false)
            loaded = true
            while (!probeHasRoot()) {
                delay(5000)
                probe = withContext(Dispatchers.IO) { runRootIsolated(ksuCli, STATUS_SCRIPT) }
            }
        }
        val version = withContext(Dispatchers.IO) {
            runRoot(ksuCli, "cat $CONF_DIR/.bundled_version 2>/dev/null")
        }.trim()
        val bootOk = withContext(Dispatchers.IO) { runRootIsolated(ksuCli, STATUS_SCRIPT) }.contains("BOOT=1")
        var upgraded = false
        if (version != BUNDLED_VERSION || !bootOk) {
            busy = true
            withContext(Dispatchers.IO) { installScripts(context, ksuCli) }
            busy = false
            upgraded = true
        }
        refresh()
        ensureKeeper(status)
        pmApps = withContext(Dispatchers.IO) { loadPmApps(context) }
        // 只有刚覆盖过脚本时才需要让新逻辑生效。进程一律交给开机侧 keeper 管：
        // app 只发「按新配置重启」的请求，绝不自己拉起 daemon/看护
        //（否则它们挂在 chen 的进程树上，划掉后台就被一起清掉）
        if (upgraded) {
            // 脚本文件已覆盖，但 keeper / 看护 / daemon 是常驻进程，跑的还是内存里的旧代码。
            // 必须把它们全部结束、交给 keeper 重拉，新逻辑（不再自动熔断、cgroup 自检）才会生效。
            withContext(Dispatchers.IO) {
                runRoot(
                    ksuCli,
                    "for f in keeper supervisor daemon; do " +
                        "p=\$(cat $CONF_DIR/run/\$f.pid 2>/dev/null); " +
                        "[ -n \"\$p\" ] && kill -9 \$p 2>/dev/null; " +
                        "rm -f $CONF_DIR/run/\$f.pid; done; " +
                        "sh $BIN_DIR/ensure.sh >/dev/null 2>&1",
                )
            }
            delay(1200)
            refresh()
        }
        // 只读状态，不在这里拉起任何进程：自愈由开机侧 keeper 每 10 秒负责，
        // 页面停留、划掉后台都不影响监听存活
        while (true) {
            // 轮询只读状态不读整份配置：少一次 root 往返，也避免频繁 IO
            delay(6000)
            if (busy) continue
            ensureKeeper(refresh(readConfig = false))
        }
    }

    val statusText = when {
        !loaded -> stringResource(R.string.autoinput_status_loading)
        busy -> stringResource(R.string.autoinput_working)
        !status.hasRoot -> stringResource(R.string.autoinput_status_no_root)
        !status.installed -> stringResource(R.string.autoinput_status_not_installed)
        status.breaker -> stringResource(R.string.autoinput_status_breaker)
        !status.enabled -> stringResource(R.string.autoinput_status_off)
        status.daemonRunning -> stringResource(R.string.autoinput_status_running)
        // keeper 不在 = 没人负责补拉 daemon，这不是「启动中」，要如实说并自动修
        !status.keeperRunning -> stringResource(R.string.autoinput_status_recovering)
        else -> stringResource(R.string.autoinput_status_idle)
    }

    val labelOf: (String) -> String = { pkg ->
        pmApps.firstOrNull { it.pkg == pkg }?.label?.takeIf { it.isNotBlank() } ?: pkg
    }

    val inputLineCount = config.input.lineSequence().count { it.isNotBlank() }

    val toggle: (Boolean) -> Unit = { on ->
        scope.launch {
            // 兜底：没有 root 时给一句明确提示，而不是让它静默失败后仍是「未安装」
            if (!status.hasRoot) {
                snackBarHost.showReplacingSnackbar(msgNoRoot)
                return@launch
            }
            busy = true
            withContext(Dispatchers.IO) {
                if (on) {
                    if (!status.installed) installScripts(context, ksuCli)
                    // 用户主动打开开关 = 明确的恢复意图，先解除安全熔断
                    runRoot(ksuCli, "rm -f ${CONF_DIR}/DISABLED")
                    writeFile(ksuCli, "${CONF_DIR}/status", "1")
                    // 只发请求：真正拉起进程的是开机侧 keeper
                    runRoot(ksuCli, "sh ${BIN_DIR}/ensure.sh >/dev/null 2>&1")
                } else {
                    writeFile(ksuCli, "${CONF_DIR}/status", "0")
                    runRoot(ksuCli, "sh ${BIN_DIR}/ensure.sh stop >/dev/null 2>&1")
                }
            }
            delay(if (on) 800 else 400)
            refresh()
            busy = false
            snackBarHost.showReplacingSnackbar(if (on) msgStarted else msgStopped)
        }
    }

    // 立即执行一次：重启守护进程，新进程没有"已触发"记忆，看到目标在前台会马上跑一遍
    val runNow: () -> Unit = {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) { runRoot(ksuCli, "sh ${BIN_DIR}/ensure.sh >/dev/null 2>&1") }
            delay(2500)
            busy = false
            refresh()
            snackBarHost.showReplacingSnackbar(msgRunNow)
        }
    }

    // 不依赖监听触发，把「脚本 + 喂入/屏幕输入 + 回车」整条链路跑一遍
    val runScriptNow: () -> Unit = {
        scope.launch {
            val msg = when {
                config.mode != "screen" && config.scriptPath.isBlank() -> msgScriptNoPath
                config.scriptPath.isNotBlank() && withContext(Dispatchers.IO) {
                    runRoot(ksuCli, "test -f '${config.scriptPath}' && echo YES || echo NO")
                }.contains("NO") -> msgScriptNotFound
                else -> {
                    withContext(Dispatchers.IO) { runRoot(ksuCli, TEST_INPUT_SCRIPT) }
                    msgScriptStarted
                }
            }
            snackBarHost.showReplacingSnackbar(msg)
            delay(2000)
            refresh()
        }
    }

    ScaffoldLike(
        title = stringResource(R.string.autoinput_title),
        scrollBehavior = scrollBehavior,
        snackBarHost = snackBarHost,
        onBack = { navigator.pop() },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .blurSource(),
        ) {
            item {
                AutoInputHero(
                    title = stringResource(R.string.autoinput_title),
                    statusText = statusText,
                    loaded = loaded,
                    // 熔断时守护进程本来就没在跑，开关显示为关才不误导；
                    // 用户点一下（打开）就会清掉 DISABLED 并重启监听。
                    checked = status.installed && status.enabled && !status.breaker,
                    running = status.installed && status.enabled && !status.breaker && status.daemonRunning,
                    enabled = loaded && !busy && status.hasRoot,
                    onCheckedChange = toggle,
                )
            }

            // 没有 root：开关点不动，直接把原因和解决办法摆在页面上，
            // 免得用户以为「点了没反应」是安装包有问题
            if (loaded && !status.hasRoot) item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.autoinput_no_root_hint),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (SHOW_DEBUG_CARDS) item {
                val fg = status.foregroundPkg
                val fgIsTarget = fg.isNotEmpty() && config.pkgs.contains(fg)
                val dotColor = when {
                    !loaded -> MaterialTheme.colorScheme.outline
                    !status.installed || !status.enabled -> MaterialTheme.colorScheme.outline
                    fgIsTarget -> Color(0xFF7CE3AE)
                    else -> Color(0xFFFFC46B)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.autoinput_live_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = runNow,
                                enabled = loaded && !busy && status.installed,
                            ) {
                                Text(stringResource(R.string.autoinput_run_now))
                            }
                        }
                        Text(
                            text = fg.ifEmpty { stringResource(R.string.autoinput_live_unknown) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                !status.installed -> stringResource(R.string.autoinput_live_not_installed)
                                config.pkgs.isEmpty() -> stringResource(R.string.autoinput_live_no_app)
                                fg.isEmpty() -> stringResource(R.string.autoinput_live_unknown_hint)
                                fgIsTarget && status.inputFocused ->
                                    stringResource(R.string.autoinput_live_target_focused)
                                fgIsTarget -> stringResource(R.string.autoinput_live_target_nofocus)
                                else -> stringResource(R.string.autoinput_live_not_target)
                            },
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (SHOW_DEBUG_CARDS && lastRun.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { showLog = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.autoinput_last_run),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = lastRun,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.autoinput_apps_group)) {
                    if (config.pkgs.isEmpty()) {
                        item {
                            SettingsBaseWidget(
                                icon = null,
                                iconPlaceholder = false,
                                title = stringResource(R.string.autoinput_apps_empty),
                                description = stringResource(R.string.autoinput_apps_hint),
                            )
                        }
                    }
                    config.pkgs.forEach { pkg ->
                        item(key = "pkg_$pkg") {
                            SettingsBaseWidget(
                                leadingContent = {
                                    PackageIcon(
                                        packageName = pkg,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                },
                                title = labelOf(pkg),
                                description = pkg,
                                foreContent = {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                persist(config.copy(pkgs = config.pkgs - pkg))
                                            }
                                        },
                                    ) {
                                        Icon(Icons.TwoTone.Delete, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Add,
                            title = stringResource(R.string.autoinput_apps_add),
                            description = stringResource(R.string.autoinput_apps_add_summary),
                            onClick = { showAppPicker = true },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.autoinput_input_group)) {
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.Filled.Description,
                            title = stringResource(R.string.autoinput_input_title),
                            description = if (inputLineCount == 0) {
                                stringResource(R.string.autoinput_input_empty)
                            } else {
                                stringResource(R.string.autoinput_input_summary, inputLineCount)
                            },
                            onClick = { showInputEditor = true },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_script_title),
                            description = config.scriptPath.ifBlank {
                                stringResource(R.string.autoinput_script_summary_empty)
                            },
                            onClick = { showScriptEditor = true },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.Filled.PlayArrow,
                            title = stringResource(R.string.autoinput_run_script),
                            description = stringResource(R.string.autoinput_run_script_summary),
                            onClick = { runScriptNow() },
                        )
                    }
                    if (config.mode != "screen") {
                        item {
                            SettingsBaseWidget(
                                icon = null,
                                iconPlaceholder = false,
                                title = stringResource(R.string.autoinput_mode_script_tip_title),
                                description = stringResource(R.string.autoinput_mode_script_tip_desc),
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.autoinput_params_group)) {
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_delay_title),
                            description = stringResource(R.string.autoinput_seconds, config.delaySec),
                            onClick = { numberKind = NumberKind.Delay },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_speed_title),
                            description = stringResource(R.string.autoinput_seconds, config.speedSec),
                            onClick = { numberKind = NumberKind.Speed },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_interval_title),
                            description = stringResource(R.string.autoinput_seconds, config.minInterval),
                            onClick = { numberKind = NumberKind.Interval },
                        )
                    }
                }
            }

            item {
                SegmentedColumn(title = stringResource(R.string.autoinput_log_group)) {
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_log_title),
                            description = stringResource(R.string.autoinput_log_summary),
                            onClick = {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) {
                                        val cfg = runRoot(
                                            ksuCli,
                                            "echo '--- pkg.txt ---'; cat $CONF_DIR/pkg.txt 2>/dev/null; " +
                                                "echo '--- input_mode ---'; cat $CONF_DIR/input_mode.txt 2>/dev/null; " +
                                                "echo '--- speed ---'; cat $CONF_DIR/speed.txt 2>/dev/null; " +
                                                "echo '--- status ---'; cat $CONF_DIR/status 2>/dev/null; " +
                                                "echo '--- DISABLED ---'; cat $CONF_DIR/DISABLED 2>/dev/null",
                                        )
                                        val a = runRoot(
                                            ksuCli,
                                            "tail -n 160 $CONF_DIR/daemon.log 2>/dev/null",
                                        )
                                        val b = runRoot(
                                            ksuCli,
                                            "tail -n 80 $CONF_DIR/script.log 2>/dev/null",
                                        )
                                        buildString {
                                            append("===== 配置 =====\n").append(cfg)
                                            append("\n===== daemon.log =====\n").append(a)
                                            if (b.isNotBlank()) {
                                                append("\n\n===== script.log =====\n").append(b)
                                            }
                                        }
                                    }
                                    logText = text.ifBlank { context.getString(R.string.autoinput_log_empty) }
                                    showLog = true
                                }
                            },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_test_title),
                            description = stringResource(R.string.autoinput_test_summary),
                            enabled = status.installed,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    withContext(Dispatchers.IO) {
                                        runRoot(ksuCli, "sh $BIN_DIR/test_input.sh >/dev/null 2>&1 &")
                                    }
                                    delay(400)
                                    busy = false
                                    snackBarHost.showReplacingSnackbar(msgTested)
                                }
                            },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.Filled.PlayArrow,
                            title = stringResource(R.string.autoinput_diag_title),
                            description = stringResource(R.string.autoinput_diag_summary),
                            onClick = {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) {
                                        runRoot(ksuCli, "sh $BIN_DIR/diag.sh 2>&1")
                                    }
                                    diagText = text.ifBlank { "no output" }
                                    showDiag = true
                                }
                            },
                        )
                    }
                    item {
                        SettingsBaseWidget(
                            icon = Icons.Filled.Delete,
                            title = stringResource(R.string.autoinput_remove_title),
                            description = stringResource(R.string.autoinput_remove_summary),
                            isError = true,
                            onClick = {
                                scope.launch {
                                    val result = confirmDialog.awaitConfirm(
                                        title = context.getString(R.string.autoinput_remove_title),
                                        content = context.getString(R.string.autoinput_remove_confirm),
                                    )
                                    if (result == ConfirmResult.Confirmed) {
                                        busy = true
                                        withContext(Dispatchers.IO) { runRoot(ksuCli, REMOVE_SCRIPT) }
                                        delay(300)
                                        refresh()
                                        busy = false
                                        snackBarHost.showReplacingSnackbar(msgRemoved)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showAppPicker) {
        AppMultiPickerDialog(
            apps = pmApps.filter { !it.system },
            selected = config.pkgs.toSet(),
            onDismiss = { showAppPicker = false },
            onConfirm = { picked ->
                showAppPicker = false
                scope.launch { persist(config.copy(pkgs = picked.toList())) }
            },
        )
    }

    if (showInputEditor) {
        TextEditorDialog(
            title = stringResource(R.string.autoinput_input_title),
            hint = stringResource(R.string.autoinput_input_hint),
            initial = config.input,
            minLines = 6,
            onDismiss = { showInputEditor = false },
            onConfirm = { text ->
                showInputEditor = false
                scope.launch { persist(config.copy(input = text)) }
            },
            onTest = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // 先把待测内容写进 input.txt，再走原模块的 test_input.sh
                        writeFile(ksuCli, "$CONF_DIR/input.txt", text)
                        runRoot(ksuCli, "sh $BIN_DIR/test_input.sh >/dev/null 2>&1 &")
                    }
                    config = config.copy(input = text)
                    snackBarHost.showReplacingSnackbar(msgTested)
                }
            },
        )
    }

    if (showScriptEditor) {
        ScriptPathDialog(
            initial = config.scriptPath,
            rootFiles = rootFiles,
            onDismiss = { showScriptEditor = false },
            onConfirm = { path ->
                showScriptEditor = false
                scope.launch { persist(config.copy(scriptPath = path)) }
            },
        )
    }

    numberKind?.let { kind ->
        val (title, min, max, value) = when (kind) {
            NumberKind.Delay -> Quad(
                stringResource(R.string.autoinput_delay_title), 0, 60, config.delaySec,
            )

            NumberKind.Speed -> Quad(
                stringResource(R.string.autoinput_speed_title), 1, 60, config.speedSec,
            )

            NumberKind.Interval -> Quad(
                stringResource(R.string.autoinput_interval_title), 0, 300, config.minInterval,
            )
        }
        SliderDialog(
            title = title,
            min = min,
            max = max,
            initial = value,
            onDismiss = { numberKind = null },
            onConfirm = { v ->
                numberKind = null
                scope.launch {
                    persist(
                        when (kind) {
                            NumberKind.Delay -> config.copy(delaySec = v)
                            NumberKind.Speed -> config.copy(speedSec = v)
                            NumberKind.Interval -> config.copy(minInterval = v)
                        },
                    )
                }
            },
        )
    }

    if (showLog) {
        TextDialog(
            title = stringResource(R.string.autoinput_log_title),
            text = logText,
            onDismiss = { showLog = false },
        )
    }

    if (showDiag) {
        TextDialog(
            title = stringResource(R.string.autoinput_diag_title),
            text = diagText,
            onDismiss = { showDiag = false },
        )
    }
}

private data class Quad(val title: String, val min: Int, val max: Int, val value: Int)

/** 卡片底色：取自图标里深色包体的冷蓝调，与卡密登录页的藏青保持一致。 */
private val HeroTop = Color(0xFF2B2E42)
private val HeroBottom = Color(0xFF474E70)

/**
 * 页首状态卡：左侧图标圆盘 + 运行状态点 + 大开关。
 * 右侧用图标本体做一层淡淡的水印，让页面和桌面图标有视觉联系。
 */
@Composable
private fun AutoInputHero(
    title: String,
    statusText: String,
    loaded: Boolean,
    checked: Boolean,
    running: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dotColor = when {
        !loaded -> Color.White.copy(alpha = 0.35f)
        !checked -> Color.White.copy(alpha = 0.28f)
        running -> Color(0xFF7CE3AE)
        else -> Color(0xFFFFC46B)
    }
    val iconRes = AppInfo.appIconRes()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom))),
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(148.dp)
                .offset(x = 36.dp, y = 10.dp)
                .alpha(0.10f)
                .clip(CircleShape),
        )

        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3f.sp,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HeroTop,
                    checkedTrackColor = Color(0xFFE9EBF4),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.92f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.45f),
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScaffoldLike(
    title: String,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackBarHost: SnackbarHostState,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.blurEffect(),
                title = { Text(title) },
                navigationIcon = { AppBackButton(onClick = onBack) },
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SwipeableSnackbarHost(hostState = snackBarHost) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppMultiPickerDialog(
    apps: List<PmApp>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autoinput_pick_app)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.autoinput_apps_search) + " (" + apps.size + ")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.autoinput_apps_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = app.pkg in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = if (checked) picked - app.pkg else picked + app.pkg
                                    }
                                    .padding(vertical = 6.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        picked = if (on) picked + app.pkg else picked - app.pkg
                                    },
                                )
                                PackageIcon(app.pkg, null, Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.autoinput_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoinput_cancel))
            }
        },
    )
}

@Composable
private fun TextEditorDialog(
    title: String,
    hint: String,
    initial: String,
    minLines: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onTest: ((String) -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等对话框真正挂上窗口再要焦点，否则注入测试没有落点
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                minLines = minLines,
                maxLines = 12,
                supportingText = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hint, modifier = Modifier.weight(1f))
                        if (onTest != null) {
                            TextButton(onClick = { onTest(text) }) {
                                Text(stringResource(R.string.autoinput_test_inject))
                            }
                        }
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.autoinput_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoinput_cancel))
            }
        },
    )
}

@Composable
private fun ScriptPathDialog(
    initial: String,
    rootFiles: RootFileRepository,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var path by remember { mutableStateOf(initial) }
    var browsing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autoinput_script_title)) },
        text = {
            if (browsing) {
                ScriptPickerContent(
                    rootFiles = rootFiles,
                    onPick = { picked ->
                        path = picked
                        browsing = false
                    },
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text(stringResource(R.string.autoinput_script_title)) },
                        supportingText = { Text(stringResource(R.string.autoinput_script_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { browsing = true }) {
                        Text(stringResource(R.string.autoinput_script_browse))
                    }
                }
            }
        },
        confirmButton = {
            if (browsing) {
                TextButton(onClick = { browsing = false }) {
                    Text(stringResource(R.string.autoinput_back))
                }
            } else {
                TextButton(onClick = { onConfirm(path.trim()) }) {
                    Text(stringResource(R.string.autoinput_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoinput_cancel))
            }
        },
    )
}

@Composable
private fun ScriptPickerContent(
    rootFiles: RootFileRepository,
    onPick: (String) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<RootFileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        error = null
        entries = withContext(Dispatchers.IO) {
            runCatching {
                rootFiles.list(path)
                    .filter { it.isDirectory || it.name.endsWith(".sh") }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }.getOrElse {
                error = it.message
                emptyList()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    if (path != "/") {
                        path = path.substringBeforeLast('/', "/").ifEmpty { "/" }
                    }
                },
            ) { Text("..") }
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.autoinput_files_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
            items(entries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) path = entry.path else onPick(entry.path)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderDialog(
    title: String,
    min: Int,
    max: Int,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initial.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.autoinput_seconds, value.toInt()),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = if (max - min > 60) 0 else (max - min - 1).coerceAtLeast(0),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toInt()) }) {
                Text(stringResource(R.string.autoinput_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoinput_cancel))
            }
        },
    )
}

@Composable
private fun TextDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoinput_confirm))
            }
        },
    )
}

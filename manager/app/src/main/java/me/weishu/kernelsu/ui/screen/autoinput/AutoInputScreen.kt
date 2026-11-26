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
private const val BUNDLED_VERSION = "1"

private val SCRIPT_NAMES = listOf(
    "common.sh",
    "daemon.sh",
    "supervisor.sh",
    "restart.sh",
    "status.sh",
    "diag.sh",
    "test_input.sh",
)

private const val STATUS_SCRIPT = """
CONF=/data/adb/auto_input
[ -d ${'$'}CONF/bin ] && echo DIR=1 || echo DIR=0
[ -f /data/adb/service.d/chen-auto-input.sh ] && echo BOOT=1 || echo BOOT=0
echo ST=${'$'}(cat ${'$'}CONF/status 2>/dev/null)
P=${'$'}(pgrep -f 'auto_input/bin/[d]aemon\.sh' 2>/dev/null | head -n1)
[ -z "${'$'}P" ] && P=${'$'}(ps -A 2>/dev/null | grep 'auto_input/bin/[d]aemon\.sh' | grep -v grep | awk '{print ${'$'}2}' | head -n1)
echo PID=${'$'}P
S=${'$'}(pgrep -f 'auto_input/bin/[s]upervisor\.sh' 2>/dev/null | head -n1)
[ -z "${'$'}S" ] && S=${'$'}(ps -A 2>/dev/null | grep 'auto_input/bin/[s]upervisor\.sh' | grep -v grep | awk '{print ${'$'}2}' | head -n1)
echo SPID=${'$'}S
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

private const val REMOVE_SCRIPT = """
CONF=/data/adb/auto_input
for p in ${'$'}(pgrep -f 'auto_input/bin/[s]upervisor\.sh' 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
for p in ${'$'}(pgrep -f 'auto_input/bin/[d]aemon\.sh' 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
hp=${'$'}(cat ${'$'}CONF/.script_hold_pid 2>/dev/null)
[ -n "${'$'}hp" ] && kill -9 ${'$'}hp 2>/dev/null
cp=${'$'}(cat ${'$'}CONF/.script_child 2>/dev/null)
[ -n "${'$'}cp" ] && kill -9 ${'$'}cp 2>/dev/null
rm -f /data/adb/service.d/chen-auto-input.sh
rm -rf ${'$'}CONF
echo REMOVED
"""

private data class AutoInputStatus(
    val installed: Boolean = false,
    val bootRegistered: Boolean = false,
    val enabled: Boolean = true,
    val daemonRunning: Boolean = false,
    val supervisorRunning: Boolean = false,
)

private data class AutoInputConfig(
    val pkgs: List<String> = emptyList(),
    val input: String = "",
    val scriptPath: String = "",
    val delaySec: Int = 5,
    val speedSec: Int = 3,
    val minInterval: Int = 8,
    val mode: String = "script",
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

private fun assetBase64(context: Context, name: String): String =
    Base64.encodeToString(
        context.assets.open("autoinput/$name").use { it.readBytes() },
        Base64.NO_WRAP,
    )

private fun writeFile(ksuCli: KsuCliRepository, path: String, content: String): String {
    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
    return runRoot(ksuCli, "mkdir -p ${CONF_DIR}\nprintf %s '$b64' | base64 -d > '$path'\necho OK")
}

private fun installScripts(context: Context, ksuCli: KsuCliRepository): String {
    val script = buildString {
        append("mkdir -p $BIN_DIR /data/adb/service.d\n")
        SCRIPT_NAMES.forEach { name ->
            append("printf %s '").append(assetBase64(context, "bin/$name"))
                .append("' | base64 -d > $BIN_DIR/$name\n")
            append("chmod 755 $BIN_DIR/$name\n")
        }
        append("printf %s '").append(assetBase64(context, "boot.sh"))
            .append("' | base64 -d > $BOOT_SCRIPT\n")
        append("chmod 755 $BOOT_SCRIPT\n")
        append("echo '$BUNDLED_VERSION' > $CONF_DIR/.bundled_version\n")
        // 配置只补齐缺失项，不覆盖用户已有设置
        append("[ -f $CONF_DIR/pkg.txt ] || : > $CONF_DIR/pkg.txt\n")
        append("[ -f $CONF_DIR/input.txt ] || : > $CONF_DIR/input.txt\n")
        append("[ -f $CONF_DIR/script_path.txt ] || : > $CONF_DIR/script_path.txt\n")
        append("[ -f $CONF_DIR/delay.txt ] || echo 5 > $CONF_DIR/delay.txt\n")
        append("[ -f $CONF_DIR/speed.txt ] || echo 3 > $CONF_DIR/speed.txt\n")
        append("[ -f $CONF_DIR/min_interval.txt ] || echo 8 > $CONF_DIR/min_interval.txt\n")
        append("[ -f $CONF_DIR/input_mode.txt ] || echo script > $CONF_DIR/input_mode.txt\n")
        append("[ -f $CONF_DIR/keep_script.txt ] || echo 1 > $CONF_DIR/keep_script.txt\n")
        append("[ -f $CONF_DIR/order.txt ] || echo script_first > $CONF_DIR/order.txt\n")
        append("[ -f $CONF_DIR/status ] || echo 1 > $CONF_DIR/status\n")
        append("chmod 755 $CONF_DIR 2>/dev/null\n")
        append("echo INSTALLED\n")
    }
    val result = runRoot(ksuCli, script)
    Log.i("ChenAutoInput", "install: " + result.replace("\n", " | "))
    return result
}

private fun parseStatus(raw: String): AutoInputStatus = AutoInputStatus(
    installed = raw.contains("DIR=1"),
    bootRegistered = raw.contains("BOOT=1"),
    enabled = raw.lineSequence().firstOrNull { it.startsWith("ST=") }
        ?.removePrefix("ST=")?.trim() != "0",
    daemonRunning = raw.lineSequence().firstOrNull { it.startsWith("PID=") }
        ?.removePrefix("PID=")?.trim()?.isNotEmpty() == true,
    supervisorRunning = raw.lineSequence().firstOrNull { it.startsWith("SPID=") }
        ?.removePrefix("SPID=")?.trim()?.isNotEmpty() == true,
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

    return AutoInputConfig(
        pkgs = text("PKG").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
        input = text("IN"),
        scriptPath = text("SP").trim(),
        delaySec = num("delay", 5),
        speedSec = num("speed", 3),
        minInterval = num("interval", 8),
        mode = str("mode").ifEmpty { "script" },
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
    var showModePicker by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var numberKind by remember { mutableStateOf<NumberKind?>(null) }
    var logText by remember { mutableStateOf("") }
    var diagText by remember { mutableStateOf("") }

    val msgSaved = stringResource(R.string.autoinput_saved)
    val msgStarted = stringResource(R.string.autoinput_started)
    val msgStopped = stringResource(R.string.autoinput_stopped)
    val msgRemoved = stringResource(R.string.autoinput_removed)
    val msgTested = stringResource(R.string.autoinput_test_done)

    suspend fun refresh() {
        val rawStatus = withContext(Dispatchers.IO) { runRoot(ksuCli, STATUS_SCRIPT) }
        status = parseStatus(rawStatus)
        config = parseConfig(withContext(Dispatchers.IO) { runRoot(ksuCli, READ_CONFIG_SCRIPT) })
        loaded = true
    }

    suspend fun persist(next: AutoInputConfig) {
        busy = true
        withContext(Dispatchers.IO) {
            writeFile(ksuCli, "$CONF_DIR/pkg.txt", next.pkgs.joinToString("\n"))
            writeFile(ksuCli, "$CONF_DIR/input.txt", next.input)
            writeFile(ksuCli, "$CONF_DIR/script_path.txt", next.scriptPath)
            writeFile(ksuCli, "$CONF_DIR/delay.txt", next.delaySec.toString())
            writeFile(ksuCli, "$CONF_DIR/speed.txt", next.speedSec.toString())
            writeFile(ksuCli, "$CONF_DIR/min_interval.txt", next.minInterval.toString())
            writeFile(ksuCli, "$CONF_DIR/input_mode.txt", next.mode)
            writeFile(ksuCli, "$CONF_DIR/keep_script.txt", "1")
        }
        config = next
        busy = false
        snackBarHost.showReplacingSnackbar(msgSaved)
    }

    LaunchedEffect(Unit) {
        val version = withContext(Dispatchers.IO) {
            runRoot(ksuCli, "cat $CONF_DIR/.bundled_version 2>/dev/null")
        }.trim()
        val bootOk = withContext(Dispatchers.IO) { runRoot(ksuCli, STATUS_SCRIPT) }.contains("BOOT=1")
        if (version != BUNDLED_VERSION || !bootOk) {
            busy = true
            withContext(Dispatchers.IO) { installScripts(context, ksuCli) }
            busy = false
        }
        refresh()
        pmApps = withContext(Dispatchers.IO) { loadPmApps(context) }
        // 开启状态下确保守护进程活着
        if (status.installed && status.enabled && !status.daemonRunning) {
            withContext(Dispatchers.IO) { runRoot(ksuCli, "sh $BIN_DIR/restart.sh >/dev/null 2>&1") }
            delay(600)
            refresh()
        }
        while (true) {
            delay(4000)
            if (!busy) refresh()
        }
    }

    val modeLabel = if (config.mode == "screen") {
        stringResource(R.string.autoinput_mode_screen)
    } else {
        stringResource(R.string.autoinput_mode_script)
    }

    val statusText = when {
        !loaded -> stringResource(R.string.autoinput_status_loading)
        busy -> stringResource(R.string.autoinput_working)
        !status.installed -> stringResource(R.string.autoinput_status_not_installed)
        !status.enabled -> stringResource(R.string.autoinput_status_off)
        status.daemonRunning -> stringResource(R.string.autoinput_status_running)
        else -> stringResource(R.string.autoinput_status_idle)
    }

    val labelOf: (String) -> String = { pkg ->
        pmApps.firstOrNull { it.pkg == pkg }?.label?.takeIf { it.isNotBlank() } ?: pkg
    }

    val inputLineCount = config.input.lineSequence().count { it.isNotBlank() }

    val toggle: (Boolean) -> Unit = { on ->
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                if (on) {
                    if (!status.installed) installScripts(context, ksuCli)
                    writeFile(ksuCli, "${CONF_DIR}/status", "1")
                    runRoot(ksuCli, "sh ${BIN_DIR}/restart.sh >/dev/null 2>&1")
                } else {
                    writeFile(ksuCli, "${CONF_DIR}/status", "0")
                    runRoot(
                        ksuCli,
                        "for p in \$(pgrep -f 'auto_input/bin/[s]upervisor\\.sh' 2>/dev/null); do kill -9 \$p; done\n" +
                            "for p in \$(pgrep -f 'auto_input/bin/[d]aemon\\.sh' 2>/dev/null); do kill -9 \$p; done",
                    )
                }
            }
            delay(if (on) 800 else 400)
            refresh()
            busy = false
            snackBarHost.showReplacingSnackbar(if (on) msgStarted else msgStopped)
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
                    checked = status.installed && status.enabled,
                    running = status.installed && status.enabled && status.daemonRunning,
                    enabled = loaded && !busy,
                    onCheckedChange = toggle,
                )
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
                    item {
                        SettingsJumpPageWidget(
                            title = stringResource(R.string.autoinput_mode_title),
                            description = modeLabel,
                            onClick = { showModePicker = true },
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
                                        val a = runRoot(
                                            ksuCli,
                                            "tail -n 160 $CONF_DIR/daemon.log 2>/dev/null",
                                        )
                                        val b = runRoot(
                                            ksuCli,
                                            "tail -n 80 $CONF_DIR/script.log 2>/dev/null",
                                        )
                                        buildString {
                                            append("===== daemon.log =====\n").append(a)
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

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text(stringResource(R.string.autoinput_mode_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    ModeRow(
                        title = stringResource(R.string.autoinput_mode_script),
                        desc = stringResource(R.string.autoinput_mode_script_desc),
                        selected = config.mode != "screen",
                        onClick = {
                            showModePicker = false
                            scope.launch { persist(config.copy(mode = "script")) }
                        },
                    )
                    ModeRow(
                        title = stringResource(R.string.autoinput_mode_screen),
                        desc = stringResource(R.string.autoinput_mode_screen_desc),
                        selected = config.mode == "screen",
                        onClick = {
                            showModePicker = false
                            scope.launch { persist(config.copy(mode = "screen")) }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModePicker = false }) {
                    Text(stringResource(R.string.autoinput_cancel))
                }
            },
        )
    }

    numberKind?.let { kind ->
        val (title, min, max, value) = when (kind) {
            NumberKind.Delay -> Quad(
                stringResource(R.string.autoinput_delay_title), 0, 60, config.delaySec,
            )

            NumberKind.Speed -> Quad(
                stringResource(R.string.autoinput_speed_title), 0, 60, config.speedSec,
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
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = minLines,
                maxLines = 12,
                supportingText = { Text(hint) },
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

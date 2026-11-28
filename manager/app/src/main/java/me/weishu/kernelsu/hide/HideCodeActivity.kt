package me.weishu.kernelsu.hide

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import me.weishu.kernelsu.R

/**
 * 「隐藏与找回」设置页。
 *
 * 交互模型：
 *   1. 选一个「触发应用」，比如计算器；
 *   2. 选触发方式：音量上 / 音量下 / 任一音量键 / 不用按（打开就触发）；
 *   3. 点「布置守护」——root 守护落到 /data/adb/chen_guard，开机自启；
 *   4. 点「现在隐藏」——Chen 备份自己的数据后被卸载，系统里彻底没有它；
 *   5. 想用时打开那个触发应用（按一下音量键），守护把 Chen 静默装回来并直接跳进去。
 *
 * 全程用最朴素的原生控件：这个页面要在各种 ROM 上都能开，别引入 Compose 主题依赖。
 */
class HideCodeActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }

    private var targetPkg: String = ""
    private lateinit var targetView: TextView
    private lateinit var statusView: TextView
    private lateinit var triggerGroup: RadioGroup
    private lateinit var holdInput: EditText
    private lateinit var waitInput: EditText
    private lateinit var armButton: Button
    private lateinit var testButton: Button
    private lateinit var hideButton: Button
    private lateinit var stopButton: Button
    private lateinit var iconSwitch: Switch
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPkg = ChenGuard.target(this)
        val pad = (dp * 20).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_title)
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF15151C.toInt())
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_intro)
                textSize = 13f
                setTextColor(0xFF6B6C7C.toInt())
                setPadding(0, (dp * 8).toInt(), 0, (dp * 16).toInt())
                setLineSpacing(0f, 1.45f)
            },
        )

        // ---------------- 触发应用 ----------------
        root.addView(sectionLabel(getString(R.string.hide_target_label)))
        targetView = TextView(this).apply {
            textSize = 15.5f
            setTextColor(0xFF5B4BD6.toInt())
            setPadding(0, (dp * 8).toInt(), 0, (dp * 8).toInt())
            setOnClickListener { pickApp() }
        }
        root.addView(targetView)

        // ---------------- 触发方式 ----------------
        root.addView(sectionLabel(getString(R.string.hide_trigger_label)))
        triggerGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, (dp * 4).toInt(), 0, (dp * 4).toInt())
        }
        addRadio(triggerGroup, ChenGuard.TRIGGER_UP, getString(R.string.hide_trigger_up))
        addRadio(triggerGroup, ChenGuard.TRIGGER_DOWN, getString(R.string.hide_trigger_down))
        addRadio(triggerGroup, ChenGuard.TRIGGER_ANY, getString(R.string.hide_trigger_any))
        addRadio(triggerGroup, ChenGuard.TRIGGER_AUTO, getString(R.string.hide_trigger_auto))
        root.addView(triggerGroup)
        checkTrigger(ChenGuard.trigger(this))

        // ---------------- 时长 ----------------
        holdInput = timingRow(root, getString(R.string.hide_hold_label), ChenGuard.hold(this))
        waitInput = timingRow(root, getString(R.string.hide_wait_label), ChenGuard.waitSec(this))
        updateTimingEnabled()
        triggerGroup.setOnCheckedChangeListener { _, _ -> updateTimingEnabled() }

        // ---------------- 主操作 ----------------
        armButton = primaryButton(getString(R.string.hide_arm)) { arm() }
        root.addView(armButton)

        testButton = ghostButton(getString(R.string.hide_test)) { test() }
        root.addView(testButton)

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A8B99.toInt())
            setLineSpacing(0f, 1.35f)
            setPadding(0, (dp * 12).toInt(), 0, (dp * 12).toInt())
            text = getString(R.string.hide_status_loading)
        }
        root.addView(statusView)

        // ---------------- 隐藏（卸载） ----------------
        root.addView(sectionLabel(getString(R.string.hide_hide_section)))
        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_hide_tip)
                textSize = 12.5f
                setTextColor(0xFF8A8B99.toInt())
                setLineSpacing(0f, 1.4f)
                setPadding(0, (dp * 6).toInt(), 0, (dp * 10).toInt())
            },
        )

        hideButton = dangerButton(getString(R.string.hide_hide_button)) { confirmHide() }
        root.addView(hideButton)

        stopButton = ghostButton(getString(R.string.hide_stop)) { stopGuard() }
        root.addView(stopButton)

        iconSwitch = Switch(this).apply {
            text = getString(R.string.hide_icon_label)
            textSize = 14.5f
            isChecked = HidePrefs.isIconHidden(this@HideCodeActivity)
            setPadding(0, (dp * 18).toInt(), 0, (dp * 6).toInt())
            setOnCheckedChangeListener { _, checked ->
                val ok = HidePrefs.setIconHidden(this@HideCodeActivity, checked)
                if (!ok) {
                    toast(getString(R.string.hide_icon_failed))
                    isChecked = !checked
                }
            }
        }
        root.addView(iconSwitch)
        root.addView(
            TextView(this).apply {
                text = getString(R.string.hide_icon_summary)
                textSize = 12.5f
                setTextColor(0xFF8A8B99.toInt())
                setLineSpacing(0f, 1.4f)
                setPadding(0, 0, 0, (dp * 24).toInt())
            },
        )

        setContentView(ScrollView(this).apply { addView(root) })
        renderTarget()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    // ------------------------------------------------------------ UI 小工具

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF8A8B99.toInt())
        setPadding(0, (dp * 12).toInt(), 0, 0)
    }

    /** 「长按时长 / 等待时长」一行：标签 + 数字输入 + 单位。 */
    private fun timingRow(parent: LinearLayout, label: String, value: Int): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (dp * 6).toInt(), 0, (dp * 2).toInt())
        }
        row.addView(
            TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF15151C.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val input = EditText(this).apply {
            setText(value.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setSelectAllOnFocus(true)
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(3))
            layoutParams = LinearLayout.LayoutParams((dp * 68).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(input)
        row.addView(
            TextView(this).apply {
                text = getString(R.string.hide_seconds_unit)
                textSize = 14f
                setTextColor(0xFF6B6C7C.toInt())
                setPadding((dp * 6).toInt(), 0, 0, 0)
            },
        )
        parent.addView(row)
        return input
    }

    /** 「打开就触发」时，长按时长没意义，置灰。 */
    private fun updateTimingEnabled() {
        val auto = selectedTrigger() == ChenGuard.TRIGGER_AUTO
        if (::holdInput.isInitialized) holdInput.isEnabled = !auto
        if (::waitInput.isInitialized) waitInput.isEnabled = true
    }

    private fun holdValue(): Int {
        val v = if (::holdInput.isInitialized) holdInput.text.toString().trim().toIntOrNull() else null
        return (v ?: ChenGuard.DEFAULT_HOLD).coerceIn(1, 20)
    }

    private fun waitValue(): Int {
        val v = if (::waitInput.isInitialized) waitInput.text.toString().trim().toIntOrNull() else null
        return (v ?: ChenGuard.DEFAULT_WAIT).coerceIn(1, 120)
    }

    private fun addRadio(group: RadioGroup, tag: String, text: String) {
        val radio = RadioButton(this).apply {
            this.text = text
            textSize = 14.5f
            this.tag = tag
            setTextColor(0xFF15151C.toInt())
            setPadding(0, (dp * 4).toInt(), 0, (dp * 4).toInt())
        }
        group.addView(radio)
    }

    private fun checkTrigger(trigger: String) {
        for (i in 0 until triggerGroup.childCount) {
            val child = triggerGroup.getChildAt(i)
            if (child is RadioButton && child.tag == trigger) {
                child.isChecked = true
                return
            }
        }
        (triggerGroup.getChildAt(0) as? RadioButton)?.isChecked = true
    }

    private fun selectedTrigger(): String {
        val id = triggerGroup.checkedRadioButtonId
        val radio = if (id == -1) null else findViewById<View>(id)
        return (radio?.tag as? String) ?: ChenGuard.TRIGGER_UP
    }

    private fun renderTarget() {
        targetView.text = if (targetPkg.isBlank()) {
            getString(R.string.hide_target_none) + "  ▸"
        } else {
            "${ChenGuard.labelOf(this, targetPkg)}   （$targetPkg）  ▸"
        }
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp * 12
            setColor(0xFF5B4BD6.toInt())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 46).toInt(),
        ).also { it.topMargin = (dp * 8).toInt() }
    }

    private fun ghostButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF5B4BD6.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp * 12
            setColor(0xFFF1EFFC.toInt())
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 44).toInt(),
        ).also { it.topMargin = (dp * 10).toInt() }
    }

    private fun dangerButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp * 12
            setColor(0xFFD9354A.toInt())
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * 46).toInt(),
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ 业务

    private fun pickApp() {
        val apps = ChenGuard.launchableApps(this)
        if (apps.isEmpty()) {
            toast(getString(R.string.hide_no_apps))
            return
        }
        val labels = apps.map { "${it.label}    ·    ${it.pkg}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_choose_title))
            .setItems(labels) { _, which ->
                targetPkg = apps[which].pkg
                renderTarget()
                ChenGuard.setSelection(this, targetPkg, selectedTrigger())
                refreshStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun arm() {
        if (targetPkg.isBlank()) {
            toast(getString(R.string.hide_target_none))
            return
        }
        if (busy) return
        val trigger = selectedTrigger()
        runBusy(getString(R.string.hide_arm_running)) {
            val out = ChenGuard.arm(this, targetPkg, trigger, holdValue(), waitValue())
            runOnUiThread {
                val ok = out.contains("started") || out.contains("GUARD_OK")
                toast(getString(if (ok) R.string.hide_arm_ok else R.string.hide_failed))
                refreshStatus()
            }
        }
    }

    /**
     * 「测试触发」：不动 Chen，只验证「打开触发应用 → 长按 N 秒 → 跳回 Chen」这条链路。
     * 守护本来就在监听，这里只是把最新配置 + 最新脚本推下去并清空日志，
     * 剩下靠用户真的去长按一次，测的就是线上那条路径，不是模拟。
     */
    private fun test() {
        if (busy) return
        if (targetPkg.isBlank()) {
            toast(getString(R.string.hide_target_none))
            return
        }
        runBusy(getString(R.string.hide_test_running)) {
            val trigger = selectedTrigger()
            val hold = holdValue()
            val wait = waitValue()
            val out = ChenGuard.prepareTriggerTest(this, targetPkg, trigger, hold, wait)
            val ok = out.contains("RUNNING=1")
            val label = ChenGuard.labelOf(this, targetPkg)
            runOnUiThread {
                if (ok) {
                    val guide = if (trigger == ChenGuard.TRIGGER_AUTO) {
                        getString(R.string.hide_test_ready_auto, label, wait)
                    } else {
                        getString(
                            R.string.hide_test_ready_hold,
                            label,
                            ChenGuard.triggerLabel(this@HideCodeActivity, trigger),
                            hold,
                        )
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.hide_test_title))
                        .setMessage(guide)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    toast(getString(R.string.hide_need_arm))
                }
                refreshStatus()
            }
        }
    }

    private fun confirmHide() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hide_hide_button))
            .setMessage(getString(R.string.hide_hide_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ -> doHide() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doHide() {
        runBusy(getString(R.string.hide_hide_running)) {
            if (targetPkg.isBlank()) {
                runOnUiThread { toast(getString(R.string.hide_target_none)) }
                return@runBusy
            }
            // 1) 卸载前必须确认守护真的在跑：否则 Chen 一消失就再也没东西能把它装回来
            ChenGuard.arm(this, targetPkg, selectedTrigger(), holdValue(), waitValue())
            val status = ChenGuard.status(this)
            if (!status.apkReady || !status.running) {
                runOnUiThread { toast(getString(R.string.hide_need_arm)) }
                return@runBusy
            }

            // 2) 备份数据。必须在 App 还活着的时候做——卸载一旦开始，本进程连同
            //    这一个 root shell 都会被一起收掉。
            val bak = ChenGuard.backupData(this)
            val sizeKb = Regex("""@@SZ\s*(\d+)""").find(bak)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (sizeKb < 8) {
                runOnUiThread { toast(getString(R.string.hide_backup_failed)) }
                return@runBusy
            }

            // 3) 让守护用 root 卸。守护是 init 侧进程，本 App 被杀它也不受影响。
            runOnUiThread { statusView.text = getString(R.string.hide_root_running) }
            ChenGuard.requestUninstall()

            // 4) 盯着结果。卸载真的成功的话，本进程会被系统当场杀掉，
            //    根本执行不到下面这段；能执行到，就说明 root 这条路没通。
            val result = ChenGuard.waitResult(25)

            if (result.contains("RESULT=ok")) {
                // 极端情况下（包已被别的方式处理）进程还在，直接收尾
                runOnUiThread { toast(getString(R.string.hide_done)) }
                return@runBusy
            }

            // 5) root 没卸掉：把真实原因摆到界面上，然后交给系统卸载器兜底。
            //    这一层不受 root/installd 的限制，系统自己的卸载器永远有权删自己。
            val why = result.lineSequence()
                .firstOrNull { it.startsWith("WHY=") }
                ?.removePrefix("WHY=")?.trim().orEmpty()
            runOnUiThread {
                if (why.isNotBlank()) statusView.text = getString(R.string.hide_last_fail, why)
                val ok = runCatching { ChenGuard.systemUninstall(this) }.getOrDefault(false)
                toast(getString(if (ok) R.string.hide_sys_confirm else R.string.hide_failed))
                if (!ok) refreshStatus()
            }
        }
    }

    private fun stopGuard() {
        if (busy) return
        runBusy(getString(R.string.hide_stop_running)) {
            ChenGuard.disarm()
            runOnUiThread {
                toast(getString(R.string.hide_stop_ok))
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        Thread {
            val status = runCatching { ChenGuard.status(this) }.getOrNull() ?: return@Thread
            val targetLabel = if (targetPkg.isBlank()) {
                getString(R.string.hide_target_none)
            } else {
                ChenGuard.labelOf(this, targetPkg)
            }
            val trigger = status.trigger.ifBlank { ChenGuard.trigger(this) }
            val text = buildString {
                append(getString(if (status.running) R.string.hide_state_running else R.string.hide_state_stopped))
                append(" · ")
                // 守护脚本版本：确认真机 /data/adb 里跑的是这一版，而不是装完没重新布置
                append(getString(R.string.hide_state_guard_ver, status.guardVer.ifEmpty { "-" }))
                append(" · ")
                append(getString(if (status.apkReady) R.string.hide_state_apk_ok else R.string.hide_state_apk_missing))
                append(" · ")
                append(getString(if (status.installed) R.string.hide_state_installed else R.string.hide_state_uninstalled))
                if (status.backup) {
                    append(" · ")
                    append(getString(R.string.hide_state_backup))
                }
                append('\n')
                val triggerDesc = if (trigger == ChenGuard.TRIGGER_AUTO) {
                    getString(R.string.hide_trigger_desc_wait, status.waitSec)
                } else {
                    getString(
                        R.string.hide_trigger_desc_hold,
                        ChenGuard.triggerLabel(this@HideCodeActivity, trigger),
                        status.hold,
                    )
                }
                append(getString(R.string.hide_state_trigger, targetLabel, triggerDesc))
                // 上次点「现在隐藏」的结果：失败时不用去翻日志，重开本页就能看到原因
                if (status.lastResult.isNotBlank()) {
                    val kv = status.lastResult.lineSequence()
                        .filter { it.contains('=') }
                        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                    append("\n")
                    when (kv["RESULT"]) {
                        "ok" -> append(
                            getString(R.string.hide_last_ok, kv["MODE"].orEmpty(), kv["AT"].orEmpty()),
                        )

                        "fail" -> {
                            append(getString(R.string.hide_last_fail, kv["WHY"].orEmpty()))
                            // 诊断行：uid / SELinux 域 / 用一个不存在的包探出来的 PMS 通道状态
                            val diag = kv["DIAG"].orEmpty()
                            if (diag.isNotBlank()) {
                                append("\n")
                                append(diag)
                            }
                        }
                    }
                }
                if (status.logTail.isNotBlank()) {
                    append("\n\n")
                    append(status.logTail.take(400))
                }
                // 触发日志：进入应用 / 按下 / 判定 / 跳转，全在这里。
                // 测试触发时这一段就是唯一的证据链。
                if (status.triggerLog.isNotBlank()) {
                    append("\n\n【触发记录】\n")
                    append(status.triggerLog.take(600))
                }
            }
            runOnUiThread {
                if (!isFinishing && ::statusView.isInitialized) statusView.text = text
            }
        }.start()
    }

    /** 跑一段需要 root 的耗时操作：禁用按钮 -> 后台线程 -> 完事恢复。 */
    private fun runBusy(label: String, block: () -> Unit) {
        busy = true
        statusView.text = label
        setButtonsEnabled(false)
        Thread {
            runCatching { block() }.onFailure { err ->
                runOnUiThread { toast(err.message ?: getString(R.string.hide_failed)) }
            }
            runOnUiThread {
                busy = false
                if (!isFinishing) setButtonsEnabled(true)
            }
        }.start()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        armButton.isEnabled = enabled
        testButton.isEnabled = enabled
        hideButton.isEnabled = enabled
        stopButton.isEnabled = enabled
    }
}

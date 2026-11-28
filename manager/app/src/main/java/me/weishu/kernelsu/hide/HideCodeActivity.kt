package me.weishu.kernelsu.hide

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
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
            val out = ChenGuard.arm(this, targetPkg, trigger)
            runOnUiThread {
                val ok = out.contains("started") || out.contains("GUARD_OK")
                toast(getString(if (ok) R.string.hide_arm_ok else R.string.hide_failed))
                refreshStatus()
            }
        }
    }

    private fun test() {
        if (busy) return
        runBusy(getString(R.string.hide_test_running)) {
            val out = ChenGuard.testRestore()
            runOnUiThread {
                val ok = out.contains("INSTALLED=1")
                toast(getString(if (ok) R.string.hide_done else R.string.hide_failed))
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
            // 卸载前必须确认守护真的在跑：否则 Chen 一消失就再也没东西能把它装回来
            ChenGuard.arm(this, targetPkg, selectedTrigger())
            val status = ChenGuard.status(this)
            if (!status.apkReady || !status.running) {
                runOnUiThread { toast(getString(R.string.hide_need_arm)) }
                return@runBusy
            }
            ChenGuard.setSelection(this, targetPkg, selectedTrigger())
            val out = ChenGuard.requestUninstall()
            runOnUiThread {
                if (out.contains("OK")) {
                    toast(getString(R.string.hide_hide_queued))
                    finish()
                } else {
                    toast(getString(R.string.hide_failed))
                }
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
                append(getString(if (status.apkReady) R.string.hide_state_apk_ok else R.string.hide_state_apk_missing))
                append(" · ")
                append(getString(if (status.installed) R.string.hide_state_installed else R.string.hide_state_uninstalled))
                if (status.backup) {
                    append(" · ")
                    append(getString(R.string.hide_state_backup))
                }
                append('\n')
                append(
                    getString(
                        R.string.hide_state_trigger,
                        targetLabel,
                        ChenGuard.triggerLabel(this@HideCodeActivity, trigger),
                    ),
                )
                if (status.logTail.isNotBlank()) {
                    append("\n\n")
                    append(status.logTail.take(400))
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

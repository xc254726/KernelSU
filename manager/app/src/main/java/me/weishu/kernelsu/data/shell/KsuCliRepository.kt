package me.weishu.kernelsu.data.shell

import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.ui.util.KsuCli

/**
 * 移植自 BakaSU 的精简 shell 仓库：文件管理 / 监听界面只需要 root shell 入口，
 * 其余能力直接复用 [KsuCli]。
 */
class KsuCliRepository {
    fun getRootShell(globalMnt: Boolean = false): Shell = KsuCli.getRootShell(globalMnt)

    fun rootAvailable(): Boolean = KsuCli.rootAvailable()
}

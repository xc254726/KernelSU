package me.weishu.kernelsu.data.shell

import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.ui.util.getRootShell as utilGetRootShell
import me.weishu.kernelsu.ui.util.rootAvailable as utilRootAvailable

/**
 * 移植自 BakaSU 的精简 shell 仓库：文件管理 / 监听界面只需要 root shell 入口，
 * 其余能力直接复用 me.weishu.kernelsu.ui.util 的顶层 shell 函数。
 */
class KsuCliRepository {
    fun getRootShell(globalMnt: Boolean = false): Shell = utilGetRootShell(globalMnt)

    fun rootAvailable(): Boolean = utilRootAvailable()
}

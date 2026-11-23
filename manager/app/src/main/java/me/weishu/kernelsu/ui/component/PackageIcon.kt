package me.weishu.kernelsu.ui.component

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按包名显示应用图标（从 BakaSU 移植的适配层）。
 * 原实现走 Koin + Coil，这里改为复用工程自带的 [AppIconImage]。
 */
@Composable
fun PackageIcon(
    packageName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appInfo by produceState<ApplicationInfo?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        }
    }
    val info = appInfo
    if (info != null) {
        val label = contentDescription ?: remember(info) {
            runCatching {
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
        AppIconImage(
            modifier = modifier,
            applicationInfo = info,
            label = label,
        )
    } else {
        Box(modifier = modifier.size(24.dp))
    }
}

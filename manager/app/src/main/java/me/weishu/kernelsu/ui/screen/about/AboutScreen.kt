package me.weishu.kernelsu.ui.screen.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.AppInfo
import me.weishu.kernelsu.ui.util.DeviceCode

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val htmlString = stringResource(
        id = R.string.about_source_code,
        "<b><a href=\"https://github.com/KOWX712/KernelSU\">GitHub</a></b>",
        "<b><a href=\"https://t.me/kowsu_build\">Telegram</a></b>"
    )
    val deviceCode = rememberDeviceCode()
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = AppInfo.appName(),
        appIconRes = AppInfo.appIconRes(),
        versionName = BuildConfig.VERSION_NAME,
        deviceCode = deviceCode,
        links = extractLinks(htmlString),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
        onCopyDeviceCode = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("device code", deviceCode))
            Toast.makeText(context, R.string.about_device_code_copied, Toast.LENGTH_SHORT).show()
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}

@Composable
private fun rememberDeviceCode(): String {
    val context = LocalContext.current
    return remember(context) { DeviceCode.get(context) }
}

package me.weishu.kernelsu.ui.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** 由主界面提供的全局 Snackbar 状态（从 BakaSU 移植）。 */
val LocalSnackbarHost = compositionLocalOf<SnackbarHostState> {
    error("CompositionLocal LocalSnackbarHost not present")
}

/** 背景模糊是否已由上层提供（移植桩恒为 false）。 */
val LocalBackgroundBlurProvided = compositionLocalOf { false }

/** 显示 snackbar 前先关掉上一条，避免堆叠。 */
suspend fun SnackbarHostState.showReplacingSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration =
        if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
    )
}

@Composable
fun adaptiveScaffoldWindowInsets(includeBottom: Boolean = true): WindowInsets = if (includeBottom) {
    WindowInsets.safeDrawing
} else {
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
}

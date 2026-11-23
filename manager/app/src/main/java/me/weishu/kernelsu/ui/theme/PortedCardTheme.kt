package me.weishu.kernelsu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片外观配置（从 BakaSU 移植的精简桩）。
 * 农民主工程的视觉体系不同，这里保留字段接口让移植界面编译通过，
 * blur 相关能力在 [blurEffect] / [renderBackgroundBlur] 中恒为 no-op。
 */
@Stable
class CardConfig {
    var cardAlpha: Float = 1f
        get() = if (isCustomBackgroundEnabled) field else 1f

    var cardElevation: Dp = 0.dp
        internal set

    var isShadowEnabled by mutableStateOf(true)
        internal set

    var isCustomBackgroundEnabled by mutableStateOf(false)
        internal set

    var isFlashCardBackgroundEnabled by mutableStateOf(true)
        internal set

    val flashCardBackgroundEnabled: Boolean
        get() = isCustomBackgroundEnabled || isFlashCardBackgroundEnabled

    var isCustomAlphaSet by mutableStateOf(false)
        internal set

    var isUserDarkModeEnabled by mutableStateOf(false)
}

/**
 * 主题开关配置（精简桩）。blur 恒关闭，界面自动走非模糊分支。
 */
@Stable
class ThemeConfig {
    var isEnableBlur: Boolean = false
    var isEnableBlurExp: Boolean = false
}

/** 无模糊背景时的占位实现，直接返回自身。 */
@Composable
fun Modifier.blurSource(): Modifier = this

/** 无模糊背景时的占位实现，直接返回自身。 */
@Composable
fun Modifier.blurEffect(
    compensateHorizontalOverscroll: Boolean = true,
    compensateVerticalOverscroll: Boolean = false,
    useFixedSurfaceBoundsForOverscroll: Boolean = false,
): Modifier = this

/** 无模糊背景时的占位实现，直接返回自身。 */
@Composable
fun Modifier.renderBackgroundBlur(tintColor: Color? = null): Modifier = this

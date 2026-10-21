package io.github.tsarubucreate.audioswitcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 画面の色（docs/画面.md）。PC の右下のポップアップと同じ系統にそろえ、PC と端末で1つの道具に見えるようにする。
 */
@Immutable
data class AppColors(
    /** 地: 背景。 */
    val ground: Color,
    /** 墨: 本文・数字。 */
    val ink: Color,
    /** 鉛: 補足の文。 */
    val graphite: Color,
    /** 罫: 止まっている線・枠。 */
    val rule: Color,
    /** 信号: 再生中の線・主なボタン。 */
    val signal: Color,
    /** 信号の上に載せる文字。 */
    val onSignal: Color,
    /** 警告: 繋がらない・知らせ。 */
    val alert: Color,
)

val LightAppColors = AppColors(
    ground = Color(0xFFFFFFFF),
    ink = Color(0xFF1F2328),
    graphite = Color(0xFF57606A),
    rule = Color(0xFFD0D7DE),
    signal = Color(0xFF0969DA),
    onSignal = Color(0xFFFFFFFF),
    alert = Color(0xFFCF222E),
)

val DarkAppColors = AppColors(
    ground = Color(0xFF161B21),
    ink = Color(0xFFE6EDF3),
    graphite = Color(0xFF9AA4AE),
    rule = Color(0xFF30363D),
    signal = Color(0xFF4C9AFF),
    onSignal = Color(0xFF0B1F3A),
    alert = Color(0xFFFF7B72),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** 等幅の数字（1秒ごとに桁が揺れないように）。経過時間と候補の数字に使う。 */
private const val TABULAR_FIGURES = "tnum"

/** 文字の大きさは 28 / 20 / 16 / 13 の4段だけ。 */
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_FIGURES),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_FIGURES),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
)

/** 白基調（ライト）とダークを切り替える。[dark] は、画面の右上の切り替えで選んだ色（選んでいなければ端末の設定。docs/画面.md）。 */
@Composable
fun AudioSwitcherTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkAppColors else LightAppColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.signal,
            onPrimary = colors.onSignal,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            onSurfaceVariant = colors.graphite,
            outline = colors.rule,
            outlineVariant = colors.rule,
            error = colors.alert,
        )
    } else {
        lightColorScheme(
            primary = colors.signal,
            onPrimary = colors.onSignal,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            onSurfaceVariant = colors.graphite,
            outline = colors.rule,
            outlineVariant = colors.rule,
            error = colors.alert,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

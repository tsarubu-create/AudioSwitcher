package io.github.tsarubucreate.audioswitcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** PC から端末への線が表す状態。 */
enum class RouteState {
    /** 止まっている: 灰色の細い線。 */
    Idle,

    /** 繋いでいる・繋ぎ直している: 青い破線。 */
    Connecting,

    /** 再生中: 青い実線の上を点が流れる。 */
    Playing,

    /** 繋がらない（繋ぎ直しを待っている）: 赤い破線。 */
    Failing,
}

/**
 * PC から端末へ引いた「線」。画面でいちばん目立たせる所で、線そのものが状態を表す（docs/画面.md）。
 * 動きは再生中の点の1か所だけ。[reduceMotion] のときは点を流さず、真ん中に止めて出す。
 */
@Composable
fun RouteLine(state: RouteState, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val progress by rememberInfiniteTransition(label = "route").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing), RepeatMode.Restart),
        label = "dot",
    )

    Canvas(modifier.height(24.dp)) {
        val y = size.height / 2
        val color = when (state) {
            RouteState.Idle -> colors.rule
            RouteState.Connecting, RouteState.Playing -> colors.signal
            RouteState.Failing -> colors.alert
        }
        val stroke = if (state == RouteState.Idle) 1.dp.toPx() else 2.dp.toPx()
        val dash = when (state) {
            RouteState.Connecting, RouteState.Failing -> PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            else -> null
        }
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round, pathEffect = dash)

        if (state == RouteState.Playing) {
            val x = size.width * (if (reduceMotion) 0.5f else progress)
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, y))
        }
    }
}

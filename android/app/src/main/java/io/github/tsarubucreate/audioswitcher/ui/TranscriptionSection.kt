package io.github.tsarubucreate.audioswitcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 画面に出す文字起こしの状態。 */
data class TranscriptionUiState(
    val enabled: Boolean = false,
    /** 保存先のフォルダの名前。未設定なら null。 */
    val folderLabel: String? = null,
    val status: String? = null,
    val statusIsProblem: Boolean = false,
    val method: String = "",
)

/**
 * 再生の画面の下に置く「文字起こし」の欄（docs/画面.md）。入・切、保存先、今の状態、方式。
 * 画面で目立たせるのは線だけなので、ここは細い線で区切って静かにする。
 */
@Composable
fun TranscriptionSection(
    state: TranscriptionUiState,
    onEnabledChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onOpenMethodSettings: () -> Unit,
) {
    val colors = LocalAppColors.current

    Spacer(Modifier.height(32.dp))
    HorizontalDivider(color = colors.rule)
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("文字起こし", style = MaterialTheme.typography.labelLarge, color = colors.ink, modifier = Modifier.weight(1f))
        Switch(
            checked = state.enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.signal,
                checkedThumbColor = colors.onSignal,
                uncheckedTrackColor = colors.ground,
                uncheckedBorderColor = colors.graphite,
                uncheckedThumbColor = colors.graphite,
            ),
        )
    }
    Text(
        "保存先: ${state.folderLabel ?: "まだ選んでいません"}",
        style = MaterialTheme.typography.bodySmall,
        color = colors.graphite,
    )
    TextButton(onClick = onPickFolder) {
        Text(
            if (state.folderLabel == null) "保存先を選ぶ" else "保存先を変える",
            style = MaterialTheme.typography.bodySmall,
            color = colors.signal,
        )
    }
    state.status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.statusIsProblem) colors.alert else colors.ink)
    }
    Text("方式: ${state.method}", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
    TextButton(onClick = onOpenMethodSettings) {
        Text("方式を変える", style = MaterialTheme.typography.bodySmall, color = colors.signal)
    }
}

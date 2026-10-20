package io.github.tsarubucreate.audioswitcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tsarubucreate.audioswitcher.transcription.HttpFailures
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionMethod

/** 方式の画面に出す設定。API キーそのものは画面に持たない（保存済みかどうかだけ）。 */
data class TranscriptionSettingsUiState(
    val method: TranscriptionMethod = TranscriptionMethod.GROQ,
    val groqKeySaved: Boolean = false,
    val geminiKeySaved: Boolean = false,
    val groqModel: String = "",
    val geminiModel: String = "",
    val onDeviceAvailable: Boolean = true,
)

/**
 * 文字起こしの方式の画面（docs/画面.md）。「文字起こし」の欄の「方式を変える」から開く。
 * 方式の選択（B・A・C）、B・A の API キーとモデルの名前。
 */
@Composable
fun TranscriptionSettingsScreen(
    state: TranscriptionSettingsUiState,
    onMethodChange: (TranscriptionMethod) -> Unit,
    onSaveKey: (TranscriptionMethod, String) -> Unit,
    onClearKey: (TranscriptionMethod) -> Unit,
    onSaveModel: (TranscriptionMethod, String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppColors.current
    Scaffold(containerColor = colors.ground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("戻る", style = MaterialTheme.typography.bodySmall, color = colors.signal)
            }
            Text("文字起こしの方式", style = MaterialTheme.typography.titleLarge, color = colors.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "Groq Whisper か Gemini で失敗が続いたら、その回は端末の音声認識に切り替えて続けます。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.graphite,
            )
            Spacer(Modifier.height(24.dp))

            MethodOption(
                method = TranscriptionMethod.GROQ,
                selected = state.method == TranscriptionMethod.GROQ,
                description = "Groq の無料枠で文字にします。送った音声は学習に使われません。API キーが要ります。",
                onSelect = onMethodChange,
            )
            if (state.method == TranscriptionMethod.GROQ) {
                CloudSettings(TranscriptionMethod.GROQ, state.groqKeySaved, state.groqModel, onSaveKey, onClearKey, onSaveModel)
            }

            MethodOption(
                method = TranscriptionMethod.GEMINI,
                selected = state.method == TranscriptionMethod.GEMINI,
                description = "Google の無料枠で文字にします。効果音や BGM を書かない指示に従えます。" +
                    "無料枠では、送った音声と結果が Google の改善に使われ、人が読むことがあります。API キーが要ります。",
                onSelect = onMethodChange,
            )
            if (state.method == TranscriptionMethod.GEMINI) {
                CloudSettings(TranscriptionMethod.GEMINI, state.geminiKeySaved, state.geminiModel, onSaveKey, onClearKey, onSaveModel)
            }

            MethodOption(
                method = TranscriptionMethod.ON_DEVICE,
                selected = state.method == TranscriptionMethod.ON_DEVICE,
                description = if (state.onDeviceAvailable) {
                    "端末の中で文字にします。API キーは要らず、音声は端末の外に出ません。句点は付きません。"
                } else {
                    "この端末（Android 12 以前）では使えません。"
                },
                onSelect = onMethodChange,
            )
        }
    }
}

@Composable
private fun MethodOption(
    method: TranscriptionMethod,
    selected: Boolean,
    description: String,
    onSelect: (TranscriptionMethod) -> Unit,
) {
    val colors = LocalAppColors.current
    HorizontalDivider(color = colors.rule)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(method) })
            .padding(vertical = 12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.signal, unselectedColor = colors.graphite),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(method.label, style = MaterialTheme.typography.labelLarge, color = colors.ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.graphite)
        }
    }
}

/** B・A の API キーとモデルの名前。キーは伏せ字で入れ、保存したら画面には出さない。 */
@Composable
private fun CloudSettings(
    method: TranscriptionMethod,
    keySaved: Boolean,
    model: String,
    onSaveKey: (TranscriptionMethod, String) -> Unit,
    onClearKey: (TranscriptionMethod) -> Unit,
    onSaveModel: (TranscriptionMethod, String) -> Unit,
) {
    val colors = LocalAppColors.current
    var key by remember(method) { mutableStateOf("") }
    var modelDraft by remember(method, model) { mutableStateOf(model) }
    var modelError by remember(method) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(12.dp)

    Column(Modifier.padding(start = 40.dp, bottom = 16.dp)) {
        Text(
            if (keySaved) "API キー: 保存済み" else "API キー: まだありません",
            style = MaterialTheme.typography.bodySmall,
            color = if (keySaved) colors.ink else colors.alert,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            placeholder = { Text(if (keySaved) "新しいキーに替えるときだけ入れる" else "API キーを入れる", color = colors.graphite) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = shape,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(enabled = key.isNotEmpty(), onClick = {
                onSaveKey(method, key)
                key = ""
            }) {
                Text("キーを保存する", style = MaterialTheme.typography.bodySmall, color = colors.signal)
            }
            if (keySaved) {
                TextButton(onClick = { onClearKey(method) }) {
                    Text("キーを消す", style = MaterialTheme.typography.bodySmall, color = colors.alert)
                }
            }
        }

        Text("モデルの名前", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = modelDraft,
            onValueChange = {
                modelDraft = it.trim()
                modelError = null
            },
            isError = modelError != null,
            supportingText = modelError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = shape,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = modelDraft != model, onClick = {
            if (HttpFailures.isValidModel(modelDraft)) onSaveModel(method, modelDraft) else modelError = "英数字と「.」「-」「_」だけで入れてください"
        }) {
            Text("モデルの名前を保存する", style = MaterialTheme.typography.bodySmall, color = colors.signal)
        }
    }
}

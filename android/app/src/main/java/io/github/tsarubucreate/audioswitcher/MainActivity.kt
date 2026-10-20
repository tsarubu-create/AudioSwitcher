package io.github.tsarubucreate.audioswitcher

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.tsarubucreate.audioswitcher.PairingProtocol.AnswerOutcome
import io.github.tsarubucreate.audioswitcher.PairingProtocol.StartOutcome
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

/**
 * 接続先を入れて、ペアリングし、再生・停止する画面。再生そのものは [PlaybackService] が行う。
 * 鍵が無ければペアリングの画面、あれば再生の画面を出す。
 */
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private lateinit var keyStorage: KeyStorage
    private var paired by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)

    // 再生サービスが鍵を捨てたとき（PC がペアリングを解除したとき）にも、画面を切り替える。
    private val keyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPairing() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        keyStorage = KeyStorage(this)
        refreshPairing()
        keyStorage.registerListener(keyListener)
        setContent {
            MaterialTheme {
                MainScreen(
                    controller = controller,
                    paired = paired,
                    notice = notice,
                    initialAddress = prefs.getString(KEY_ADDRESS, "") ?: "",
                    onAddressSaved = { prefs.edit { putString(KEY_ADDRESS, it) } },
                    onPaired = { keyStorage.save(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        keyStorage.unregisterListener(keyListener)
        super.onDestroy()
    }

    private fun refreshPairing() {
        paired = keyStorage.hasKey()
        notice = keyStorage.notice()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                controller = try {
                    future.get()
                } catch (e: Exception) {
                    Log.e(TAG, "再生サービスに繋げなかった", e)
                    null
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS_NAME = "settings"
        const val KEY_ADDRESS = "address"
    }
}

/** 画面に出す再生の状態。 */
private data class PlayerUiState(
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val errorName: String? = null,
)

private fun Player.toUiState() = PlayerUiState(
    playWhenReady = playWhenReady,
    isPlaying = isPlaying,
    playbackState = playbackState,
    errorName = playerError?.errorCodeName,
)

@Composable
private fun MainScreen(
    controller: MediaController?,
    paired: Boolean,
    notice: String?,
    initialAddress: String,
    onAddressSaved: (String) -> Unit,
    onPaired: (String) -> Unit,
) {
    var address by remember { mutableStateOf(initialAddress) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf(PlayerUiState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        state = controller.toUiState()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = player.toUiState()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AudioSwitcher（試作）", style = MaterialTheme.typography.titleLarge)
            if (!paired && notice != null) {
                Text(notice, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    addressError = null
                },
                label = { Text("PC の IP:ポート") },
                placeholder = { Text("192.168.1.5:${StreamAddress.DEFAULT_PORT}") },
                isError = addressError != null,
                supportingText = { addressError?.let { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!paired) {
                PairingSection(
                    address = address,
                    onInvalidAddress = { addressError = it },
                    onPaired = {
                        onAddressSaved(address.trim())
                        onPaired(it)
                    },
                )
                return@Column
            }

            Text(statusText(controller, state))

            val playing = state.playWhenReady
            Button(
                enabled = controller != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val c = controller ?: return@Button
                    if (playing) {
                        c.pause()
                        c.stop()
                        return@Button
                    }
                    val url = StreamAddress.toStreamUrl(address)
                    if (url == null) {
                        addressError = ADDRESS_HINT
                        return@Button
                    }
                    onAddressSaved(address.trim())
                    c.setMediaItem(
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("PC の音")
                                    .setArtist(address.trim())
                                    .build(),
                            )
                            .build(),
                    )
                    c.prepare()
                    c.play()
                },
            ) {
                Text(if (playing) "停止" else "再生")
            }
        }
    }
}

private fun statusText(controller: MediaController?, state: PlayerUiState): String = when {
    controller == null -> "再生サービスに繋いでいます…"
    state.playWhenReady && state.errorName != null -> "PC に繋がりません。繋ぎ直しています（${state.errorName}）"
    state.playWhenReady && state.playbackState == Player.STATE_ENDED -> "PC が配信を終えました。繋ぎ直しています"
    state.isPlaying -> "再生中"
    state.playWhenReady -> "PC に繋いでいます…"
    else -> "停止中"
}

private const val ADDRESS_HINT = "「192.168.1.5:48100」の形で入れてください"

/** ペアリングの進み具合。 */
private sealed interface PairingPhase {
    data object Idle : PairingPhase
    data object Working : PairingPhase
    data class Choosing(val pairingId: String, val candidates: List<String>) : PairingPhase
}

/** PC に出ている2桁の数字と同じものを、5つの候補から選ぶ（docs/構成.md の 2.3）。 */
@Composable
private fun PairingSection(address: String, onInvalidAddress: (String) -> Unit, onPaired: (String) -> Unit) {
    var phase by remember { mutableStateOf<PairingPhase>(PairingPhase.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun begin() {
        val baseUrl = StreamAddress.toBaseUrl(address) ?: return onInvalidAddress(ADDRESS_HINT)
        phase = PairingPhase.Working
        message = null
        scope.launch {
            when (val outcome = PairingClient(baseUrl).start()) {
                is StartOutcome.Started -> {
                    phase = PairingPhase.Choosing(outcome.pairingId, outcome.candidates)
                    message = "PC に出ている数字と同じものを選んでください（2分で無効になります）。"
                }
                StartOutcome.AlreadyPaired -> {
                    phase = PairingPhase.Idle
                    message = "PC は別の端末とペアリング済みです。PC のトレイで「ペアリングを解除」してから始めてください。"
                }
                StartOutcome.Busy -> {
                    phase = PairingPhase.Idle
                    message = "別の端末がペアリングの途中です。2分ほど待ってからやり直してください。"
                }
                is StartOutcome.Locked -> {
                    phase = PairingPhase.Idle
                    message = lockedMessage(outcome.lockedUntil)
                }
                is StartOutcome.Failed -> {
                    phase = PairingPhase.Idle
                    message = outcome.message
                }
            }
        }
    }

    fun choose(choosing: PairingPhase.Choosing, answer: String) {
        val baseUrl = StreamAddress.toBaseUrl(address) ?: return onInvalidAddress(ADDRESS_HINT)
        phase = PairingPhase.Working
        scope.launch {
            when (val outcome = PairingClient(baseUrl).answer(choosing.pairingId, answer)) {
                is AnswerOutcome.Paired -> onPaired(outcome.key)
                is AnswerOutcome.Wrong -> {
                    phase = PairingPhase.Choosing(outcome.pairingId, outcome.candidates)
                    message = "違いました。PC に新しい数字が出ています。もう一度選んでください。"
                }
                is AnswerOutcome.Locked -> {
                    phase = PairingPhase.Idle
                    message = lockedMessage(outcome.lockedUntil)
                }
                AnswerOutcome.Expired -> {
                    phase = PairingPhase.Idle
                    message = "時間切れです。もう一度「ペアリングを始める」を押してください。"
                }
                is AnswerOutcome.Failed -> {
                    phase = choosing
                    message = outcome.message
                }
            }
        }
    }

    message?.let { Text(it) }
    when (val current = phase) {
        is PairingPhase.Choosing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            current.candidates.forEach { candidate ->
                OutlinedButton(onClick = { choose(current, candidate) }, modifier = Modifier.weight(1f)) {
                    Text(candidate, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        else -> Button(
            enabled = phase == PairingPhase.Idle,
            onClick = { begin() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (phase == PairingPhase.Working) "PC と通信しています…" else "ペアリングを始める")
        }
    }
}

private fun lockedMessage(lockedUntil: String?): String {
    val until = lockedUntil?.let {
        try {
            OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        } catch (e: DateTimeParseException) {
            it
        }
    }
    val period = until?.let { "（$it まで）" } ?: ""
    return "続けて2回外れたため、PC がペアリングを受け付けない状態です$period。PC のトレイで「受け付けない状態を解く」を押すと、すぐに始められます。"
}

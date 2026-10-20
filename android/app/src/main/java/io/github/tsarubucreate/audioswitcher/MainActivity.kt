package io.github.tsarubucreate.audioswitcher

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** 接続先を入れて、再生・停止するだけの画面。再生そのものは [PlaybackService] が行う。 */
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                MainScreen(
                    controller = controller,
                    initialAddress = prefs.getString(KEY_ADDRESS, "") ?: "",
                    onAddressSaved = { prefs.edit { putString(KEY_ADDRESS, it) } },
                )
            }
        }
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
private fun MainScreen(controller: MediaController?, initialAddress: String, onAddressSaved: (String) -> Unit) {
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
                        addressError = "「192.168.1.5:48100」の形で入れてください"
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

package io.github.tsarubucreate.audioswitcher

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.tsarubucreate.audioswitcher.ui.AudioSwitcherTheme
import io.github.tsarubucreate.audioswitcher.ui.LocalAppColors
import io.github.tsarubucreate.audioswitcher.ui.RouteLine
import io.github.tsarubucreate.audioswitcher.ui.RouteState
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 接続先を入れて、ペアリングし、再生・停止する画面。再生そのものは [PlaybackService] が行う。
 * 鍵が無ければペアリングの画面、あれば再生の画面を出す。見た目の決めごとは docs/画面.md。
 */
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private lateinit var keyStorage: KeyStorage
    private lateinit var playbackStart: PlaybackStartStore
    private var paired by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var startedAt by mutableStateOf<Long?>(null)

    // 再生サービスが鍵を捨てたとき（PC がペアリングを解除したとき）にも、画面を切り替える。
    private val keyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPairing() }

    // 再生サービスが「再生を押した時刻」を記録・消去したら、経過時間の表示を合わせる。
    private val startListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> startedAt = playbackStart.startedAt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        keyStorage = KeyStorage(this)
        playbackStart = PlaybackStartStore(this)
        refreshPairing()
        startedAt = playbackStart.startedAt()
        keyStorage.registerListener(keyListener)
        playbackStart.registerListener(startListener)
        // 端末の設定で動きを減らしていたら、線の上の点を流さない。
        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            AudioSwitcherTheme {
                MainScreen(
                    controller = controller,
                    paired = paired,
                    notice = notice,
                    startedAt = startedAt,
                    reduceMotion = reduceMotion,
                    initialAddress = prefs.getString(KEY_ADDRESS, "") ?: "",
                    onAddressSaved = { prefs.edit { putString(KEY_ADDRESS, it) } },
                    onPaired = { keyStorage.save(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        keyStorage.unregisterListener(keyListener)
        playbackStart.unregisterListener(startListener)
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
    /** いったん失敗してから、まだ音が出ていない（[ConnectionHealth]）。 */
    val failing: Boolean = false,
)

private fun Player.toUiState(previous: PlayerUiState) = PlayerUiState(
    playWhenReady = playWhenReady,
    isPlaying = isPlaying,
    playbackState = playbackState,
    failing = ConnectionHealth.failing(previous.failing, playWhenReady, isPlaying, hasError = playerError != null),
)

private fun PlayerUiState.routeState(): RouteState = when {
    isPlaying -> RouteState.Playing
    playWhenReady && failing -> RouteState.Failing
    playWhenReady -> RouteState.Connecting
    else -> RouteState.Idle
}

@Composable
private fun MainScreen(
    controller: MediaController?,
    paired: Boolean,
    notice: String?,
    startedAt: Long?,
    reduceMotion: Boolean,
    initialAddress: String,
    onAddressSaved: (String) -> Unit,
    onPaired: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    var address by remember { mutableStateOf(initialAddress) }
    var state by remember { mutableStateOf(PlayerUiState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        state = controller.toUiState(PlayerUiState())
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = player.toUiState(state)
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    Scaffold(containerColor = colors.ground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("AudioSwitcher", style = MaterialTheme.typography.titleLarge, color = colors.ink)
            Spacer(Modifier.height(32.dp))
            if (paired) {
                PlayerSection(
                    controller = controller,
                    state = state,
                    startedAt = startedAt,
                    reduceMotion = reduceMotion,
                    address = address,
                    onAddressChange = {
                        address = it
                        onAddressSaved(it)
                    },
                )
            } else {
                PairingContent(
                    notice = notice,
                    address = address,
                    onAddressChange = { address = it },
                    onPaired = {
                        onAddressSaved(address.trim())
                        onPaired(it)
                    },
                )
            }
        }
    }
}

/** 再生の画面: PC から端末への線、状態、経過時間、再生・停止。 */
@Composable
private fun PlayerSection(
    controller: MediaController?,
    state: PlayerUiState,
    startedAt: Long?,
    reduceMotion: Boolean,
    address: String,
    onAddressChange: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val route = state.routeState()
    var editingAddress by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }

    // PC から端末への線。画面でいちばん目立たせる所。
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("PC", style = MaterialTheme.typography.labelLarge, color = colors.ink)
        RouteLine(route, reduceMotion, Modifier.weight(1f).padding(horizontal = 12.dp))
        Text("この端末", style = MaterialTheme.typography.labelLarge, color = colors.ink)
    }
    Text(address.ifBlank { "接続先が未設定" }, style = MaterialTheme.typography.bodySmall, color = colors.graphite)

    Spacer(Modifier.height(40.dp))
    Text(
        statusText(controller, state),
        style = MaterialTheme.typography.bodyLarge,
        color = if (route == RouteState.Failing) colors.alert else colors.ink,
    )
    if (state.playWhenReady && startedAt != null) {
        ElapsedClock(startedAt)
    }

    Spacer(Modifier.height(32.dp))
    val shape = RoundedCornerShape(12.dp)
    if (state.playWhenReady) {
        OutlinedButton(
            onClick = {
                controller?.pause()
                controller?.stop()
            },
            enabled = controller != null,
            shape = shape,
            // 罫の色の枠はダークの地でほとんど見えないので、枠は鉛・文字は墨にして押せるものだと分かるようにする。
            border = BorderStroke(1.dp, colors.graphite),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("停止", style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = {
                val c = controller ?: return@Button
                val url = StreamAddress.toStreamUrl(address)
                if (url == null) {
                    editingAddress = true
                    addressError = ADDRESS_HINT
                    return@Button
                }
                c.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("PC の音").setArtist(address.trim()).build())
                        .build(),
                )
                c.prepare()
                c.play()
            },
            enabled = controller != null,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.signal, contentColor = colors.onSignal),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("再生", style = MaterialTheme.typography.labelLarge)
        }
    }

    Spacer(Modifier.height(8.dp))
    if (!editingAddress) {
        TextButton(onClick = { editingAddress = true }) {
            Text("接続先を変える", style = MaterialTheme.typography.bodySmall, color = colors.signal)
        }
    } else {
        var draft by remember { mutableStateOf(address) }
        Spacer(Modifier.height(8.dp))
        AddressField(draft, addressError) {
            draft = it
            addressError = null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (StreamAddress.toStreamUrl(draft) == null) {
                    addressError = ADDRESS_HINT
                } else {
                    onAddressChange(draft.trim())
                    editingAddress = false
                }
            }) {
                Text("保存する", style = MaterialTheme.typography.bodySmall, color = colors.signal)
            }
            TextButton(onClick = {
                editingAddress = false
                addressError = null
            }) {
                Text("やめる", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
            }
        }
        Text("次に再生したときから、この接続先を使います。", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
    }
}

/** 「再生」を押してからの経過時間。秒の境目ちょうどに書き換える。 */
@Composable
private fun ElapsedClock(startedAt: Long) {
    val colors = LocalAppColors.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000 - (now - startedAt).mod(1000L))
        }
    }
    val text = ElapsedTime.format(now - startedAt)
    Text(
        text,
        style = MaterialTheme.typography.displaySmall,
        color = colors.ink,
        modifier = Modifier.semantics { contentDescription = "再生を始めてから $text" },
    )
}

@Composable
private fun AddressField(value: String, error: String?, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Text("接続先（PC の IP:ポート）", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("192.168.1.5:${StreamAddress.DEFAULT_PORT}", color = colors.graphite) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun statusText(controller: MediaController?, state: PlayerUiState): String = when {
    controller == null -> "再生サービスに繋いでいます"
    state.playWhenReady && state.failing -> "PC に繋がりません。繋ぎ直しています"
    state.playWhenReady && state.playbackState == Player.STATE_ENDED -> "PC が配信を終えました。繋ぎ直しています"
    state.isPlaying -> "再生中"
    state.playWhenReady -> "PC に繋いでいます"
    else -> "停止中"
}

private const val ADDRESS_HINT = "「192.168.1.5:48100」の形で入れてください"

/** ペアリングの画面: 知らせ、接続先、ペアリング。 */
@Composable
private fun PairingContent(notice: String?, address: String, onAddressChange: (String) -> Unit, onPaired: (String) -> Unit) {
    val colors = LocalAppColors.current
    var addressError by remember { mutableStateOf<String?>(null) }

    if (notice != null) {
        Text(notice, style = MaterialTheme.typography.bodyLarge, color = colors.alert)
        Spacer(Modifier.height(24.dp))
    }
    AddressField(address, addressError) {
        onAddressChange(it)
        addressError = null
    }
    Spacer(Modifier.height(24.dp))
    PairingSection(address = address, onInvalidAddress = { addressError = it }, onPaired = onPaired)
}

/** ペアリングの進み具合。 */
private sealed interface PairingPhase {
    data object Idle : PairingPhase
    data object Working : PairingPhase

    /** [shownAt] は数字が出た時刻（単調な時計）。残り時間をここから数える。 */
    data class Choosing(val pairingId: String, val candidates: List<String>, val shownAt: Long) : PairingPhase
}

/** PC に出ている2桁の数字と同じものを、5つの候補から選ぶ（docs/構成.md の 2.3）。 */
@Composable
private fun PairingSection(address: String, onInvalidAddress: (String) -> Unit, onPaired: (String) -> Unit) {
    val colors = LocalAppColors.current
    var phase by remember { mutableStateOf<PairingPhase>(PairingPhase.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsProblem by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 押したら入力欄の焦点を外し、キーボードで候補が隠れないようにする。
    val focusManager = LocalFocusManager.current

    fun say(text: String?, problem: Boolean = false) {
        message = text
        messageIsProblem = problem
    }

    fun begin() {
        val baseUrl = StreamAddress.toBaseUrl(address) ?: return onInvalidAddress(ADDRESS_HINT)
        focusManager.clearFocus()
        phase = PairingPhase.Working
        say(null)
        scope.launch {
            when (val outcome = PairingClient(baseUrl).start()) {
                is StartOutcome.Started -> {
                    phase = PairingPhase.Choosing(outcome.pairingId, outcome.candidates, SystemClock.elapsedRealtime())
                    say("PC に出た数字と同じものを選んでください。")
                }
                StartOutcome.AlreadyPaired -> {
                    phase = PairingPhase.Idle
                    say("PC は別の端末とペアリング済みです。PC のトレイで「ペアリングを解除」してから始めてください。", problem = true)
                }
                StartOutcome.Busy -> {
                    phase = PairingPhase.Idle
                    say("別の端末がペアリングの途中です。2分ほど待ってからやり直してください。", problem = true)
                }
                is StartOutcome.Locked -> {
                    phase = PairingPhase.Idle
                    say(lockedMessage(outcome.lockedUntil), problem = true)
                }
                is StartOutcome.Failed -> {
                    phase = PairingPhase.Idle
                    say(outcome.message, problem = true)
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
                    phase = PairingPhase.Choosing(outcome.pairingId, outcome.candidates, SystemClock.elapsedRealtime())
                    say("違いました。PC に新しい数字が出ています。もう一度選んでください。", problem = true)
                }
                is AnswerOutcome.Locked -> {
                    phase = PairingPhase.Idle
                    say(lockedMessage(outcome.lockedUntil), problem = true)
                }
                AnswerOutcome.Expired -> {
                    phase = PairingPhase.Idle
                    say("時間切れです。もう一度「ペアリングを始める」を押してください。", problem = true)
                }
                is AnswerOutcome.Failed -> {
                    phase = choosing
                    say(outcome.message, problem = true)
                }
            }
        }
    }

    message?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = if (messageIsProblem) colors.alert else colors.ink)
        Spacer(Modifier.height(16.dp))
    }
    when (val current = phase) {
        is PairingPhase.Choosing -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                current.candidates.forEach { candidate ->
                    CandidateTile(candidate, Modifier.weight(1f)) { choose(current, candidate) }
                }
            }
            Spacer(Modifier.height(12.dp))
            RemainingTime(current.shownAt)
        }
        else -> Button(
            enabled = phase == PairingPhase.Idle,
            onClick = { begin() },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.signal, contentColor = colors.onSignal),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (phase == PairingPhase.Working) "PC と通信しています" else "ペアリングを始める", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 候補の数字の四角いタイル。PC の右下のポップアップの数字と見比べやすいよう、等幅の数字で出す。 */
@Composable
private fun CandidateTile(number: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(1.dp, colors.rule, shape)
            .clickable(role = Role.Button, onClickLabel = "$number を選ぶ", onClick = onClick),
    ) {
        Text(number, style = MaterialTheme.typography.titleMedium, color = colors.ink)
    }
}

/** 数字の残り時間（PC と同じく 2:00 から減る）。 */
@Composable
private fun RemainingTime(shownAt: Long) {
    val colors = LocalAppColors.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(shownAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val leftMs = (CODE_LIFETIME_MS - (now - shownAt)).coerceAtLeast(0)
    // 端数は切り上げる（PC のポップアップと同じ書き方）。
    val text = ElapsedTime.format(((leftMs + 999) / 1000) * 1000)
    Text("残り $text", style = MaterialTheme.typography.bodySmall, color = colors.signal)
}

private const val CODE_LIFETIME_MS = 2 * 60 * 1000L

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

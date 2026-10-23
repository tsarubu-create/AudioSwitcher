package io.github.tsarubucreate.audioswitcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import io.github.tsarubucreate.audioswitcher.ui.TranscriptionSection
import io.github.tsarubucreate.audioswitcher.ui.TranscriptionUiState
import io.github.tsarubucreate.audioswitcher.ui.TranscriptionSettingsScreen
import io.github.tsarubucreate.audioswitcher.ui.TranscriptionSettingsUiState
import io.github.tsarubucreate.audioswitcher.transcription.GroqLimits
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionMethod
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionSettings
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
    private lateinit var transcriptionSettings: TranscriptionSettings
    private var transcription by mutableStateOf(TranscriptionUiState())
    private var methodSettings by mutableStateOf(TranscriptionSettingsUiState())
    private var showMethodSettings by mutableStateOf(false)

    // 画面の右上の切り替えで選んだ色。一度も選んでいなければ null（端末の設定に合わせる。docs/画面.md）。
    private var darkTheme by mutableStateOf<Boolean?>(null)

    // 音の切り替え（PC の音 / 端末の音。docs/構成.md の 2.7）と、端末の音の取り込みの状態。
    private var deviceSource by mutableStateOf(false)
    private lateinit var captureState: CaptureState
    private var captureStartedAt by mutableStateOf<Long?>(null)
    private var captureProblem by mutableStateOf<String?>(null)
    private val captureListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshCapture() }

    // ホーム画面のウィジェットで音を切り替えたら、画面にも合わせる。
    private lateinit var appSettings: AppSettings
    private val appSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        deviceSource = appSettings.deviceSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    // 端末の音: 録音の許可 → Android の画面のキャプチャの確かめ → 取り込みサービスを始める。
    private val requestRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askCaptureConsent() else captureState.problem = "録音の許可が無いので、端末の音を取り込めません"
    }
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            captureState.problem = "画面のキャプチャが許可されなかったので、始めませんでした"
            return@registerForActivityResult
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data),
        )
    }

    // 再生サービスが文字起こしの状態を書いたら、画面に出し直す。
    private val transcriptionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshTranscription() }

    // 保存先のフォルダを選ぶ（Storage Access Framework）。選んだフォルダへの読み書きの許可を覚えておく。
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        transcriptionSettings.folder = uri
    }

    // 再生サービスが鍵を捨てたとき（PC がペアリングを解除したとき）にも、画面を切り替える。
    private val keyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPairing() }

    // 再生サービスが「再生を押した時刻」を記録・消去したら、経過時間の表示を合わせる。
    private val startListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> startedAt = playbackStart.startedAt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        appSettings = settings
        settings.registerListener(appSettingsListener)
        darkTheme = settings.darkTheme
        deviceSource = settings.deviceSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        captureState = CaptureState(this)
        refreshCapture()
        captureState.registerListener(captureListener)
        keyStorage = KeyStorage(this)
        playbackStart = PlaybackStartStore(this)
        refreshPairing()
        startedAt = playbackStart.startedAt()
        keyStorage.registerListener(keyListener)
        playbackStart.registerListener(startListener)
        transcriptionSettings = TranscriptionSettings(this)
        refreshTranscription()
        transcriptionSettings.registerListener(transcriptionListener)
        transcriptionSettings.groqKey.registerListener(transcriptionListener)
        transcriptionSettings.geminiKey.registerListener(transcriptionListener)
        // 端末の設定で動きを減らしていたら、線の上の点を流さない。
        val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            val dark = darkTheme ?: isSystemInDarkTheme()
            // 上の帯（時計など）の文字の色も、選んだ色に合わせる。
            LaunchedEffect(dark) { applySystemBars(dark) }
            AudioSwitcherTheme(dark = dark) {
                // 方式の画面（文字起こしの欄の「方式を変える」から開く）。戻るで再生の画面へ戻る。
                BackHandler(enabled = showMethodSettings) { showMethodSettings = false }
                if (showMethodSettings) {
                    TranscriptionSettingsScreen(
                        state = methodSettings,
                        onMethodChange = { transcriptionSettings.method = it },
                        onSaveKey = { method, key -> secretFor(method)?.save(key) },
                        onClearKey = { method -> secretFor(method)?.clear() },
                        onSaveModel = { method, model ->
                            when (method) {
                                TranscriptionMethod.GROQ -> transcriptionSettings.groqModel = model
                                TranscriptionMethod.GEMINI -> transcriptionSettings.geminiModel = model
                                TranscriptionMethod.ON_DEVICE -> Unit
                            }
                        },
                        onBack = { showMethodSettings = false },
                    )
                    return@AudioSwitcherTheme
                }
                MainScreen(
                    controller = controller,
                    paired = paired,
                    notice = notice,
                    startedAt = startedAt,
                    reduceMotion = reduceMotion,
                    initialAddress = settings.address,
                    onAddressSaved = { settings.address = it },
                    onPaired = { keyStorage.save(it) },
                    onNoticeDone = { keyStorage.clearNotice() },
                    transcription = transcription,
                    onTranscriptionEnabledChange = { enabled ->
                        transcriptionSettings.enabled = enabled
                        if (enabled && transcriptionSettings.folder == null) pickFolder.launch(VAULT_FOLDER_HINT)
                    },
                    onPickFolder = { pickFolder.launch(transcriptionSettings.folder ?: VAULT_FOLDER_HINT) },
                    onOpenMethodSettings = { showMethodSettings = true },
                    dark = dark,
                    onDarkChange = {
                        darkTheme = it
                        settings.darkTheme = it
                        AudioWidget.update(this)
                    },
                    device = DeviceUiState(
                        selected = deviceSource,
                        available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                        startedAt = captureStartedAt,
                        problem = captureProblem,
                        canStart = transcription.enabled && transcription.folderLabel != null,
                    ),
                    onSourceChange = {
                        deviceSource = it
                        settings.deviceSource = it
                        AudioWidget.update(this)
                    },
                    onDeviceStart = {
                        captureState.problem = null
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            askCaptureConsent()
                        } else {
                            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDeviceStop = { CaptureService.stop(this) },
                )
            }
        }
    }

    override fun onDestroy() {
        appSettings.unregisterListener(appSettingsListener)
        captureState.unregisterListener(captureListener)
        keyStorage.unregisterListener(keyListener)
        playbackStart.unregisterListener(startListener)
        transcriptionSettings.unregisterListener(transcriptionListener)
        transcriptionSettings.groqKey.unregisterListener(transcriptionListener)
        transcriptionSettings.geminiKey.unregisterListener(transcriptionListener)
        super.onDestroy()
    }

    /** 上の帯・下の帯の文字の色を、画面の色に合わせる（帯の地は透明のまま）。 */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun refreshCapture() {
        captureStartedAt = captureState.startedAt()
        captureProblem = captureState.problem
    }

    /** Android の画面のキャプチャの確かめを出す（端末の音を取り込むのに要る。始めるたびに出る）。 */
    private fun askCaptureConsent() {
        captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun refreshTranscription() {
        transcription = TranscriptionUiState(
            enabled = transcriptionSettings.enabled,
            folderLabel = transcriptionSettings.folder?.let(TranscriptionSettings::folderLabel),
            status = transcriptionSettings.status,
            statusIsProblem = transcriptionSettings.statusIsProblem,
            method = transcriptionSettings.method.label,
            lastResult = transcriptionSettings.lastResult,
            limitWarning = GroqLimits.warning(transcriptionSettings.groqDailyLimit),
        )
        methodSettings = TranscriptionSettingsUiState(
            method = transcriptionSettings.method,
            groqKeySaved = transcriptionSettings.groqKey.has(),
            geminiKeySaved = transcriptionSettings.geminiKey.has(),
            groqModel = transcriptionSettings.groqModel,
            geminiModel = transcriptionSettings.geminiModel,
            onDeviceAvailable = android.os.Build.VERSION.SDK_INT >= 33,
        )
    }

    private fun secretFor(method: TranscriptionMethod): SecretStore? = when (method) {
        TranscriptionMethod.GROQ -> transcriptionSettings.groqKey
        TranscriptionMethod.GEMINI -> transcriptionSettings.geminiKey
        TranscriptionMethod.ON_DEVICE -> null
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

        /** フォルダを選ぶ画面で最初に開く場所（Obsidian の Vault の中。docs/構成.md の 2.5）。 */
        val VAULT_FOLDER_HINT: Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents/SyncVault/30_Projects/Spaceまとめ",
        )
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
    onNoticeDone: () -> Unit,
    transcription: TranscriptionUiState,
    onTranscriptionEnabledChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onOpenMethodSettings: () -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    device: DeviceUiState,
    onSourceChange: (Boolean) -> Unit,
    onDeviceStart: () -> Unit,
    onDeviceStop: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("AudioSwitcher", style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.weight(1f))
                // 画面の色の切り替え（docs/画面.md）。
                Text("ダーク", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = dark,
                    onCheckedChange = onDarkChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.signal,
                        checkedThumbColor = colors.onSignal,
                        uncheckedTrackColor = colors.ground,
                        uncheckedBorderColor = colors.graphite,
                        uncheckedThumbColor = colors.graphite,
                    ),
                )
            }
            Spacer(Modifier.height(32.dp))
            if (paired) {
                SourceSwitch(
                    device = device,
                    // 再生中・取り込み中は切り替えない（同時には使わない。docs/構成.md の 2.7）。
                    locked = state.playWhenReady || device.startedAt != null,
                    onSourceChange = onSourceChange,
                )
                Spacer(Modifier.height(24.dp))
                if (device.selected) {
                    DeviceSection(device, reduceMotion, onDeviceStart, onDeviceStop)
                } else {
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
                }
                TranscriptionSection(transcription, onTranscriptionEnabledChange, onPickFolder, onOpenMethodSettings)
            } else {
                PairingContent(
                    notice = notice,
                    onNoticeDone = onNoticeDone,
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

/** 端末の音（docs/構成.md の 2.7）の状態。 */
private data class DeviceUiState(
    /** 「端末の音」を選んでいる。 */
    val selected: Boolean,
    /** この端末で使える（Android 10 以降）。 */
    val available: Boolean,
    /** 取り込みを始めた時刻。止まっていれば null。 */
    val startedAt: Long?,
    /** 止まった理由。無ければ null。 */
    val problem: String?,
    /** 文字起こしが「入」で、保存先がある。 */
    val canStart: Boolean,
)

/** 音の切り替え（PC の音 / 端末の音。docs/画面.md）。 */
@Composable
private fun SourceSwitch(device: DeviceUiState, locked: Boolean, onSourceChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = colors.signal,
        selectedLabelColor = colors.onSignal,
        labelColor = colors.graphite,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !device.selected,
            onClick = { onSourceChange(false) },
            enabled = !locked,
            label = { Text("PC の音", style = MaterialTheme.typography.bodySmall) },
            colors = chipColors,
        )
        FilterChip(
            selected = device.selected,
            onClick = { onSourceChange(true) },
            enabled = !locked && device.available,
            label = { Text("端末の音", style = MaterialTheme.typography.bodySmall) },
            colors = chipColors,
        )
    }
    if (!device.available) {
        Text("端末の音は Android 10 以降で使えます", style = MaterialTheme.typography.bodySmall, color = colors.graphite)
    }
}

/** 端末の音の画面: X から文字起こしへの線、状態、経過時間、再生・停止（docs/画面.md）。 */
@Composable
private fun DeviceSection(device: DeviceUiState, reduceMotion: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = LocalAppColors.current
    val running = device.startedAt != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("X", style = MaterialTheme.typography.labelLarge, color = colors.ink)
        RouteLine(if (running) RouteState.Playing else RouteState.Idle, reduceMotion, Modifier.weight(1f).padding(horizontal = 12.dp))
        Text("文字起こし", style = MaterialTheme.typography.labelLarge, color = colors.ink)
    }
    Text("X のアプリの音だけを取り込みます", style = MaterialTheme.typography.bodySmall, color = colors.graphite)

    Spacer(Modifier.height(40.dp))
    val status = when {
        running -> "取り込み中"
        device.problem != null -> device.problem
        !device.canStart -> "文字起こしを入にして、保存先を選んでください"
        else -> "停止中"
    }
    Text(
        status,
        style = MaterialTheme.typography.bodyLarge,
        color = if (!running && device.problem != null) colors.alert else colors.ink,
    )
    device.startedAt?.let { ElapsedClock(it) }

    Spacer(Modifier.height(32.dp))
    val shape = RoundedCornerShape(12.dp)
    if (running) {
        OutlinedButton(
            onClick = onStop,
            shape = shape,
            border = BorderStroke(1.dp, colors.graphite),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("停止", style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = onStart,
            enabled = device.canStart,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.signal, contentColor = colors.onSignal),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("再生", style = MaterialTheme.typography.labelLarge)
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
            onClick = { controller?.let(PcPlayback::stop) },
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
                if (!PcPlayback.start(c, address)) {
                    editingAddress = true
                    addressError = ADDRESS_HINT
                }
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
private fun PairingContent(
    notice: String?,
    onNoticeDone: () -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
    onPaired: (String) -> Unit,
) {
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
    // 「ペアリングを始める」を押したら、「解除されました」の知らせは役目を終えたので消す
    // （赤い知らせが2つ並ばないように。docs/画面.md）。
    PairingSection(address = address, onInvalidAddress = { addressError = it }, onBegin = onNoticeDone, onPaired = onPaired)
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
private fun PairingSection(address: String, onInvalidAddress: (String) -> Unit, onBegin: () -> Unit, onPaired: (String) -> Unit) {
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
        onBegin()
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

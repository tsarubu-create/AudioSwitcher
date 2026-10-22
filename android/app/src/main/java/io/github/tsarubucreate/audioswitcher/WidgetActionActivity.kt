package io.github.tsarubucreate.audioswitcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.tsarubucreate.audioswitcher.transcription.TranscriptionSettings

/**
 * ホーム画面のウィジェットの丸いボタンから一瞬だけ開く、見えない画面（docs/画面.md）。
 *
 * 再生の仕組み（MediaController）と、Android の画面のキャプチャの確かめは、画面からでないと動かしにくいので、
 * ウィジェットはこの画面を開き、ここで始める・止める。済んだらすぐ閉じる。
 * ほかの設定（接続先・文字起こし・音の切り替え）は、アプリで最後に選んだものを使う。
 * 始められないとき（ペアリングしていない、接続先が無い、端末の音で文字起こしが切・保存先が無い）は、アプリの画面を開く。
 */
class WidgetActionActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val requestRecordAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            askCaptureConsent()
        } else {
            CaptureState(this).problem = "録音の許可が無いので、端末の音を取り込めません"
            finish()
        }
    }

    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data),
            )
        } else {
            CaptureState(this).problem = "画面のキャプチャが許可されなかったので、始めませんでした"
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 確かめの画面から戻ってきたとき（作り直し）は、もう一度始めない。
        if (savedInstanceState != null) return
        when {
            CaptureState(this).startedAt() != null -> {
                CaptureService.stop(this)
                finish()
            }
            PlaybackStartStore(this).startedAt() != null -> withController { player ->
                PcPlayback.stop(player)
                finishAfter(player, waitForPlaying = false)
            }
            AppSettings(this).deviceSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startDevice()
            else -> startPc()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }

    private fun startDevice() {
        val transcription = TranscriptionSettings(this)
        if (!transcription.enabled || transcription.folder == null) return openApp()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askCaptureConsent()
        } else {
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startPc() {
        val address = AppSettings(this).address
        if (!KeyStorage(this).hasKey() || StreamAddress.toStreamUrl(address) == null) return openApp()
        withController { player ->
            if (!PcPlayback.start(player, address)) return@withController openApp()
            finishAfter(player, waitForPlaying = true)
        }
    }

    private fun askCaptureConsent() {
        captureConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    /** 再生サービスに繋いでから [action] を行う。繋げなければアプリの画面を開く。 */
    private fun withController(action: (Player) -> Unit) {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = try {
                    future.get()
                } catch (e: Exception) {
                    Log.e(TAG, "再生サービスに繋げなかった", e)
                    return@addListener openApp()
                }
                action(controller)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /**
     * 閉じる。始めたときは、音が出るまで（最長 [START_WAIT_MS]）待ってから閉じる
     * （再生サービスが前景になる前に繋ぎを切ると、サービスが終わってしまうことがあるため）。
     */
    private fun finishAfter(player: Player, waitForPlaying: Boolean) {
        if (!waitForPlaying || player.isPlaying) return finish()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) finish()
            }
        })
        handler.postDelayed({ finish() }, START_WAIT_MS)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val TAG = "WidgetAction"
        const val START_WAIT_MS = 10_000L
    }
}

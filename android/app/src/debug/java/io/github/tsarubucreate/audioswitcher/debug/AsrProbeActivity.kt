package io.github.tsarubucreate.audioswitcher.debug

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

/**
 * 端末の中の音声認識（文字起こしの C 案）が、この端末で使えるかを確かめる画面（Debug 版だけ）。
 *
 * adb から次のように開く（音声は 16kHz・16bit・モノラルの生の PCM を、アプリの外部ファイル用のフォルダに置く）。
 * `adb shell am start -n <パッケージ>/.debug.AsrProbeActivity --es mode ondevice --ez segmented true`
 *
 * 結果は画面と logcat（タグ AsrProbe）に出す。
 * 外から音を流し込む口（EXTRA_AUDIO_SOURCE）が Android 13（API 33）からなので、それより前の端末では使わない。
 */
class AsrProbeActivity : Activity() {
    private lateinit var output: TextView
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 96, 32, 32) }
        setContentView(ScrollView(this).apply { addView(output) })

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            log("Android 13 より前なので確かめられない（API ${Build.VERSION.SDK_INT}）")
            return
        }

        probe()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probe() {
        val mode = intent.getStringExtra("mode") ?: "ondevice"
        val segmented = intent.getBooleanExtra("segmented", true)
        val file = File(getExternalFilesDir(null), intent.getStringExtra("file") ?: "ja-test.pcm")

        log("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）、機種 ${Build.MODEL}")
        log("音声認識がある: ${SpeechRecognizer.isRecognitionAvailable(this)}")
        log("端末の中の音声認識がある: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)}")
        log("方式=$mode、区切りながら認識=$segmented、音声=${file.path}（${file.length()} バイト）")

        if (mode == "ondevice") {
            checkSupportThenRecognize(file, segmented)
        } else {
            recognize(file, SpeechRecognizer.createSpeechRecognizer(this), segmented = segmented)
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupportThenRecognize(file: File, segmented: Boolean) {
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        checker.checkRecognitionSupport(
            languageIntent(),
            mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    log("入っている言語（端末の中）: ${support.installedOnDeviceLanguages}")
                    log("入れられる言語（端末の中）: ${support.supportedOnDeviceLanguages}")
                    log("入れている途中の言語: ${support.pendingOnDeviceLanguages}")
                    log("ネットを使う言語: ${support.onlineLanguages}")
                    // 調べた認識器をそのまま使う（すぐ片付けて別のを作ると、片付けの最中で失敗する）。
                    recognize(file, checker, segmented = segmented)
                }

                override fun onError(error: Int) {
                    log("言語の対応を確かめられなかった: ${errorName(error)}")
                    recognize(file, checker, segmented = segmented)
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognize(file: File, created: SpeechRecognizer, segmented: Boolean) {
        if (!file.exists()) {
            log("音声のファイルが無い: ${file.path}")
            return
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val intent = languageIntent().apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            if (segmented) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }

        recognizer = created
        val startedAt = System.nanoTime()
        fun elapsed() = "%.1f 秒".format((System.nanoTime() - startedAt) / 1e9)

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = log("[${elapsed()}] 聞き取りの準備ができた")
            override fun onBeginningOfSpeech() = log("[${elapsed()}] 話し始めを検出")
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = log("[${elapsed()}] 話し終わりを検出")
            override fun onError(error: Int) {
                log("[${elapsed()}] 失敗: ${errorName(error)}")
                readSide.close()
            }

            override fun onResults(results: Bundle?) {
                log("[${elapsed()}] 結果: ${texts(results)}")
                readSide.close()
            }
            override fun onPartialResults(partialResults: Bundle?) = log("[${elapsed()}] 途中: ${texts(partialResults)}")
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) = log("[${elapsed()}] 区切りの結果: ${texts(segmentResults)}")
            override fun onEndOfSegmentedSession() {
                log("[${elapsed()}] 区切りながらの認識が終わった")
                readSide.close()
            }
        })
        // 読み口は認識サービスへ渡り終えるまで開いておく（startListening は依頼を後で送るため、すぐ閉じると切れる）。
        // 認識が終わったとき（結果・失敗・区切りながらの認識の終わり）に閉じる。
        created.startListening(intent)

        // 本番に近づけるため、実時間の速さ（0.1 秒ぶんずつ、0.1 秒おき）で書き込む。
        Thread {
            val bytesPer100ms = 16_000 * 2 / 10
            try {
                FileOutputStream(writeSide.fileDescriptor).use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(bytesPer100ms)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            Thread.sleep(100)
                        }
                    }
                }
                log("[${elapsed()}] 音声を書き終えて閉じた")
            } catch (e: Exception) {
                log("[${elapsed()}] 音声を書き込めなかった: $e")
            } finally {
                writeSide.close()
            }
        }.start()
    }

    private fun languageIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
    }

    private fun texts(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { "「$it」" } ?: "（無し）"

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread { output.append(message + "\n") }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットの時間切れ"
        SpeechRecognizer.ERROR_NETWORK -> "ネットの失敗"
        SpeechRecognizer.ERROR_AUDIO -> "音声の読み取りの失敗"
        SpeechRecognizer.ERROR_SERVER -> "サーバの失敗"
        SpeechRecognizer.ERROR_CLIENT -> "呼び出し側の失敗"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "話が無いまま時間切れ"
        SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れなかった"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識器が使用中"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "許可が足りない"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "要求が多すぎる"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "サーバとの接続が切れた"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "言語に対応していない"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "言語が使えない（入っていない）"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "対応を確かめられない"
        else -> "番号 $error"
    } + "（$error）"

    private companion object {
        const val TAG = "AsrProbe"
    }
}

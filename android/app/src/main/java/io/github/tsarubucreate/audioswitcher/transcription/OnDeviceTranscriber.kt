package io.github.tsarubucreate.audioswitcher.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.FileOutputStream
import java.io.IOException

/**
 * C: 端末の中の音声認識（docs/構成.md の 2.5）。外から流し込む口（EXTRA_AUDIO_SOURCE）が Android 13 からなので、それ以降で使う。
 *
 * 区切りながら認識し（EXTRA_SEGMENTED_SESSION）、区切りの結果を1つにつき1行として渡す（句点は付かないので足さない）。
 * 認識が途中で終わったり失敗したりしたら、続けている間は新しい区切りの認識を始め直す。
 *
 * 確かめで分かった注意（docs/構成.md の 2.5「作るときの注意」）:
 * - パイプの読み口は、認識が終わるまで閉じない。
 * - 認識器は1つを使い続ける（片付けている最中に次を始めると失敗する）。
 *
 * SpeechRecognizer は画面のスレッド（[main]）で扱う。[feed] は作業スレッドから呼ばれる。
 */
@RequiresApi(33)
class OnDeviceTranscriber(
    private val context: Context,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : Transcriber {
    override val method = "端末の音声認識"
    override val model = "android-ondevice"

    @Volatile private var running = false
    @Volatile private var output: FileOutputStream? = null
    private var listener: Transcriber.Listener? = null
    private var recognizer: SpeechRecognizer? = null
    private var readSide: ParcelFileDescriptor? = null
    private var writeSide: ParcelFileDescriptor? = null
    private val restart = Runnable { startSegment() }
    private val release = Runnable { releaseRecognizer() }

    override fun start(listener: Transcriber.Listener) {
        this.listener = listener
        running = true
        main.post {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                running = false
                listener.onError("この端末では、端末の中の音声認識が使えません")
                return@post
            }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                it.setRecognitionListener(Callbacks())
            }
            startSegment()
        }
    }

    override fun feed(samples: ShortArray, isSpeech: Boolean) {
        val out = output ?: return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        try {
            out.write(bytes)
        } catch (e: IOException) {
            // 認識を始め直している最中は書き口が閉じている。そのあいだの音は認識できない。
            Log.w(TAG, "認識へ音を渡せなかった（始め直しの最中）: ${e.message}")
        }
    }

    override fun stop() {
        running = false
        main.post {
            main.removeCallbacks(restart)
            // 書き口を閉じると、最後の区切りの結果を返してから認識が終わる。終わりを待って片付ける（長くても 3 秒）。
            closeWriteSide()
            main.postDelayed(release, RELEASE_TIMEOUT_MS)
        }
    }

    private fun startSegment() {
        val created = recognizer ?: return
        if (!running) return
        val pipe = ParcelFileDescriptor.createPipe()
        readSide = pipe[0]
        writeSide = pipe[1]
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmConverter.TARGET_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        created.startListening(intent)
        output = FileOutputStream(pipe[1].fileDescriptor)
    }

    /** 1回の区切りながらの認識が終わったとき（結果・失敗・終わりの知らせ）。 */
    private fun endSegment(restartDelayMs: Long) {
        closeWriteSide()
        readSide?.close()
        readSide = null
        if (running) {
            main.postDelayed(restart, restartDelayMs)
        } else {
            main.removeCallbacks(release)
            releaseRecognizer()
        }
    }

    private fun closeWriteSide() {
        output = null
        try {
            writeSide?.close()
        } catch (e: IOException) {
            Log.w(TAG, "書き口を閉じられなかった: ${e.message}")
        }
        writeSide = null
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
        readSide?.close()
        readSide = null
    }

    private fun emit(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) listener?.reserve()?.fulfill(listOf(text))
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onSegmentResults(segmentResults: Bundle) = emit(segmentResults)

        override fun onResults(results: Bundle?) {
            emit(results)
            endSegment(RESTART_DELAY_MS)
        }

        override fun onEndOfSegmentedSession() = endSegment(RESTART_DELAY_MS)

        override fun onError(error: Int) {
            Log.w(TAG, "端末の音声認識が失敗した（$error）")
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                -> {
                    running = false
                    listener?.onError("端末の中の音声認識で日本語が使えません（$error）")
                    endSegment(0)
                }
                // 聞き取れなかった・話が無かったなどは、始め直して続ける。
                else -> endSegment(ERROR_RESTART_DELAY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "OnDeviceTranscriber"
        const val RESTART_DELAY_MS = 200L
        const val ERROR_RESTART_DELAY_MS = 1_000L
        const val RELEASE_TIMEOUT_MS = 3_000L
    }
}

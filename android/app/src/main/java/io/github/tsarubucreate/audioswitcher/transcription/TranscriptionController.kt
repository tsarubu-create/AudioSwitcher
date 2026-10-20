package io.github.tsarubucreate.audioswitcher.transcription

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * 再生サービスの中で文字起こしを動かす。再生している音を横取りし（[tap]）、「再生」から「停止」までを1回として扱う。
 *
 * 横取りした音の変換・空白と途切れの検出・ファイルへの書き出しは、すべて文字起こし専用の作業スレッド1本で順に行う
 * （再生のスレッドや画面のスレッドで時間のかかることをしないため。順番も自然に守られる）。
 */
@OptIn(UnstableApi::class)
class TranscriptionController(private val context: Context, private val settings: TranscriptionSettings) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "transcription") }
    private val converter = PcmConverter()

    // 作業スレッドだけで触る。
    private var session: TranscriptionSession? = null

    @Volatile private var active = false

    /** ExoPlayer の音の出口の手前に挟む「T字の分かれ道」に渡す受け口。 */
    val tap: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        @Volatile private var sampleRate = 0
        @Volatile private var channels = 0
        @Volatile private var encoding = C.ENCODING_INVALID

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!active || sampleRate <= 0 || channels <= 0) return
            val samples = toShorts(buffer, encoding) ?: return
            val rate = sampleRate
            val count = channels
            worker.execute {
                val current = session ?: return@execute
                converter.configure(rate, count)
                current.onPcm(converter.convert(samples))
            }
        }
    }

    /** 「再生」を押したとき、または再生中に文字起こしを「入」にしたとき。画面のスレッドから呼ぶ。 */
    fun start() {
        if (active) return
        val folder = settings.folder
        when {
            !settings.enabled -> return
            folder == null -> return report("保存先のフォルダを選んでください", problem = true)
        }
        val transcriber = createTranscriber() ?: return
        val writer = TranscriptWriter(
            output = SafTranscriptOutput(context.contentResolver, folder!!),
            wallClock = { ZonedDateTime.now() },
            monotonicMs = { SystemClock.elapsedRealtime() },
            method = transcriber.method,
            model = transcriber.model,
            onStatus = { status ->
                // 停止のあとに届いた最後の行を書いたときは、状態を出し直さない。
                if (active) when (status) {
                    is TranscriptWriter.Status.Writing -> report("文字起こし中: ${status.fileName}", problem = false)
                    is TranscriptWriter.Status.Failing -> report("保存先に書けません: ${status.reason}", problem = true)
                }
            },
        )
        val assembler = TranscriptAssembler { line -> worker.execute { writer.writeLine(line) } }
        transcriber.start(object : Transcriber.Listener {
            override fun reserve() = assembler.reserve()

            override fun onError(message: String) = report(message, problem = true)
        })
        val created = TranscriptionSession(transcriber, assembler, monotonicMs = { SystemClock.elapsedRealtime() })
        worker.execute { session = created }
        active = true
        report("文字起こし中（まだ書いていない）", problem = false)
        Log.i(TAG, "文字起こしを始めた（${transcriber.method}）")
    }

    /** 「停止」したとき、または文字起こしを「切」にしたとき。 */
    fun stop() {
        if (!active) return
        active = false
        worker.execute {
            session?.finish()
            session = null
        }
        report(null, problem = false)
        Log.i(TAG, "文字起こしを止めた")
    }

    fun release() {
        stop()
        worker.shutdown()
    }

    /** 設定の方式で文字起こしを作る。B・A は、失敗が続いたら C に切り替えられるようにする。作れなければ状態を出して null。 */
    private fun createTranscriber(): Transcriber? {
        val onDevice: (() -> Transcriber)? = if (Build.VERSION.SDK_INT >= 33) ({ OnDeviceTranscriber(context) }) else null
        val primary: Transcriber = when (settings.method) {
            TranscriptionMethod.ON_DEVICE -> return onDevice?.invoke()
                ?: null.also { report("この端末（Android 12 以前）では端末の音声認識を使えません", problem = true) }
            TranscriptionMethod.GROQ -> GroqTranscriber(
                settings.groqKey.load() ?: return null.also { report("Groq の API キーがまだありません（方式を変える から入れてください）", problem = true) },
                settings.groqModel,
            )
            TranscriptionMethod.GEMINI -> GeminiTranscriber(
                settings.geminiKey.load() ?: return null.also { report("Gemini の API キーがまだありません（方式を変える から入れてください）", problem = true) },
                settings.geminiModel,
            )
        }
        return SwitchableTranscriber(primary, onDevice, worker)
    }

    private fun report(message: String?, problem: Boolean) {
        settings.status = message
        settings.statusIsProblem = problem
    }

    private companion object {
        const val TAG = "Transcription"

        /** 横取りした音を 16bit の PCM にする（ExoPlayer の出口は 16bit か float）。それ以外の形は扱わない。 */
        fun toShorts(buffer: ByteBuffer, encoding: Int): ShortArray? {
            val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            return when (encoding) {
                C.ENCODING_PCM_16BIT -> ShortArray(view.remaining() / 2).also { view.asShortBuffer().get(it) }
                C.ENCODING_PCM_FLOAT -> {
                    val floats = FloatArray(view.remaining() / 4).also { view.asFloatBuffer().get(it) }
                    ShortArray(floats.size) { i -> (floats[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
                }
                else -> null
            }
        }
    }
}

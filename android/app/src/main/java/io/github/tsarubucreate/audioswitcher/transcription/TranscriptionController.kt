package io.github.tsarubucreate.audioswitcher.transcription

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.github.tsarubucreate.audioswitcher.BuildConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 再生サービス（PC の音）と取り込みサービス（端末の音。docs/構成.md の 2.7）の中で文字起こしを動かす。
 * 音は、再生している音の横取り（[tap]）か、取り込んだ音（[feedPcm]）で受け取り、「再生」から「停止」までを1回として扱う。
 *
 * 横取りした音の変換・空白と途切れの検出・ファイルへの書き出しは、すべて文字起こし専用の作業スレッド1本で順に行う
 * （再生のスレッドや画面のスレッドで時間のかかることをしないため。順番も自然に守られる）。
 */
@OptIn(UnstableApi::class)
class TranscriptionController(private val context: Context, private val settings: TranscriptionSettings) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "transcription") }

    // 区間を送る役（B・A）。アプリで1本を使い回す（TranscriptionSender）。
    private val sender = TranscriptionSender.executor
    private val converter = PcmConverter()

    // 作業スレッドだけで触る。
    private var session: TranscriptionSession? = null
    private var writer: TranscriptWriter? = null

    // 画面のスレッドだけで触る。今の回の数え（前回の結果）と、始めた時刻の表示。
    private var tally: UsageTally? = null
    private var startedLabel = ""

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
            feedPcm(samples, sampleRate, channels)
        }
    }

    /** 16bit の PCM（[channels] の並び）を渡す。どのスレッドから呼んでもよい。文字起こしをしていなければ捨てる。 */
    fun feedPcm(samples: ShortArray, sampleRate: Int, channels: Int) {
        if (!active || sampleRate <= 0 || channels <= 0) return
        worker.execute {
            val current = session ?: return@execute
            converter.configure(sampleRate, channels)
            current.onPcm(converter.convert(samples))
        }
    }

    /**
     * 「再生」を押したとき、または再生中に文字起こしを「入」にしたとき。画面のスレッドから呼ぶ。
     * 始めた（または始めていた）ら true。「切」・保存先が無い・方式を作れないときは false。
     */
    fun start(): Boolean {
        if (active) return true
        val folder = settings.folder
        when {
            !settings.enabled -> return false
            folder == null -> return false.also { report("保存先のフォルダを選んでください", problem = true) }
        }
        val counting = UsageTally(monotonicMs = { SystemClock.elapsedRealtime() })
        val transcriber = createTranscriber(counting) ?: return false
        val writer = TranscriptWriter(
            output = SafTranscriptOutput(context.contentResolver, folder!!),
            wallClock = { ZonedDateTime.now() },
            monotonicMs = { SystemClock.elapsedRealtime() },
            method = transcriber.method,
            model = transcriber.model,
            partLengthMs = debugPartLengthMs() ?: TranscriptWriter.PART_LENGTH_MS,
            onStatus = { status ->
                // 停止のあとに届いた最後の行を書いたときは、状態を出し直さない。
                if (active) when (status) {
                    is TranscriptWriter.Status.Writing -> report("文字起こし中: ${status.fileName}", problem = false)
                    is TranscriptWriter.Status.Failing -> report("保存先に書けません: ${status.reason}", problem = true)
                }
            },
        )
        val assembler = TranscriptAssembler { line ->
            try {
                worker.execute { writer.writeLine(line) }
            } catch (e: RejectedExecutionException) {
                // 閉じたあと（release のあと）に届いた行。送る役の行は閉じる前に書き終えるので、端末の音声認識の遅れた行など。
                Log.w(TAG, "止めたあとに届いた行を書けなかった（${line.length}文字）", e)
            }
        }
        transcriber.start(object : Transcriber.Listener {
            override fun reserve() = assembler.reserve()

            override fun onError(message: String) = report(message, problem = true)

            override fun onNotice(message: String) = report(message, problem = false)
        })
        val created = TranscriptionSession(transcriber, assembler, monotonicMs = { SystemClock.elapsedRealtime() })
        worker.execute {
            session = created
            this.writer = writer
        }
        active = true
        tally = counting
        startedLabel = STARTED_LABEL.format(ZonedDateTime.now())
        report("文字起こし中（まだ書いていない）", problem = false)
        Log.i(TAG, "文字起こしを始めた（${transcriber.method}）")
        return true
    }

    /** 「停止」したとき、または文字起こしを「切」にしたとき。 */
    fun stop() {
        if (!active) return
        active = false
        worker.execute {
            session?.finish()
            session = null
            writer?.finish()
            writer = null
        }
        tally?.let { settings.lastResult = it.summary(startedLabel) }
        tally = null
        report(null, problem = false)
        Log.i(TAG, "文字起こしを止めた")
    }

    fun release() {
        stop()
        // 作業スレッドは、止めたときに送った最後の区間の行を書き終えてから閉じる。
        // stop() の後片付け（溜めた区間を送る役へ渡す）が作業スレッドで済んだあとに、送る役の列の最後へ「閉じる」を並べる
        // （送る役は1本で順に送るので、それまでの区間の行は、閉じる前に作業スレッドへ渡し終える）。送る役そのものは閉じない。
        worker.execute { sender.execute { worker.shutdown() } }
    }

    /**
     * Debug 版だけ: アプリの中の `files/debug_part_minutes` に分の数があれば、区切りをその長さにする
     * （1時間を待たずに区切りを確かめるため。docs/構成.md の 2.5）。無い・読めないときは null（1時間のまま）。
     */
    private fun debugPartLengthMs(): Long? {
        if (!BuildConfig.DEBUG) return null
        val file = File(context.filesDir, DEBUG_PART_MINUTES_FILE)
        if (!file.exists()) return null
        val minutes = try {
            file.readText().trim().toLongOrNull()
        } catch (e: IOException) {
            Log.w(TAG, "Debug: 区切りの長さを読めなかったので、1時間のままにする", e)
            return null
        }
        if (minutes == null || minutes <= 0) {
            Log.w(TAG, "Debug: 区切りの長さが正の整数でないので、1時間のままにする")
            return null
        }
        Log.i(TAG, "Debug: 区切りを${minutes}分にする")
        return minutes * 60_000
    }

    /**
     * 設定の方式で文字起こしを作る。作れなければ状態を出して null。
     * 失敗が続いたら、優先度の順（B > A > C）に下の方式へ切り替えられるようにする（docs/構成.md の 2.5「切り替えの順」）。
     * 下の方式は、使えるもの（A は API キーがあるとき、C は Android 13 以降）だけを並べる。
     */
    private fun createTranscriber(tally: UsageTally): Transcriber? {
        val onDevice: (() -> Transcriber)? = if (Build.VERSION.SDK_INT >= 33) ({ OnDeviceTranscriber(context) }) else null
        val gemini: (() -> Transcriber)? = settings.geminiKey.load()?.let { key ->
            val model = settings.geminiModel
            ({ GeminiTranscriber(key, model, sender) })
        }
        val chain: List<() -> Transcriber> = when (settings.method) {
            TranscriptionMethod.ON_DEVICE -> listOf(
                onDevice ?: return null.also { report("この端末（Android 12 以前）では端末の音声認識を使えません", problem = true) },
            )
            TranscriptionMethod.GROQ -> {
                val key = settings.groqKey.load()
                    ?: return null.also { report("Groq の API キーがまだありません（方式を変える から入れてください）", problem = true) }
                val model = settings.groqModel
                listOfNotNull({ GroqTranscriber(key, model, sender, onDailyLimit = ::onGroqDailyLimit) }, gemini, onDevice)
            }
            TranscriptionMethod.GEMINI -> listOfNotNull(
                gemini ?: return null.also { report("Gemini の API キーがまだありません（方式を変える から入れてください）", problem = true) },
                onDevice,
            )
        }
        return SwitchableTranscriber(chain, worker, monotonicMs = { SystemClock.elapsedRealtime() }, tally = tally)
    }

    /** Groq が返した1日の回数の上限を覚える（送る役のスレッドから呼ばれる）。変わったときだけログに書く。 */
    private fun onGroqDailyLimit(limit: Int) {
        if (settings.groqDailyLimit == limit) return
        Log.i(TAG, "Groq の1日の回数の上限: $limit（前は ${settings.groqDailyLimit ?: "未記録"}）")
        settings.groqDailyLimit = limit
    }

    private fun report(message: String?, problem: Boolean) {
        settings.status = message
        settings.statusIsProblem = problem
    }

    private companion object {
        const val TAG = "Transcription"
        val STARTED_LABEL: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
        const val DEBUG_PART_MINUTES_FILE = "debug_part_minutes"

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

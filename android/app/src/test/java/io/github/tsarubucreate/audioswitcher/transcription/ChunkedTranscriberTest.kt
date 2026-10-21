package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class ChunkedTranscriberTest {
    private val written = ArrayList<String>()
    private val assembler = TranscriptAssembler { written.add(it) }
    private val fallbacks = ArrayList<String>()
    private val errors = ArrayList<String>()
    private val sleeps = ArrayList<Long>()
    private val logs = ArrayList<String>()
    private var clock = 0L
    private val listener = object : Transcriber.Listener {
        override fun reserve() = assembler.reserve()
        override fun onError(message: String) {
            errors.add(message)
        }
        override fun onFallback(reason: String) {
            fallbacks.add(reason)
        }
    }

    @Test
    fun 区間を送り_返ってきた文を句点で改行して書く() {
        val transcriber = FakeTranscriber(listOf(Result.success("こんにちは。元気です。")))
        transcriber.start(listener)

        transcriber.feed(speech(2_000), isSpeech = true)
        transcriber.stop()

        assertEquals(listOf("こんにちは。", "元気です。"), written)
        assertEquals(1, transcriber.calls)
    }

    @Test
    fun 失敗したら2回まで送り直し_成功すれば失敗の印は書かない() {
        val transcriber = FakeTranscriber(
            listOf(
                Result.failure(TranscriptionFailure("通信の失敗", retryable = true)),
                Result.failure(TranscriptionFailure("通信の失敗", retryable = true)),
                Result.success("届いた。"),
            ),
        )
        transcriber.start(listener)

        transcriber.feed(speech(2_000), isSpeech = true)
        transcriber.boundary()

        assertEquals(listOf("届いた。"), written)
        assertEquals(3, transcriber.calls)
        assertEquals(listOf(2_000L, 4_000L), sleeps)
    }

    @Test
    fun 無料枠の上限では示された待ち時間だけ待ち_長すぎれば諦める() {
        val transcriber = FakeTranscriber(
            listOf(
                Result.failure(HttpFailures.of(429, "7")),
                Result.failure(HttpFailures.of(429)),
                Result.success("届いた。"),
                Result.failure(HttpFailures.of(429, "3600")),
            ),
        )
        transcriber.start(listener)

        transcriber.feed(speech(2_000), isSpeech = true)
        transcriber.boundary()
        transcriber.feed(speech(1_000), isSpeech = true)
        transcriber.boundary()

        assertEquals(listOf("届いた。", "（1秒文字起こし失敗：無料枠の上限（429））"), written)
        assertEquals(listOf(7_000L, 30_000L), sleeps) // 示されなければ 30 秒。1時間は待たない
        assertEquals(4, transcriber.calls)
    }

    @Test
    fun 無料枠の上限が返るたびに_切り替えの数えに知らせる() {
        var clockForTally = 0L
        val tally = UsageTally(monotonicMs = { clockForTally })
        val primary = FakeTranscriber(listOf(Result.failure(HttpFailures.of(429, "1")), Result.success("届いた。")))
        val switchable = SwitchableTranscriber(listOf({ primary }), Executor { it.run() }, monotonicMs = { clock }, tally = tally)
        switchable.start(listener)

        switchable.feed(speech(2_000), isSpeech = true)
        switchable.boundary()
        clockForTally += 60_000

        assertEquals("前回 x から 1分: 偽の方式 100%、無料枠の上限 1回", tally.summary("x"))
    }

    @Test
    fun 止めても送る役は閉じず_次の回もそのまま送れる() {
        val sender = DirectExecutor()
        val first = FakeTranscriber(listOf(Result.success("一回目。")), sender)
        first.start(listener)
        first.feed(speech(2_000), isSpeech = true)
        first.stop()

        val second = FakeTranscriber(listOf(Result.success("二回目。")), sender)
        second.start(listener)
        second.feed(speech(2_000), isSpeech = true)
        second.stop()

        assertEquals(listOf("一回目。", "二回目。"), written)
        assertTrue(!sender.isShutdown)
    }

    @Test
    fun 送り直しても駄目なら失敗の印を書いて次へ進む() {
        val transcriber = FakeTranscriber(List(3) { Result.failure<String>(TranscriptionFailure("無料枠の上限（429）", retryable = true)) } + Result.success("次。"))
        transcriber.start(listener)

        transcriber.feed(speech(3_000), isSpeech = true)
        transcriber.boundary()
        transcriber.feed(speech(1_000), isSpeech = true)
        transcriber.boundary()

        assertEquals(listOf("（3秒文字起こし失敗：無料枠の上限（429））", "次。"), written)
    }

    @Test
    fun 送り直さない失敗はすぐ印を書く() {
        val transcriber = FakeTranscriber(listOf(Result.failure<String>(TranscriptionFailure("API キーが認められない（401）", retryable = false))))
        transcriber.start(listener)

        transcriber.feed(speech(1_000), isSpeech = true)
        transcriber.boundary()

        assertEquals(1, transcriber.calls)
        assertTrue(sleeps.isEmpty())
        assertEquals(listOf("（1秒文字起こし失敗：API キーが認められない（401））"), written)
    }

    @Test
    fun 思わぬ例外でも札を埋め_後の行を止めない() {
        val transcriber = FakeTranscriber(listOf(Result.failure<String>(IllegalStateException("壊れた")), Result.success("後。")))
        transcriber.start(listener)

        transcriber.feed(speech(1_000), isSpeech = true)
        transcriber.boundary()
        transcriber.feed(speech(1_000), isSpeech = true)
        transcriber.boundary()

        assertEquals(listOf("（1秒文字起こし失敗：想定外の失敗（IllegalStateException））", "後。"), written)
    }

    @Test
    fun 続けて3区間失敗したら切り替えを頼む_成功を挟めば数え直す() {
        val fail = Result.failure<String>(TranscriptionFailure("サーバの失敗（503）", retryable = false))
        val transcriber = FakeTranscriber(listOf(fail, fail, Result.success("成功。"), fail, fail, fail))
        transcriber.start(listener)

        repeat(6) {
            transcriber.feed(speech(1_000), isSpeech = true)
            transcriber.boundary()
        }

        assertEquals(listOf("サーバの失敗（503）"), fallbacks)
    }

    @Test
    fun 切り替えを頼まれたら_印を書いてから代わりの方式で続ける() {
        val primary = FakeTranscriber(List(3) { Result.failure<String>(TranscriptionFailure("通信の失敗", retryable = false)) })
        val fallback = RecordingTranscriber()
        val switchable = SwitchableTranscriber(listOf({ primary }, { fallback }), Executor { it.run() }, monotonicMs = { clock })
        switchable.start(listener)

        repeat(3) {
            switchable.feed(speech(1_000), isSpeech = true)
            switchable.boundary()
        }
        switchable.feed(speech(500), isSpeech = true)

        assertEquals("端末の音声認識（偽）", switchable.method)
        assertEquals("（文字起こしを端末の音声認識（偽）に切り替えた：通信の失敗）", written.last())
        assertTrue(fallback.started)
        assertEquals(8_000, fallback.fedSamples)
        assertEquals(1, errors.size)
    }

    @Test
    fun 代わりの方式が無ければ知らせだけ出して続ける() {
        val primary = FakeTranscriber(List(3) { Result.failure<String>(TranscriptionFailure("通信の失敗", retryable = false)) })
        val switchable = SwitchableTranscriber(listOf({ primary }), Executor { it.run() }, monotonicMs = { clock })
        switchable.start(listener)

        repeat(3) {
            switchable.feed(speech(1_000), isSpeech = true)
            switchable.boundary()
        }

        assertEquals("偽の方式", switchable.method)
        assertEquals(listOf("文字起こしの失敗が続いています：通信の失敗"), errors)
    }

    @Test
    fun 送るたびに何回目か_かかった時間_結果をログに残す() {
        val transcriber = FakeTranscriber(
            listOf(
                Result.failure(TranscriptionFailure("時間切れ", retryable = true)),
                Result.success("届いた。"),
            ),
        )
        transcriber.start(listener)

        transcriber.feed(speech(2_000), isSpeech = true)
        transcriber.stop()

        assertEquals(
            listOf(
                "偽の方式: 2秒の区間、1回目で失敗（1500ms、時間切れ）。2000ms 待って送り直す",
                "偽の方式: 2秒の区間、2回目で成功（1500ms、1行）",
            ),
            logs,
        )
    }

    @Test
    fun 声の少ない区間を捨てたらログに残す() {
        val transcriber = FakeTranscriber(emptyList())
        transcriber.start(listener)

        transcriber.feed(speech(300), isSpeech = true)
        transcriber.stop()

        assertEquals(0, transcriber.calls)
        assertEquals(listOf("偽の方式: 300msの区間を送らずに捨てた（声は300ms）"), logs)
    }

    private fun speech(ms: Long) = ShortArray((ms * 16).toInt()) { 1000 }

    /** 送る処理をその場で走らせ、返事は並べた順に返す。 */
    private inner class FakeTranscriber(results: List<Result<String>>, sender: Executor = DirectExecutor()) :
        ChunkedTranscriber(sender = sender, sleep = { sleeps.add(it) }, monotonicMs = { clock }, log = { logs.add(it) }) {
        private val queue = ArrayDeque(results)
        var calls = 0
        override val method = "偽の方式"
        override val model = "fake"

        override fun transcribe(wav: ByteArray): String {
            calls++
            clock += 1_500 // 返事に 1.5 秒かかったことにする
            return queue.removeFirst().getOrThrow()
        }
    }

    @Test
    fun 優先度の順に_次の方式へ_さらに次の方式へと切り替える() {
        val fail = Result.failure<String>(TranscriptionFailure("通信の失敗", retryable = false))
        val top = FakeTranscriber(List(3) { fail })
        val second = FakeTranscriber(List(3) { fail })
        val last = RecordingTranscriber()
        val switchable = SwitchableTranscriber(listOf({ top }, { second }, { last }), Executor { it.run() }, monotonicMs = { clock })
        switchable.start(listener)

        repeat(6) {
            switchable.feed(speech(1_000), isSpeech = true)
            switchable.boundary()
        }

        assertEquals("端末の音声認識（偽）", switchable.method)
        assertEquals(
            listOf("（文字起こしを偽の方式に切り替えた：通信の失敗）", "（文字起こしを端末の音声認識（偽）に切り替えた：通信の失敗）"),
            written.filter { it.startsWith("（文字起こしを") },
        )
        assertTrue(last.started)
    }

    @Test
    fun 下の方式に替えて10分たったら_選んだ方式に戻して試す() {
        val fail = Result.failure<String>(TranscriptionFailure("通信の失敗", retryable = false))
        val tops = ArrayDeque(listOf(FakeTranscriber(List(3) { fail }), FakeTranscriber(listOf(Result.success("戻った。")))))
        val fallback = RecordingTranscriber()
        val notices = ArrayList<String>()
        val switchable = SwitchableTranscriber(
            listOf({ tops.removeFirst() }, { fallback }),
            Executor { it.run() },
            monotonicMs = { clock },
        )
        switchable.start(object : Transcriber.Listener by listener {
            override fun onNotice(message: String) {
                notices.add(message)
            }
        })
        repeat(3) {
            switchable.feed(speech(1_000), isSpeech = true)
            switchable.boundary()
        }
        assertEquals("端末の音声認識（偽）", switchable.method)

        clock += 10 * 60 * 1000L - 1
        switchable.feed(speech(1_000), isSpeech = true)
        assertEquals("端末の音声認識（偽）", switchable.method) // まだ 10 分に満たない

        clock += 1
        switchable.feed(speech(1_000), isSpeech = true)
        switchable.boundary()

        assertEquals("偽の方式", switchable.method)
        assertEquals(listOf("（文字起こしを偽の方式に戻した）", "戻った。"), written.takeLast(2))
        assertEquals(listOf("偽の方式に戻して試しています"), notices)
    }

    private class RecordingTranscriber : Transcriber {
        var started = false
        var fedSamples = 0
        override val method = "端末の音声認識（偽）"
        override val model = "fake-ondevice"
        override fun start(listener: Transcriber.Listener) {
            started = true
        }
        override fun feed(samples: ShortArray, isSpeech: Boolean) {
            fedSamples += samples.size
        }
        override fun stop() = Unit
    }

    private class DirectExecutor : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {
            shutdown = true
        }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}

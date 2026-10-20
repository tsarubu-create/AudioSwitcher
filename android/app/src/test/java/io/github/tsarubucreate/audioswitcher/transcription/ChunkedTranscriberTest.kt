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
        val switchable = SwitchableTranscriber(primary, { fallback }, Executor { it.run() })
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
        val switchable = SwitchableTranscriber(primary, null, Executor { it.run() })
        switchable.start(listener)

        repeat(3) {
            switchable.feed(speech(1_000), isSpeech = true)
            switchable.boundary()
        }

        assertEquals("偽の方式", switchable.method)
        assertEquals(listOf("文字起こしの失敗が続いています：通信の失敗"), errors)
    }

    private fun speech(ms: Long) = ShortArray((ms * 16).toInt()) { 1000 }

    /** 送る処理をその場で走らせ、返事は並べた順に返す。 */
    private inner class FakeTranscriber(results: List<Result<String>>) :
        ChunkedTranscriber(sender = DirectExecutor(), sleep = { sleeps.add(it) }) {
        private val queue = ArrayDeque(results)
        var calls = 0
        override val method = "偽の方式"
        override val model = "fake"

        override fun transcribe(wav: ByteArray): String {
            calls++
            return queue.removeFirst().getOrThrow()
        }
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

package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionSessionTest {
    private var now = 0L
    private val written = ArrayList<String>()
    private val assembler = TranscriptAssembler { written.add(it) }
    private val transcriber = RecordingTranscriber()
    private val session = TranscriptionSession(transcriber, assembler, monotonicMs = { now })

    @Test
    fun 五秒以上の無音は声が戻ったときに空白の印を書く() {
        deliver(speechMs = 1_000)
        deliver(silenceMs = 6_400)
        deliver(speechMs = 1_000)

        assertEquals(listOf("（6秒空白）"), written)
    }

    @Test
    fun 五秒未満の無音は書かない() {
        deliver(speechMs = 1_000)
        deliver(silenceMs = 4_900)
        deliver(speechMs = 1_000)

        assertTrue(written.isEmpty())
    }

    @Test
    fun 雑音の一瞬では空白を終えない() {
        deliver(silenceMs = 3_000)
        deliver(speechMs = 40) // 2 フレームだけ
        deliver(silenceMs = 3_000)
        deliver(speechMs = 1_000)

        assertEquals(listOf("（6秒空白）"), written)
    }

    @Test
    fun 五秒以上音が届かなかったら_戻ったときに途切れの印を書く() {
        deliver(speechMs = 1_000)
        now += 12_300 // 音が届かない
        deliver(speechMs = 1_000)

        assertEquals(listOf("（12秒途切れ）"), written)
    }

    @Test
    fun 途切れの前の空白は途切れより先に書く() {
        deliver(silenceMs = 5_200)
        now += 8_000
        deliver(speechMs = 1_000)

        assertEquals(listOf("（5秒空白）", "（8秒途切れ）"), written)
    }

    @Test
    fun 停止したとき最後の空白を書き_文字起こしを止める() {
        deliver(speechMs = 500)
        deliver(silenceMs = 7_000)

        session.finish()

        assertEquals(listOf("（7秒空白）"), written)
        assertTrue(transcriber.stopped)
    }

    @Test
    fun 空白と途切れの印を書く前に_方式へ区切りを知らせる() {
        deliver(speechMs = 1_000)
        deliver(silenceMs = 6_000)
        deliver(speechMs = 1_000)
        now += 7_000
        deliver(speechMs = 1_000)

        assertEquals(listOf("（6秒空白）", "（7秒途切れ）"), written)
        assertEquals(2, transcriber.boundaries)
    }

    @Test
    fun 届いた音はすべて文字起こしへ渡し_声の有無を添える() {
        deliver(silenceMs = 100)
        deliver(speechMs = 100)

        assertEquals(listOf(false, true), transcriber.fed.map { it.second })
        assertEquals(3_200, transcriber.fed.sumOf { it.first })
    }

    /** 音の中の時間を進めながら、20ms ずつ渡す（時計も同じだけ進める）。 */
    private fun deliver(speechMs: Long = 0, silenceMs: Long = 0) {
        val ms = speechMs + silenceMs
        val samples = ShortArray((ms * 16).toInt()) { if (speechMs > 0) (if (it % 2 == 0) 8_000 else -8_000).toShort() else 0 }
        now += ms
        session.onPcm(samples)
    }

    private class RecordingTranscriber : Transcriber {
        val fed = ArrayList<Pair<Int, Boolean>>()
        var boundaries = 0
        var stopped = false
        override val method = "テスト"
        override val model = "test"
        override fun start(listener: Transcriber.Listener) = Unit
        override fun feed(samples: ShortArray, isSpeech: Boolean) {
            fed.add(samples.size to isSpeech)
        }
        override fun boundary() {
            boundaries++
        }
        override fun stop() {
            stopped = true
        }
    }
}

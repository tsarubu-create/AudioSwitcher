package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChunkingTest {
    private val chunker = SpeechChunker()

    @Test
    fun 十秒たまって間が来たら区切る() {
        assertNull(feed(speechMs = 9_000))
        assertNull(feed(silenceMs = 900)) // まだ 10 秒に満たない
        assertNull(feed(speechMs = 1_000))

        val chunk = feed(silenceMs = 800)

        assertEquals(9_000 + 900 + 1_000 + 800, ms(chunk!!))
    }

    @Test
    fun 六十秒に達したら間が無くても区切る() {
        var chunk: ShortArray? = null
        repeat(61) { chunk = chunk ?: feed(speechMs = 1_000) }

        assertEquals(60_000, ms(chunk!!))
    }

    @Test
    fun 声の前の無音は直前の0_3秒だけを含める() {
        assertNull(feed(silenceMs = 2_000))
        feed(speechMs = 1_000)

        assertEquals(1_300, ms(chunker.flush()!!))
    }

    @Test
    fun 声が合わせて0_5秒に満たない区間は送らずに捨てる() {
        feed(speechMs = 400) // 一時停止の雑音のような短い音
        feed(silenceMs = 300)

        assertNull(chunker.flush())
        // 捨てたあとは、次の区間を新しく始める（捨てた区間の音は混ぜない）。
        feed(speechMs = 600)
        assertEquals(600, ms(chunker.flush()!!))
    }

    @Test
    fun 声が無いまま区切っても何も返さない() {
        feed(silenceMs = 2_000)

        assertNull(chunker.flush())
    }

    @Test
    fun WAVの見出しは16kHz_16bit_モノラル() {
        val wav = Wav.encode(shortArrayOf(1, -1, 3))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(22).toInt()) // モノラル
        assertEquals(16_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(6, header.getInt(40)) // データのバイト数
        assertEquals(44 + 6, wav.size)
        assertEquals(-1, header.getShort(46).toInt())
    }

    @Test
    fun 返ってきた文は句点で改行する() {
        assertEquals(
            listOf("こんにちは。", "今日は晴れです。", "改行もある", "最後"),
            Sentences.lines(" こんにちは。今日は晴れです。改行もある\n最後 "),
        )
        assertEquals(emptyList<String>(), Sentences.lines("  \n "))
    }

    private fun feed(speechMs: Long = 0, silenceMs: Long = 0): ShortArray? {
        val ms = speechMs + silenceMs
        return chunker.add(ShortArray((ms * 16).toInt()) { 1 }, isSpeech = speechMs > 0)
    }

    private fun ms(samples: ShortArray) = samples.size / 16

    @Test
    fun 区切った音は足した順につながる() {
        val speech = ShortArray(16 * 600) { 1 }
        chunker.add(speech, isSpeech = true)
        chunker.add(shortArrayOf(3), isSpeech = false)

        assertArrayEquals(speech + shortArrayOf(3), chunker.flush())
    }
}

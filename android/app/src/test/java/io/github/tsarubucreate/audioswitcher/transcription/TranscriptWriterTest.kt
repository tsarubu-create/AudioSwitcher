package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

class TranscriptWriterTest {
    private var wall = ZonedDateTime.of(2026, 10, 20, 20, 45, 12, 0, ZoneId.of("Asia/Tokyo"))
    private var mono = 0L
    private val output = MemoryOutput()
    private val statuses = ArrayList<TranscriptWriter.Status>()
    private val writer = TranscriptWriter(
        output = output,
        wallClock = { wall },
        monotonicMs = { mono },
        method = "端末の音声認識",
        model = "android-ondevice",
        onStatus = { statuses.add(it) },
    )

    @Test
    fun 最初の行でファイルを作り_プロパティのあとに本文を書く() {
        writer.writeLine("こんにちは")
        writer.writeLine("（6秒空白）")

        assertEquals(listOf("2026-10-20 2045.md"), output.files.keys.toList())
        assertEquals(
            """
            ---
            開始: 2026-10-20 20:45
            何本目: 1
            方式: 端末の音声認識
            モデル: android-ondevice
            ---
            こんにちは
            （6秒空白）

            """.trimIndent(),
            output.files.getValue("2026-10-20 2045.md").toString(),
        )
        assertEquals(TranscriptWriter.Status.Writing("2026-10-20 2045.md"), statuses.last())
    }

    @Test
    fun 一時間たったら次のファイルへ移り_名前は記録を始めた日時に番号を付ける() {
        writer.writeLine("一本目")
        mono += 60 * 60 * 1000L
        wall = wall.plusHours(1)
        writer.writeLine("二本目")

        assertEquals(listOf("2026-10-20 2045.md", "2026-10-20 2045 (2).md"), output.files.keys.toList())
        val second = output.files.getValue("2026-10-20 2045 (2).md").toString()
        assertTrue(second.contains("開始: 2026-10-20 21:45\n何本目: 2\n"))
        assertTrue(second.endsWith("---\n二本目\n"))
    }

    @Test
    fun 書けなかった行は溜めておき_次に書けたときに順に書く() {
        writer.writeLine("一")
        output.failing = true
        writer.writeLine("二")
        writer.writeLine("三")
        assertTrue(statuses.last() is TranscriptWriter.Status.Failing)

        output.failing = false
        writer.writeLine("四")

        assertTrue(output.files.getValue("2026-10-20 2045.md").toString().endsWith("---\n一\n二\n三\n四\n"))
    }

    private class MemoryOutput : TranscriptOutput {
        val files = LinkedHashMap<String, StringBuilder>()
        var failing = false

        override fun create(displayName: String): String {
            if (failing) throw IOException("書けない")
            files[displayName] = StringBuilder()
            return displayName
        }

        override fun append(handle: String, text: String) {
            if (failing) throw IOException("書けない")
            files.getValue(handle).append(text)
        }
    }
}

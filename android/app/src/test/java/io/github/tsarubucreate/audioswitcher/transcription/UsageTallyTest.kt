package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTallyTest {
    private var clock = 0L
    private val tally = UsageTally(monotonicMs = { clock })

    @Test
    fun 方式ごとの時間の割合と_上限と切り替えの回数をまとめる() {
        tally.use("Groq Whisper")
        clock += 46 * 60_000L
        tally.rateLimited()
        tally.rateLimited()
        tally.rateLimited()
        tally.switched()
        tally.use("端末の音声認識")
        clock += 4 * 60_000L

        assertEquals(
            "前回 10/21 03:23 から 50分: Groq Whisper 92%・端末の音声認識 8%、無料枠の上限 3回、切り替え 1回",
            tally.summary("10/21 03:23"),
        )
    }

    @Test
    fun 戻した方式は同じ方式の時間に足し_何も無ければ回数は書かない() {
        tally.use("Groq Whisper")
        clock += 30 * 60_000L
        tally.use("端末の音声認識")
        clock += 10 * 60_000L
        tally.use("Groq Whisper")
        clock += 80 * 60_000L

        assertEquals("前回 10/21 03:23 から 2時間0分: Groq Whisper 92%・端末の音声認識 8%", tally.summary("10/21 03:23"))
    }

    @Test
    fun 一分に満たなければ秒で書く() {
        tally.use("Groq Whisper")
        clock += 42_000

        assertEquals("前回 10/21 03:23 から 42秒: Groq Whisper 100%", tally.summary("10/21 03:23"))
    }

    @Test
    fun Groqの1日の上限が確かめた値より下がったときだけ知らせる() {
        assertEquals("Groq の1日の回数の上限が下がっています（2,000 → 1,000 回）", GroqLimits.warning(1_000))
        assertNull(GroqLimits.warning(2_000))
        assertNull(GroqLimits.warning(14_400))
        assertNull(GroqLimits.warning(null))
        assertNull(GroqLimits.warning(0))
        assertEquals(1_000, GroqLimits.parse(" 1000 "))
        assertNull(GroqLimits.parse("たくさん"))
    }
}

package io.github.tsarubucreate.audioswitcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun 倍々に延ばして上限で頭打ちにする() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 15_000)

        val delays = List(7) { policy.nextDelayMs() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), delays)
    }

    @Test
    fun 繋がったら最初の待ち時間に戻る() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 15_000)
        repeat(3) { policy.nextDelayMs() }

        policy.reset()

        assertEquals(1_000L, policy.nextDelayMs())
    }
}

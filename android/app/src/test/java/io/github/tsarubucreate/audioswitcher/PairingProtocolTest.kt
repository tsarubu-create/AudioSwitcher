package io.github.tsarubucreate.audioswitcher

import io.github.tsarubucreate.audioswitcher.PairingProtocol.AnswerOutcome
import io.github.tsarubucreate.audioswitcher.PairingProtocol.Fields
import io.github.tsarubucreate.audioswitcher.PairingProtocol.StartOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    private val five = listOf("12", "47", "80", "03", "66")

    @Test
    fun 開始の応答を読み分ける() {
        assertEquals(StartOutcome.Started("id", five), PairingProtocol.startOutcome(200, Fields(pairingId = "id", candidates = five)))
        assertEquals(StartOutcome.AlreadyPaired, PairingProtocol.startOutcome(409, Fields(reason = "paired")))
        assertEquals(StartOutcome.Busy, PairingProtocol.startOutcome(409, Fields(reason = "busy")))
        assertEquals(
            StartOutcome.Locked("2026-10-21T00:00:00+00:00"),
            PairingProtocol.startOutcome(423, Fields(lockedUntil = "2026-10-21T00:00:00+00:00")),
        )
        assertTrue(PairingProtocol.startOutcome(500, Fields()) is StartOutcome.Failed)
    }

    @Test
    fun 候補が5つでない応答は読めないものとして扱う() {
        assertTrue(PairingProtocol.startOutcome(200, Fields(pairingId = "id", candidates = five.take(4))) is StartOutcome.Failed)
        assertTrue(PairingProtocol.answerOutcome(400, Fields(pairingId = "id", candidates = emptyList())) is AnswerOutcome.Failed)
    }

    @Test
    fun 答えの応答を読み分ける() {
        assertEquals(AnswerOutcome.Paired("key"), PairingProtocol.answerOutcome(200, Fields(key = "key")))
        assertEquals(AnswerOutcome.Wrong("id2", five), PairingProtocol.answerOutcome(400, Fields(pairingId = "id2", candidates = five)))
        assertEquals(AnswerOutcome.Locked(null), PairingProtocol.answerOutcome(423, Fields()))
        assertEquals(AnswerOutcome.Expired, PairingProtocol.answerOutcome(410, Fields()))
        assertTrue(PairingProtocol.answerOutcome(200, Fields()) is AnswerOutcome.Failed)
    }
}

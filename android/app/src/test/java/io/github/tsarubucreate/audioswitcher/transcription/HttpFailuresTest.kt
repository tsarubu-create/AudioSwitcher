package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFailuresTest {
    @Test
    fun 送り直すのは時間切れ_無料枠の上限_サーバの失敗だけ() {
        assertTrue(HttpFailures.of(408).retryable)
        assertTrue(HttpFailures.of(429).retryable)
        assertTrue(HttpFailures.of(503).retryable)
        assertFalse(HttpFailures.of(401).retryable)
        assertFalse(HttpFailures.of(403).retryable)
        assertFalse(HttpFailures.of(404).retryable)
        assertFalse(HttpFailures.of(400).retryable)
        assertFalse(HttpFailures.of(418).retryable)
    }

    @Test
    fun 理由は短い日本語で番号を添える() {
        assertEquals("API キーが認められない（401）", HttpFailures.of(401).message)
        assertEquals("無料枠の上限（429）", HttpFailures.of(429).message)
        assertEquals("モデルの名前が見つからない（404）", HttpFailures.of(404).message)
    }

    @Test
    fun モデルの名前は英数字と記号3つだけを受け付ける() {
        assertTrue(HttpFailures.isValidModel("gemini-3.8-flash"))
        assertTrue(HttpFailures.isValidModel("whisper-large-v3-turbo"))
        assertFalse(HttpFailures.isValidModel(""))
        assertFalse(HttpFailures.isValidModel("gemini/../x"))
        assertFalse(HttpFailures.isValidModel("gemini flash"))
    }
}

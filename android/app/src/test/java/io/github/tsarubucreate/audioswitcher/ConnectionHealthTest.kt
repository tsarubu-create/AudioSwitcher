package io.github.tsarubucreate.audioswitcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHealthTest {
    @Test
    fun 一度失敗したら_繋ぎ直しの読み込み中も繋がらないのまま() {
        var failing = ConnectionHealth.failing(previouslyFailing = false, playWhenReady = true, isPlaying = false, hasError = true)
        assertTrue(failing)

        // 繋ぎ直しを始めてエラーが消えても、まだ音は出ていない。
        failing = ConnectionHealth.failing(failing, playWhenReady = true, isPlaying = false, hasError = false)
        assertTrue(failing)
    }

    @Test
    fun 音が出始めたら繋がらないを解く() {
        assertFalse(ConnectionHealth.failing(previouslyFailing = true, playWhenReady = true, isPlaying = true, hasError = false))
    }

    @Test
    fun 停止したら繋がらないを解く() {
        assertFalse(ConnectionHealth.failing(previouslyFailing = true, playWhenReady = false, isPlaying = false, hasError = true))
    }

    @Test
    fun 失敗する前の最初の読み込み中は繋いでいる扱い() {
        assertFalse(ConnectionHealth.failing(previouslyFailing = false, playWhenReady = true, isPlaying = false, hasError = false))
    }
}

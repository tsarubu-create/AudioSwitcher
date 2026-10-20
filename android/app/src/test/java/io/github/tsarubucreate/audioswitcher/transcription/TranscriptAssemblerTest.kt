package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {
    private val written = ArrayList<String>()
    private val assembler = TranscriptAssembler { written.add(it) }

    @Test
    fun 先の区間の文字が遅れて届いても_後の印より先に書く() {
        val first = assembler.reserve()
        assembler.addMarker("（6秒空白）")
        val second = assembler.reserve()

        second.fulfill(listOf("後の文"))
        assertEquals(emptyList<String>(), written)

        first.fulfill(listOf("先の文1", "先の文2"))
        assertEquals(listOf("先の文1", "先の文2", "（6秒空白）", "後の文"), written)
        assertEquals(0, assembler.pendingCount())
    }

    @Test
    fun 空の行は書かない() {
        assembler.reserve().fulfill(listOf("", " ", "文"))

        assertEquals(listOf("文"), written)
    }
}

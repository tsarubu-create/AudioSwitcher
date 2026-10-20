package io.github.tsarubucreate.audioswitcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAddressTest {
    @Test
    fun IPとポートからURLを作る() {
        assertEquals("http://192.168.1.5:48200/stream", StreamAddress.toStreamUrl("192.168.1.5:48200"))
    }

    @Test
    fun ポートを省いたら既定のポートを使う() {
        assertEquals("http://192.168.1.5:48100/stream", StreamAddress.toStreamUrl(" 192.168.1.5 "))
    }

    @Test
    fun URLをそのまま貼っても読める() {
        assertEquals("http://192.168.1.5:48100/stream", StreamAddress.toStreamUrl("http://192.168.1.5:48100/stream"))
    }

    @Test
    fun 読めない書き方はnullを返す() {
        assertNull(StreamAddress.toStreamUrl(""))
        assertNull(StreamAddress.toStreamUrl("192.168.1.5:"))
        assertNull(StreamAddress.toStreamUrl("192.168.1.5:70000"))
        assertNull(StreamAddress.toStreamUrl("192.168.1.5:abc"))
        assertNull(StreamAddress.toStreamUrl("pc name"))
    }
}

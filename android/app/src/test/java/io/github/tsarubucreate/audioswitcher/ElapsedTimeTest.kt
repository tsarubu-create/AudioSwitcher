package io.github.tsarubucreate.audioswitcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTimeTest {
    @Test
    fun 一時間未満は分と秒() {
        assertEquals("0:00", ElapsedTime.format(0))
        assertEquals("0:00", ElapsedTime.format(999))
        assertEquals("5:07", ElapsedTime.format((5 * 60 + 7) * 1000L))
        assertEquals("59:59", ElapsedTime.format((59 * 60 + 59) * 1000L))
    }

    @Test
    fun 一時間以上は時と分と秒() {
        assertEquals("1:00:00", ElapsedTime.format(3600 * 1000L))
        assertEquals("1:23:45", ElapsedTime.format(((1 * 60 + 23) * 60 + 45) * 1000L))
        assertEquals("25:00:01", ElapsedTime.format((25 * 3600 + 1) * 1000L))
    }

    @Test
    fun 負の値は0として扱う() {
        assertEquals("0:00", ElapsedTime.format(-5000))
    }
}

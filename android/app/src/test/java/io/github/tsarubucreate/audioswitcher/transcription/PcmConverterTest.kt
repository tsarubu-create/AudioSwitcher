package io.github.tsarubucreate.audioswitcher.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmConverterTest {
    @Test
    fun ステレオ48kHzは左右を平均して3つずつ平均する() {
        val converter = PcmConverter().apply { configure(48_000, 2) }
        // 左右の組（L, R）を 6 フレーム。左右の平均は 10, 20, 30, 40, 50, 60。
        val input = shortArrayOf(0, 20, 10, 30, 20, 40, 30, 50, 40, 60, 50, 70)

        assertArrayEquals(shortArrayOf(20, 50), converter.convert(input))
    }

    @Test
    fun 呼び出しをまたいだ端数は次へ持ち越す() {
        val converter = PcmConverter().apply { configure(48_000, 1) }

        val first = converter.convert(shortArrayOf(3, 3, 3, 3))
        val second = converter.convert(shortArrayOf(6, 6))

        assertArrayEquals(shortArrayOf(3), first)
        assertArrayEquals(shortArrayOf(5), second) // (3 + 6 + 6) / 3
    }

    @Test
    fun 割り切れない44_1kHzは線形補間で間引き_長さと周波数が保たれる() {
        val converter = PcmConverter().apply { configure(44_100, 1) }
        val seconds = 1
        val source = ShortArray(44_100 * seconds) { i -> (10_000 * sin(2 * PI * 440 * i / 44_100)).toInt().toShort() }

        // 小分けにして渡しても、つなぎ目で途切れないこと。
        val out = source.toList().chunked(1_000).flatMap { converter.convert(it.toShortArray()).toList() }

        assertTrue("長さ ${out.size}", abs(out.size - 16_000 * seconds) <= 2)
        var crossings = 0
        for (i in 1 until out.size) if (out[i - 1] < 0 && out[i] >= 0) crossings++
        assertTrue("上向きに 0 をまたいだ回数 $crossings", abs(crossings - 440) <= 2)
    }

    @Test
    fun 形を替えたら持ち越しを捨てる() {
        val converter = PcmConverter().apply { configure(48_000, 1) }
        converter.convert(shortArrayOf(9, 9))

        converter.configure(16_000, 1)

        assertEquals(listOf<Short>(1, 2), converter.convert(shortArrayOf(1, 2)).toList())
    }
}

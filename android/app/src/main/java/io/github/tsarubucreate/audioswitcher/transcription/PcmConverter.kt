package io.github.tsarubucreate.audioswitcher.transcription

import kotlin.math.floor

/**
 * 再生している音（PCM 16bit、インターリーブ、任意の周波数・チャンネル数）を、文字起こしに渡す
 * 16kHz・モノラルに変える。呼ぶたびの端数は次の呼び出しへ持ち越す。
 *
 * 48kHz → 16kHz のように割り切れるときは、3つずつの平均を取る（簡単な折り返しの抑え）。
 * 割り切れないときは、線形の補間で間引く。
 */
class PcmConverter(private val targetRate: Int = TARGET_RATE) {
    private var sourceRate = 0
    private var channels = 0
    // 割り切れるとき: 平均を取っている途中の合計と数。
    private var groupSum = 0L
    private var groupCount = 0
    // 割り切れないとき: 次に取り出す位置（入力のサンプルを単位とする）と、直前の値。
    private var position = 0.0
    private var previous = 0

    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0 && channelCount > 0) { "周波数とチャンネル数は正の数（$sampleRate, $channelCount）" }
        if (sampleRate == sourceRate && channelCount == channels) return
        sourceRate = sampleRate
        channels = channelCount
        groupSum = 0
        groupCount = 0
        position = 0.0
        previous = 0
    }

    /** [interleaved] は [configure] の形の PCM。16kHz・モノラルの PCM を返す。 */
    fun convert(interleaved: ShortArray): ShortArray {
        check(sourceRate > 0) { "configure を先に呼ぶ" }
        val frames = interleaved.size / channels
        val mono = IntArray(frames) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
        return if (sourceRate % targetRate == 0) average(mono, sourceRate / targetRate) else interpolate(mono)
    }

    private fun average(mono: IntArray, factor: Int): ShortArray {
        val out = ShortArray((groupCount + mono.size) / factor)
        var written = 0
        for (sample in mono) {
            groupSum += sample
            groupCount++
            if (groupCount == factor) {
                out[written++] = (groupSum / factor).toInt().toShort()
                groupSum = 0
                groupCount = 0
            }
        }
        return out
    }

    private fun interpolate(mono: IntArray): ShortArray {
        val step = sourceRate.toDouble() / targetRate
        val out = ArrayList<Short>(((mono.size / step) + 2).toInt())
        // position は「直前の値（previous）を -1、今回の先頭を 0」とした位置。
        while (position < mono.size - 1) {
            // 持ち越した位置は負（直前の値との間）になりうるので、小さい方へ切り捨てる。
            val index = floor(position).toInt()
            val fraction = position - index
            val left = if (index < 0) previous else mono[index]
            val right = mono[index + 1]
            out.add((left + (right - left) * fraction).toInt().toShort())
            position += step
        }
        if (mono.isNotEmpty()) {
            previous = mono.last()
            position -= mono.size
        }
        return out.toShortArray()
    }

    companion object {
        const val TARGET_RATE = 16_000
    }
}

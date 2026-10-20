package io.github.tsarubucreate.audioswitcher.transcription

/**
 * 区間を溜めて送る方式（B・A）のために、声の区間を区切る（docs/構成.md の 2.5「B・A の区間の切り方」）。
 *
 * - 声が始まった所から溜める（その直前の [preRollMs] も含める。言葉の頭を切らないため）。
 * - [minMs] 以上溜まって、[pauseMs] 以上の間（声の無い所）が来たら区切る。
 * - [maxMs] に達したら、間が無くても区切る。
 *
 * Groq は 10 秒より短い音声も 10 秒として数えるので、[minMs] は 10 秒にする。
 * 区間の中の声が合わせて [minSpeechMs] に満たなければ、送らずに捨てる（一時停止の「プツッ」などの雑音で、
 * 無料枠を 10 秒ぶん使ったり、声の無い音から文を作られたりしないように）。
 */
class SpeechChunker(
    private val minMs: Long = 10_000,
    private val pauseMs: Long = 800,
    private val maxMs: Long = 60_000,
    private val preRollMs: Long = 300,
    private val minSpeechMs: Long = 500,
    private val sampleRate: Int = PcmConverter.TARGET_RATE,
) {
    private val buffer = ArrayList<ShortArray>()
    private var bufferedSamples = 0
    private var preRoll = ShortArray(0)
    private var trailingPauseMs = 0L
    private var speechMs = 0L

    /** 区間を捨てたときに呼ぶ（区間の長さと、その中の声の長さ。ともにミリ秒）。ログに残すため。 */
    var onDiscard: ((lengthMs: Long, speechMs: Long) -> Unit)? = null

    /** 塊を足す。区切れたら区間の音を返す（区切れなければ null）。 */
    fun add(samples: ShortArray, isSpeech: Boolean): ShortArray? {
        if (bufferedSamples == 0 && !isSpeech) {
            keepPreRoll(samples)
            return null
        }
        if (bufferedSamples == 0) {
            append(preRoll)
            preRoll = ShortArray(0)
        }
        append(samples)
        trailingPauseMs = if (isSpeech) 0 else trailingPauseMs + toMs(samples.size)
        if (isSpeech) speechMs += toMs(samples.size)

        val lengthMs = toMs(bufferedSamples)
        return if ((lengthMs >= minMs && trailingPauseMs >= pauseMs) || lengthMs >= maxMs) take() else null
    }

    /** 溜まっている分を区切って返す（空白・途切れの印の前、停止のとき）。溜まっていなければ null。 */
    fun flush(): ShortArray? = if (bufferedSamples == 0) null else take()

    /** 区切る。声が [minSpeechMs] に満たなければ捨てて null。 */
    private fun take(): ShortArray? {
        val speech = speechMs
        val out = drain()
        if (speech >= minSpeechMs) return out
        onDiscard?.invoke(toMs(out.size), speech)
        return null
    }

    private fun drain(): ShortArray {
        val out = ShortArray(bufferedSamples)
        var offset = 0
        for (part in buffer) {
            part.copyInto(out, offset)
            offset += part.size
        }
        buffer.clear()
        bufferedSamples = 0
        trailingPauseMs = 0
        speechMs = 0
        return out
    }

    private fun append(samples: ShortArray) {
        if (samples.isEmpty()) return
        buffer.add(samples)
        bufferedSamples += samples.size
    }

    private fun keepPreRoll(samples: ShortArray) {
        val keep = (preRollMs * sampleRate / 1000).toInt()
        val joined = preRoll + samples
        preRoll = if (joined.size > keep) joined.copyOfRange(joined.size - keep, joined.size) else joined
    }

    private fun toMs(samples: Int): Long = samples * 1000L / sampleRate
}

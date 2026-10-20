package io.github.tsarubucreate.audioswitcher

/**
 * 切れたあと繋ぎ直すまでの待ち時間を決める。1秒から倍々に延ばし、[maxDelayMs] で頭打ちにする。
 *
 * 利用者が停止を押すまで、回数の上限なく繋ぎ直す（PC を起動し直したときにも自然に戻るため）。
 */
class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
) {
    private var attempt = 0

    /** 次に繋ぎ直すまでの待ち時間。呼ぶたびに延びる。 */
    fun nextDelayMs(): Long {
        val delay = (initialDelayMs shl attempt.coerceAtMost(20)).coerceAtMost(maxDelayMs)
        attempt++
        return delay
    }

    /** 繋がったら最初からに戻す。 */
    fun reset() {
        attempt = 0
    }
}

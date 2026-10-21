package io.github.tsarubucreate.audioswitcher.transcription

import java.util.Locale

/**
 * 1回の文字起こし（「再生」から「停止」まで）で、方式ごとに使った時間・無料枠の上限に当たった回数・下の方式へ切り替えた回数を数える
 * （docs/構成.md の 2.5「無料枠の見張り」）。止めたら [summary] を「前回の結果」として画面に残す。
 *
 * 時間は単調な時計（ミリ秒）を差し替えられる口から受け取る。どのスレッドから呼んでもよい。
 */
class UsageTally(private val monotonicMs: () -> Long) {
    private val lock = Any()
    private val startedAt = monotonicMs()
    private val usedMs = LinkedHashMap<String, Long>()
    private var current: String? = null
    private var currentSince = startedAt
    private var rateLimits = 0
    private var switches = 0

    /** 今の方式が [method] になった（始めた・切り替えた・戻した）。 */
    fun use(method: String) = synchronized(lock) {
        closeCurrent()
        current = method
    }

    /** 下の方式へ切り替えた。 */
    fun switched() = synchronized(lock) { switches++ }

    /** 無料枠の上限（429）が返った（送り直しも1回と数える）。 */
    fun rateLimited() = synchronized(lock) { rateLimits++ }

    /** 例: 「前回 10/21 03:23 から 1時間2分: Groq Whisper 92%・端末の音声認識 8%、無料枠の上限 3回、切り替え 1回」。 */
    fun summary(startedLabel: String): String = synchronized(lock) {
        closeCurrent()
        val total = usedMs.values.sum()
        val shares = usedMs.entries.joinToString("・") { (method, ms) ->
            val percent = if (total > 0) Math.round(ms * 100.0 / total) else 100
            "$method $percent%"
        }
        buildString {
            append("前回 ").append(startedLabel).append(" から ").append(formatLength(monotonicMs() - startedAt))
            append(": ").append(shares)
            if (rateLimits > 0) append("、無料枠の上限 ").append(rateLimits).append("回")
            if (switches > 0) append("、切り替え ").append(switches).append("回")
        }
    }

    private fun closeCurrent() {
        val now = monotonicMs()
        current?.let { usedMs[it] = (usedMs[it] ?: 0L) + (now - currentSince) }
        currentSince = now
    }

    private fun formatLength(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "${ms / 1000}秒"
            minutes < 60 -> "${minutes}分"
            else -> "${minutes / 60}時間${minutes % 60}分"
        }
    }
}

/** Groq の1日の回数の上限の見張り（docs/構成.md の 2.5「無料枠の見張り」）。 */
object GroqLimits {
    /** 利用者が Groq の管理画面の Limits で確かめた、1日の回数の上限（2026-10-20）。 */
    const val KNOWN_DAILY_REQUESTS = 2_000

    /** 返事のヘッダ `x-ratelimit-limit-requests` の値が、確かめた上限より下がっていれば、画面に出す文。 */
    fun warning(observedDailyRequests: Int?): String? =
        observedDailyRequests?.takeIf { it in 1 until KNOWN_DAILY_REQUESTS }?.let {
            String.format(Locale.ROOT, "Groq の1日の回数の上限が下がっています（%,d → %,d 回）", KNOWN_DAILY_REQUESTS, it)
        }

    /** ヘッダの値を読む（整数でなければ null）。 */
    fun parse(header: String?): Int? = header?.trim()?.toIntOrNull()
}

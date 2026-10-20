package io.github.tsarubucreate.audioswitcher.transcription

import java.util.concurrent.Executor

/**
 * 方式を優先度の順（B > A > C）に並べて動かす（docs/構成.md の 2.5「切り替えの順」）。
 *
 * - 先頭（選んだ方式）で始め、失敗が続いて切り替えを頼まれたら、次の方式に替えて続ける。
 *   md には「（文字起こしを～に切り替えた：理由）」と書く。最後の方式でも続いたら、知らせだけを出す。
 * - 下の方式に替えてから [retryTopAfterMs] たつごとに、先頭の方式に戻して試す。md には「（文字起こしを～に戻した）」と書く。
 *
 * 方式は [chain] の作り方から、使うときに作る（戻すたびに作り直し、失敗の数を数え直すため）。
 * 切り替えは、音を渡している作業スレッド（[switchOn]）で行う（[feed] と同時に切り替えないため）。
 * 時計は単調な時計（ミリ秒）を差し替えられる口から受け取る。
 */
class SwitchableTranscriber(
    private val chain: List<() -> Transcriber>,
    private val switchOn: Executor,
    private val monotonicMs: () -> Long,
    private val retryTopAfterMs: Long = 10 * 60 * 1000L,
) : Transcriber {
    init {
        require(chain.isNotEmpty()) { "方式が1つも無い" }
    }

    @Volatile private var current: Transcriber = chain.first()()
    private var level = 0
    private var switchedAt = 0L
    private var outer: Transcriber.Listener? = null

    override val method: String get() = current.method
    override val model: String get() = current.model

    override fun start(listener: Transcriber.Listener) {
        outer = listener
        startCurrent(listener)
    }

    override fun feed(samples: ShortArray, isSpeech: Boolean) {
        if (level > 0 && monotonicMs() - switchedAt >= retryTopAfterMs) switchToTop()
        current.feed(samples, isSpeech)
    }

    override fun boundary() = current.boundary()

    override fun stop() = current.stop()

    private fun startCurrent(listener: Transcriber.Listener) {
        val started = current
        started.start(object : Transcriber.Listener by listener {
            override fun onFallback(reason: String) = switchOn.execute { switchDown(started, reason) }
        })
    }

    /** [from] で失敗が続いたので、次の方式に替える。 */
    private fun switchDown(from: Transcriber, reason: String) {
        val listener = outer ?: return
        // 戻したあとに、前の方式から遅れて届いた頼みは聞かない。
        if (from !== current) return
        if (level + 1 >= chain.size) {
            listener.onError("文字起こしの失敗が続いています：$reason")
            return
        }
        val next = chain[level + 1]()
        // 今の方式が溜めている分を先に送り、切り替えの印はそのあとに書く。
        from.stop()
        listener.reserve().fulfill(listOf("（文字起こしを${next.method}に切り替えた：$reason）"))
        current = next
        level++
        switchedAt = monotonicMs()
        startCurrent(listener)
        listener.onError("${from.method}の失敗が続いたため、${next.method}に切り替えました")
    }

    /** 先頭の方式に戻して試す。また失敗が続けば、もう一度下へ切り替わる。 */
    private fun switchToTop() {
        val listener = outer ?: return
        val top = chain.first()()
        current.stop()
        listener.reserve().fulfill(listOf("（文字起こしを${top.method}に戻した）"))
        current = top
        level = 0
        startCurrent(listener)
        listener.onNotice("${top.method}に戻して試しています")
    }
}

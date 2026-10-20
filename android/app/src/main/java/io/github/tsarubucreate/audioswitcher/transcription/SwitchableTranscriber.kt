package io.github.tsarubucreate.audioswitcher.transcription

import java.util.concurrent.Executor

/**
 * 主の方式（B・A）で動かし、失敗が続いて切り替えを頼まれたら、その回は代わりの方式（C）に替えて続ける
 * （docs/構成.md の 2.5）。md には「（文字起こしを端末の音声認識に切り替えた：理由）」と書く。
 *
 * 切り替えは、音を渡している作業スレッド（[switchOn]）で行う（[feed] と同時に切り替えないため）。
 * [fallbackFactory] が null なら（この端末で C が使えないなど）、切り替えずに知らせだけを出す。
 */
class SwitchableTranscriber(
    private val primary: Transcriber,
    private val fallbackFactory: (() -> Transcriber)?,
    private val switchOn: Executor,
) : Transcriber {
    @Volatile private var current: Transcriber = primary
    private var outer: Transcriber.Listener? = null

    override val method: String get() = current.method
    override val model: String get() = current.model

    override fun start(listener: Transcriber.Listener) {
        outer = listener
        primary.start(object : Transcriber.Listener by listener {
            override fun onFallback(reason: String) = switchOn.execute { switchToFallback(reason) }
        })
    }

    override fun feed(samples: ShortArray, isSpeech: Boolean) = current.feed(samples, isSpeech)

    override fun boundary() = current.boundary()

    override fun stop() = current.stop()

    private fun switchToFallback(reason: String) {
        val listener = outer ?: return
        if (current !== primary) return
        val factory = fallbackFactory
        if (factory == null) {
            listener.onError("文字起こしの失敗が続いています：$reason")
            return
        }
        val fallback = factory()
        // 主の方式が溜めている分を先に送り、切り替えの印はそのあとに書く。
        primary.stop()
        listener.reserve().fulfill(listOf("（文字起こしを${fallback.method}に切り替えた：$reason）"))
        fallback.start(listener)
        current = fallback
        listener.onError("${primary.method}の失敗が続いたため、${fallback.method}に切り替えました")
    }
}

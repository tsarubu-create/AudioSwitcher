package io.github.tsarubucreate.audioswitcher.transcription

/**
 * 文字起こしの方式の口（docs/構成.md の 2.5）。B（Groq の Whisper）・A（Gemini）・C（端末の音声認識）を入れ替えられる。
 *
 * 音は 16kHz・モノラルの PCM で、届いた順に [feed] で渡す（無音の間も渡す。音が届かない間は渡さない）。
 * 文字になったら [Listener] に知らせる。区間の切り方や送り方は、方式ごとに中で決める。
 */
interface Transcriber {
    /** md のプロパティ `方式` に書く名前。 */
    val method: String

    /** md のプロパティ `モデル` に書く短い名前。 */
    val model: String

    fun start(listener: Listener)

    /**
     * 16kHz・モノラルの PCM を渡す。[isSpeech] はフレームごとではなく、渡す塊に声が含まれていたか（区間の切り方の手がかり）。
     * どのスレッドから呼んでもよいが、同時には呼ばない。
     */
    fun feed(samples: ShortArray, isSpeech: Boolean)

    fun stop()

    interface Listener {
        /**
         * 文字になった区間の行。並びは md に書く順（1要素が1行）。返事の遅れる方式のために [Ticket] で順番を守る。
         */
        fun reserve(): Ticket

        /** 文字起こしを続けられないとき（方式が使えない・止まったなど）。[message] は画面に出す短い文。 */
        fun onError(message: String)
    }

    /** 書き出す順番の札。区間を受け付けたときに取り、文字になったら [fulfill] する。 */
    fun interface Ticket {
        fun fulfill(lines: List<String>)
    }
}

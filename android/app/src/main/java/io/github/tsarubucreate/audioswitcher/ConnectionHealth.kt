package io.github.tsarubucreate.audioswitcher

/**
 * 「繋がらない」を画面に出し続けるかの判断。Android に依らない形にして、単体テストで確かめる。
 *
 * ExoPlayer は繋ぎ直しを始めるとエラーを消して読み込み中に戻るため、エラーの有無だけで決めると、
 * 時間切れを待っている間（最大 8 秒）は「繋いでいる」に見えてしまう。そこで、いったん失敗したら、
 * 音が出始めるか停止するまで「繋がらない」のままにする。
 */
object ConnectionHealth {
    fun failing(previouslyFailing: Boolean, playWhenReady: Boolean, isPlaying: Boolean, hasError: Boolean): Boolean = when {
        !playWhenReady || isPlaying -> false
        hasError -> true
        else -> previouslyFailing
    }
}

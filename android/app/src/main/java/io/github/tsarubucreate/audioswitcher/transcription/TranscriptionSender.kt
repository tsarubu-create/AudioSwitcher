package io.github.tsarubucreate.audioswitcher.transcription

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 区間を送る役（B・A）。アプリの中で1本だけにし、PC の音と端末の音（docs/構成.md の 2.7）で使い回す。
 * 前の回の残りを送り終えてから次の回を送る（止めてすぐ別の経路で始めても、送りが重ならないように。2.5）。
 * アプリが動いている間ずっと使うので、閉じない。
 */
object TranscriptionSender {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "transcription-send") }
}

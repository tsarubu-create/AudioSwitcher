package io.github.tsarubucreate.audioswitcher.transcription

import android.util.Log
import java.util.concurrent.Executor

/**
 * 区間を文字にできなかったとき。[retryable] が false なら送り直さない（API キーが認められないなど）。
 * [waitMs] があれば、送り直す前にその長さだけ待つ（無料枠の上限のとき）。
 */
class TranscriptionFailure(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val waitMs: Long? = null,
) : Exception(message, cause)

/**
 * 区間を溜めて送る方式（B・A）に共通の部分（docs/構成.md の 2.5「B・A の区間の切り方と、失敗したとき」）。
 *
 * - 区間は [SpeechChunker] で区切り、受け付けた順に札を取って、送る用のスレッド1本（[sender]）で順に送る。
 *   [sender] はアプリで1本を使い回す（前の回の残りと次の回が同時に送らないため）。止めても閉じない。
 * - 失敗したら間をあけて [maxRetries] 回まで送り直し（無料枠の上限では示された待ち時間。[maxWaitMs] を超えたら諦める）、それでも駄目なら「（～秒文字起こし失敗：理由）」と書いて次へ進む。
 * - 続けて [fallbackAfter] 区間失敗したら、切り替えを頼む（[Transcriber.Listener.onFallback]）。
 *
 * 送り直しの待ちは差し替えられる口（[sleep]）から受け取り、テストでは待たない。
 * 送るたびに、何回目か・かかった時間・結果を [log] に残す（返事の遅さや送り直しを後から確かめるため）。
 * 時間は単調な時計（[monotonicMs]）で計る。ログに API キーや文字にした中身は入れない。
 */
abstract class ChunkedTranscriber(
    private val sender: Executor,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val chunker: SpeechChunker = SpeechChunker(),
    private val maxRetries: Int = 2,
    private val fallbackAfter: Int = 3,
    private val maxWaitMs: Long = 60_000,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Transcriber {
    private var listener: Transcriber.Listener? = null

    @Volatile private var consecutiveFailures = 0

    @Volatile private var fallbackRequested = false

    init {
        chunker.onDiscard = { lengthMs, speechMs ->
            log("$method: ${lengthMs}msの区間を送らずに捨てた（声は${speechMs}ms）")
        }
    }

    /** 区間の WAV を文字にする。失敗したら [TranscriptionFailure] を投げる。送る用のスレッドで呼ばれる。 */
    protected abstract fun transcribe(wav: ByteArray): String

    override fun start(listener: Transcriber.Listener) {
        this.listener = listener
    }

    override fun feed(samples: ShortArray, isSpeech: Boolean) {
        chunker.add(samples, isSpeech)?.let(::send)
    }

    override fun boundary() {
        chunker.flush()?.let(::send)
    }

    override fun stop() {
        // 受け付けた区間は、送る役が順に送り終える（送る役はアプリで1本なので、ここでは閉じない）。
        chunker.flush()?.let(::send)
    }

    private fun send(chunk: ShortArray) {
        val current = listener ?: return
        val ticket = current.reserve()
        val seconds = chunk.size / PcmConverter.TARGET_RATE
        sender.execute {
            val wav = Wav.encode(chunk)
            var attempt = 0
            var failure: TranscriptionFailure?
            while (true) {
                val sentAt = monotonicMs()
                failure = try {
                    val lines = Sentences.lines(transcribe(wav))
                    log("$method: ${seconds}秒の区間、${attempt + 1}回目で成功（${monotonicMs() - sentAt}ms、${lines.size}行）")
                    ticket.fulfill(lines)
                    consecutiveFailures = 0
                    return@execute
                } catch (e: TranscriptionFailure) {
                    e
                } catch (e: Exception) {
                    // 思わぬ失敗でも札は必ず埋める（埋めないと、後の行がいつまでも書き出されない）。送り直しはしない。
                    TranscriptionFailure("想定外の失敗（${e.javaClass.simpleName}）", retryable = false, cause = e)
                }
                // 無料枠の上限のときだけ waitMs がある。
                if (failure.waitMs != null) current.onRateLimited()
                val wait = failure.waitMs ?: (RETRY_BASE_DELAY_MS shl attempt)
                val again = failure.retryable && attempt < maxRetries && wait <= maxWaitMs
                log(
                    "$method: ${seconds}秒の区間、${attempt + 1}回目で失敗（${monotonicMs() - sentAt}ms、${failure.message}）" +
                        when {
                            again -> "。${wait}ms 待って送り直す"
                            failure.retryable && wait > maxWaitMs -> "。待ち時間（${wait}ms）が長すぎるので諦める"
                            else -> "。諦める"
                        },
                )
                if (!again) break
                attempt++
                sleep(wait)
            }

            ticket.fulfill(listOf("（${seconds}秒文字起こし失敗：${failure?.message}）"))
            consecutiveFailures++
            if (consecutiveFailures >= fallbackAfter && !fallbackRequested) {
                fallbackRequested = true
                current.onFallback(failure?.message ?: "失敗が続いた")
            }
        }
    }

    private companion object {
        const val TAG = "Transcription"
        const val RETRY_BASE_DELAY_MS = 2_000L
    }
}

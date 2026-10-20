package io.github.tsarubucreate.audioswitcher.transcription

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 区間を文字にできなかったとき。[retryable] が false なら送り直さない（API キーが認められないなど）。 */
class TranscriptionFailure(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/**
 * 区間を溜めて送る方式（B・A）に共通の部分（docs/構成.md の 2.5「B・A の区間の切り方と、失敗したとき」）。
 *
 * - 区間は [SpeechChunker] で区切り、受け付けた順に札を取って、送る用のスレッド1本で順に送る。
 * - 失敗したら間をあけて [maxRetries] 回まで送り直し、それでも駄目なら「（～秒文字起こし失敗：理由）」と書いて次へ進む。
 * - 続けて [fallbackAfter] 区間失敗したら、切り替えを頼む（[Transcriber.Listener.onFallback]）。
 *
 * 送り直しの待ちは差し替えられる口（[sleep]）から受け取り、テストでは待たない。
 * 送るたびに、何回目か・かかった時間・結果を [log] に残す（返事の遅さや送り直しを後から確かめるため）。
 * 時間は単調な時計（[monotonicMs]）で計る。ログに API キーや文字にした中身は入れない。
 */
abstract class ChunkedTranscriber(
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "transcription-send") },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val chunker: SpeechChunker = SpeechChunker(),
    private val maxRetries: Int = 2,
    private val fallbackAfter: Int = 3,
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
        chunker.flush()?.let(::send)
        // 受け付けた区間は送り終えてから終わる（新しくは受け付けない）。
        sender.shutdown()
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
                val again = failure.retryable && attempt < maxRetries
                log(
                    "$method: ${seconds}秒の区間、${attempt + 1}回目で失敗（${monotonicMs() - sentAt}ms、${failure.message}）" +
                        if (again) "。送り直す" else "。諦める",
                )
                if (!again) break
                attempt++
                sleep(RETRY_BASE_DELAY_MS shl (attempt - 1))
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

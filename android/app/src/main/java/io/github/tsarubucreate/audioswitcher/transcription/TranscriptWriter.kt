package io.github.tsarubucreate.audioswitcher.transcription

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 文字起こしの md を書く先（Vault のフォルダなど）。テストではメモリ上のものに差し替える。 */
interface TranscriptOutput {
    /** [displayName] のファイルを作り、追記するための手がかりを返す。 */
    @Throws(IOException::class)
    fun create(displayName: String): String

    @Throws(IOException::class)
    fun append(handle: String, text: String)
}

/**
 * 文字起こしの md を書く（docs/構成.md の 2.5）。
 *
 * - ファイル名は記録を始めた日時（例: `2026-10-20 2045.md`）。作った時点（「再生」を押した時点）の日時を使う
 *   （最初の行を書く時点ではない。B・A は最初の行が遅れて届くため）。[partLengthMs] ごとに次のファイル（`… (2).md`）へ移る。
 * - 各ファイルの先頭に Obsidian のプロパティ（開始・何本目・方式・モデル）を書く。
 * - 書けなかった行は捨てずに溜め、次に書けたときに書き出す（溜めるのは [maxPendingLines] 行まで）。
 *
 * 日時は壁時計（ファイル名とプロパティ用）、区切りの長さは単調な時計で計る。どちらも差し替えられる口から受け取る。
 */
class TranscriptWriter(
    private val output: TranscriptOutput,
    private val wallClock: () -> ZonedDateTime,
    private val monotonicMs: () -> Long,
    private val method: String,
    private val model: String,
    private val partLengthMs: Long = PART_LENGTH_MS,
    private val maxPendingLines: Int = 5_000,
    private val onStatus: (Status) -> Unit = {},
) {
    private val startedAt: ZonedDateTime = wallClock()
    private val startedAtMs: Long = monotonicMs()
    private val baseName: String = FILE_NAME.format(startedAt)
    private var part = 0
    private var partStartedAt = startedAtMs
    private var handle: String? = null
    private val pending = ArrayDeque<String>()
    private var droppedLines = 0

    sealed interface Status {
        data class Writing(val fileName: String) : Status
        data class Failing(val reason: String) : Status
    }

    /** 1行を書く（[line] に改行は含めない）。 */
    fun writeLine(line: String) {
        pending.addLast(line)
        while (pending.size > maxPendingLines) {
            pending.removeFirst()
            droppedLines++
        }
        flush()
    }

    private fun flush() {
        try {
            if (handle == null || monotonicMs() - partStartedAt >= partLengthMs) openNextPart(firstPart = handle == null)
            if (droppedLines > 0) {
                output.append(handle!!, "（書けずに捨てた行が ${droppedLines} 行あります）\n")
                droppedLines = 0
            }
            while (pending.isNotEmpty()) {
                output.append(handle!!, pending.first() + "\n")
                pending.removeFirst()
            }
        } catch (e: IOException) {
            // 捨てずに溜めておき、次に書けたときに書き出す。何が起きたかは状態で知らせる。
            onStatus(Status.Failing(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openNextPart(firstPart: Boolean) {
        // 1本目は記録を始めた日時、2本目からは、その本の始まり（前の本から 1 時間後）の日時。
        val partStart = if (firstPart) startedAt else wallClock()
        val nextPart = part + 1
        val name = if (nextPart == 1) "$baseName.md" else "$baseName ($nextPart).md"
        val created = output.create(name)
        output.append(created, frontMatter(partStart, nextPart))
        handle = created
        part = nextPart
        partStartedAt = if (firstPart) startedAtMs else monotonicMs()
        onStatus(Status.Writing(name))
    }

    private fun frontMatter(startedAt: ZonedDateTime, partNumber: Int) = buildString {
        append("---\n")
        append("開始: ").append(PROPERTY_TIME.format(startedAt)).append('\n')
        append("何本目: ").append(partNumber).append('\n')
        append("方式: ").append(method).append('\n')
        append("モデル: ").append(model).append('\n')
        append("---\n")
    }

    companion object {
        /** 1本のファイルの長さ（1時間。docs/構成.md の 2.5）。 */
        const val PART_LENGTH_MS = 60 * 60 * 1000L
        private val FILE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
        private val PROPERTY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

package io.github.tsarubucreate.audioswitcher.transcription

/**
 * 文字起こしの行と、空白・途切れの印を、起きた順に並べて書き出す。
 *
 * 返事が遅れて届く方式（B・A）では、先に起きた区間の文字が、後の印より遅れて届くことがある。
 * そこで、区間を受け付けた時点で札（[reserve]）を取り、前の札がすべて埋まってから書き出す。
 */
class TranscriptAssembler(private val sink: (String) -> Unit) {
    private val queue = ArrayDeque<Entry>()
    private val lock = Any()

    /** 文字になるのを待つ札を取る。 */
    fun reserve(): Transcriber.Ticket {
        val entry = Entry(null)
        synchronized(lock) { queue.addLast(entry) }
        return Transcriber.Ticket { lines -> fulfill(entry, lines) }
    }

    /** 空白・途切れなどの印。前に待っている札があれば、その後に書く。 */
    fun addMarker(line: String) {
        synchronized(lock) { queue.addLast(Entry(listOf(line))) }
        drain()
    }

    /** 待っている札の数（テスト・状態の表示用）。 */
    fun pendingCount(): Int = synchronized(lock) { queue.count { it.lines == null } }

    private fun fulfill(entry: Entry, lines: List<String>) {
        synchronized(lock) { entry.lines = lines }
        drain()
    }

    private fun drain() {
        val ready = ArrayList<String>()
        synchronized(lock) {
            while (queue.isNotEmpty()) {
                val head = queue.first()
                val lines = head.lines ?: break
                queue.removeFirst()
                ready.addAll(lines.filter { it.isNotBlank() })
            }
        }
        ready.forEach(sink)
    }

    private class Entry(@Volatile var lines: List<String>?)
}

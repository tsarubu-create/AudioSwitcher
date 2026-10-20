package io.github.tsarubucreate.audioswitcher

/**
 * PC のペアリングの受け口（docs/構成.md の 2.3「通信の取り決め」）の応答を、画面で扱う結果に直す。
 * Android に依らない形にして、単体テストで確かめる。
 */
object PairingProtocol {
    sealed interface StartOutcome {
        data class Started(val pairingId: String, val candidates: List<String>) : StartOutcome
        data object AlreadyPaired : StartOutcome
        data object Busy : StartOutcome
        data class Locked(val lockedUntil: String?) : StartOutcome
        data class Failed(val message: String) : StartOutcome
    }

    sealed interface AnswerOutcome {
        data class Paired(val key: String) : AnswerOutcome
        data class Wrong(val pairingId: String, val candidates: List<String>) : AnswerOutcome
        data class Locked(val lockedUntil: String?) : AnswerOutcome
        data object Expired : AnswerOutcome
        data class Failed(val message: String) : AnswerOutcome
    }

    /** 応答の本体から取り出した値。無い項目は null。 */
    data class Fields(
        val pairingId: String? = null,
        val candidates: List<String>? = null,
        val reason: String? = null,
        val lockedUntil: String? = null,
        val key: String? = null,
    )

    fun startOutcome(status: Int, fields: Fields): StartOutcome = when (status) {
        200 -> {
            val id = fields.pairingId
            val candidates = fields.candidates
            if (id != null && candidates != null && candidates.size == CANDIDATE_COUNT) {
                StartOutcome.Started(id, candidates)
            } else {
                StartOutcome.Failed("PC の応答が読めませんでした")
            }
        }
        409 -> if (fields.reason == "busy") StartOutcome.Busy else StartOutcome.AlreadyPaired
        423 -> StartOutcome.Locked(fields.lockedUntil)
        else -> StartOutcome.Failed("PC が想定外の応答を返しました（$status）")
    }

    fun answerOutcome(status: Int, fields: Fields): AnswerOutcome = when (status) {
        200 -> fields.key?.let { AnswerOutcome.Paired(it) } ?: AnswerOutcome.Failed("PC の応答が読めませんでした")
        400 -> {
            val id = fields.pairingId
            val candidates = fields.candidates
            if (id != null && candidates != null && candidates.size == CANDIDATE_COUNT) {
                AnswerOutcome.Wrong(id, candidates)
            } else {
                AnswerOutcome.Failed("PC の応答が読めませんでした")
            }
        }
        423 -> AnswerOutcome.Locked(fields.lockedUntil)
        410 -> AnswerOutcome.Expired
        else -> AnswerOutcome.Failed("PC が想定外の応答を返しました（$status）")
    }

    private const val CANDIDATE_COUNT = 5
}

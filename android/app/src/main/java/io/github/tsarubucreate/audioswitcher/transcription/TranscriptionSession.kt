package io.github.tsarubucreate.audioswitcher.transcription

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 1回の文字起こし（「再生」から「停止」まで）。届いた音から空白と途切れを見つけて印を書き、音を文字起こしの方式へ流す。
 *
 * - 空白: 届いた音のうち、声の無い長さ（音の中の時間）。[silenceThresholdMs] 以上なら、声が戻ったときに「（～秒空白）」。
 * - 途切れ: 音が届かなかった長さ（時計の時間）。[gapThresholdMs] 以上なら、音が戻ったときに「（～秒途切れ）」。
 *
 * 時計は単調な時計（ミリ秒）を差し替えられる口から受け取る。[onPcm] と [finish] は同じ作業スレッドから呼ぶ。
 */
class TranscriptionSession(
    private val transcriber: Transcriber,
    private val assembler: TranscriptAssembler,
    private val monotonicMs: () -> Long,
    private val silenceThresholdMs: Long = 5_000,
    private val gapThresholdMs: Long = 5_000,
    private val speechThresholdDbfs: Double = -45.0,
) {
    private var silentMs = 0L
    private var speechStreak = 0
    private var lastPcmAt: Long? = null
    private var pendingSamples = ShortArray(0)

    /** 16kHz・モノラルの PCM が届いたとき。 */
    fun onPcm(samples: ShortArray) {
        val now = monotonicMs()
        lastPcmAt?.let { last ->
            // 今届いた音の長さぶんは「届いていた」時間なので、途切れから差し引く。
            val gapMs = now - samples.size * 1000L / PcmConverter.TARGET_RATE - last
            if (gapMs >= gapThresholdMs) {
                flushSilence()
                transcriber.boundary()
                assembler.addMarker("（${gapMs / 1000}秒途切れ）")
            }
        }
        lastPcmAt = now

        var containsSpeech = false
        val all = if (pendingSamples.isEmpty()) samples else pendingSamples + samples
        var offset = 0
        while (offset + FRAME_SAMPLES <= all.size) {
            val speech = rmsDbfs(all, offset, FRAME_SAMPLES) > speechThresholdDbfs
            onFrame(speech)
            containsSpeech = containsSpeech || speech
            offset += FRAME_SAMPLES
        }
        pendingSamples = all.copyOfRange(offset, all.size)

        transcriber.feed(samples, containsSpeech)
    }

    /** 停止したとき。最後の空白・途切れを書いてから、文字起こしを止める。 */
    fun finish() {
        lastPcmAt?.let { last ->
            val gapMs = monotonicMs() - last
            if (gapMs >= gapThresholdMs) {
                flushSilence()
                transcriber.boundary()
                assembler.addMarker("（${gapMs / 1000}秒途切れ）")
            }
        }
        flushSilence()
        transcriber.stop()
    }

    private fun onFrame(speech: Boolean) {
        if (speech) {
            speechStreak++
            // 雑音の一瞬で空白を終えないよう、声が3フレーム（60ms）続いたら声が戻ったとみなす。
            if (speechStreak >= SPEECH_FRAMES_TO_RESUME) flushSilence()
        } else {
            speechStreak = 0
            silentMs += FRAME_MS
        }
    }

    private fun flushSilence() {
        if (silentMs >= silenceThresholdMs) {
            transcriber.boundary()
            assembler.addMarker("（${silentMs / 1000}秒空白）")
        }
        silentMs = 0
    }

    companion object {
        private const val FRAME_MS = 20L
        private const val FRAME_SAMPLES = PcmConverter.TARGET_RATE / 50
        private const val SPEECH_FRAMES_TO_RESUME = 3

        /** フレームの大きさ（dBFS。全く音の無いフレームは -∞ の代わりに -120）。 */
        fun rmsDbfs(samples: ShortArray, offset: Int, length: Int): Double {
            var sum = 0.0
            for (i in offset until offset + length) {
                val v = samples[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / length)
            return if (rms <= 0.0) -120.0 else 20 * log10(rms)
        }
    }
}

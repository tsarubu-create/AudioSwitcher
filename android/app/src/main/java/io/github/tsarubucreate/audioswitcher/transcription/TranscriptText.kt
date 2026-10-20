package io.github.tsarubucreate.audioswitcher.transcription

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 区間の音を、B・A に送る WAV（16bit・モノラル）にする。 */
object Wav {
    fun encode(samples: ShortArray, sampleRate: Int = PcmConverter.TARGET_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // fmt の大きさ
            putShort(1) // PCM
            putShort(1) // モノラル
            putInt(sampleRate)
            putInt(sampleRate * 2) // 1 秒あたりのバイト数
            putShort(2) // 1 サンプルのバイト数
            putShort(16) // ビット数
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        data.asShortBuffer().put(samples)
        return ByteArrayOutputStream(44 + dataBytes).apply {
            write(header.array())
            write(data.array())
        }.toByteArray()
    }
}

/** B・A から返ってきた文を、md の行にする（句点で改行。docs/構成.md の 2.5）。 */
object Sentences {
    fun lines(text: String): List<String> =
        text.replace("\r", "")
            .split('\n')
            .flatMap { paragraph -> paragraph.split(Regex("(?<=。)")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

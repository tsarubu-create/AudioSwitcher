package io.github.tsarubucreate.audioswitcher.transcription

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/** HTTP の失敗を、送り直すかどうかと短い理由に振り分ける。理由に API キーや送った中身は入れない。 */
object HttpFailures {
    fun of(code: Int): TranscriptionFailure = when (code) {
        401, 403 -> TranscriptionFailure("API キーが認められない（$code）", retryable = false)
        404 -> TranscriptionFailure("モデルの名前が見つからない（$code）", retryable = false)
        400 -> TranscriptionFailure("送った音声を受け付けなかった（$code）", retryable = false)
        413 -> TranscriptionFailure("音声が大きすぎる（$code）", retryable = false)
        408 -> TranscriptionFailure("時間切れ（$code）", retryable = true)
        429 -> TranscriptionFailure("無料枠の上限（$code）", retryable = true)
        in 500..599 -> TranscriptionFailure("サーバの失敗（$code）", retryable = true)
        else -> TranscriptionFailure("失敗（$code）", retryable = false)
    }

    /** モデルの名前として受け付ける形（URL に入れるので、英数字と `.`・`-`・`_` だけ）。 */
    fun isValidModel(model: String): Boolean = Regex("^[A-Za-z0-9._-]+$").matches(model)
}

/** HTTP で送り、返事の本文を返す。返事を [readTimeoutMs] まで待つ。失敗は [TranscriptionFailure] にする。 */
private fun post(url: String, headers: Map<String, String>, body: ByteArray, readTimeoutMs: Int = 60_000): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        connection.doOutput = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        if (code !in 200..299) throw HttpFailures.of(code)
        return connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        throw TranscriptionFailure("通信の失敗（${e.javaClass.simpleName}）", retryable = true, cause = e)
    } finally {
        connection.disconnect()
    }
}

/**
 * B: Groq の Whisper（docs/構成.md の 2.5）。区間の WAV を送り、日本語の文字を受け取る。
 * API キーは `Authorization` ヘッダで渡す。
 */
class GroqTranscriber(private val apiKey: String, override val model: String) : ChunkedTranscriber() {
    override val method = "Groq Whisper"

    override fun transcribe(wav: ByteArray): String {
        val boundary = "AudioSwitcher${System.nanoTime()}"
        val body = java.io.ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", model)
            field("language", "ja")
            field("response_format", "json")
            field("temperature", "0")
            // Whisper は日本語で句点を付けないことが多い。句点の付いた例文を前置きにすると、その書き方に寄せて返す
            // （「句点を付けて」という指示は効かない。docs/構成.md の 2.5）。
            field("prompt", PUNCTUATION_PROMPT)
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val response = post(
            ENDPOINT,
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "multipart/form-data; boundary=$boundary"),
            body,
        )
        return try {
            JSONObject(response).optString("text")
        } catch (e: JSONException) {
            throw TranscriptionFailure("返事が読めない", retryable = false, cause = e)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"
        private const val PUNCTUATION_PROMPT = "こんにちは。今日はよろしくお願いします。では、始めましょう。"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    }
}

/**
 * A: Gemini（docs/構成.md の 2.5）。区間の WAV を `inline_data` で送り、話し言葉だけを日本語で書かせる。
 * API キーは URL ではなく `x-goog-api-key` ヘッダで渡す（URL は記録に残りやすいため）。
 */
class GeminiTranscriber(private val apiKey: String, override val model: String) : ChunkedTranscriber() {
    override val method = "Gemini"

    override fun transcribe(wav: ByteArray): String {
        val request = JSONObject().put(
            "contents",
            org.json.JSONArray().put(
                JSONObject().put(
                    "parts",
                    org.json.JSONArray()
                        .put(JSONObject().put("text", PROMPT))
                        .put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", "audio/wav").put("data", Base64.getEncoder().encodeToString(wav)),
                            ),
                        ),
                ),
            ),
        )
        val response = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
            request.toString().toByteArray(),
            READ_TIMEOUT_MS,
        )
        return try {
            val candidates = JSONObject(response).optJSONArray("candidates") ?: return ""
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            (0 until parts.length()).joinToString("\n") { parts.optJSONObject(it)?.optString("text").orEmpty() }
        } catch (e: JSONException) {
            throw TranscriptionFailure("返事が読めない", retryable = false, cause = e)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        /** Gemini は音声が長いほど返事が遅く、60 秒の区間で 60 秒を超えたので、180 秒まで待つ（docs/構成.md の 2.5）。 */
        private const val READ_TIMEOUT_MS = 180_000

        /** 話し言葉だけを書かせる指示（docs/構成.md の 2.5: 効果音・衝撃音・BGM は書かない）。 */
        private const val PROMPT =
            "次の音声を日本語で文字起こししてください。話し言葉だけを書き、効果音・衝撃音・BGM・歌は書かないでください。" +
                "文の終わりには句点「。」を付け、文の区切りを空白で表さないでください。" +
                "前置きや説明、話し手の名前は書かず、文字起こしの本文だけを出してください。" +
                "聞き取れる話し言葉が無いときは、何も出さないでください。"
    }
}

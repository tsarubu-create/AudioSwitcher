package io.github.tsarubucreate.audioswitcher

import io.github.tsarubucreate.audioswitcher.PairingProtocol.AnswerOutcome
import io.github.tsarubucreate.audioswitcher.PairingProtocol.Fields
import io.github.tsarubucreate.audioswitcher.PairingProtocol.StartOutcome
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** PC のペアリングの受け口を呼ぶ。通信は IO のスレッドで行う。 */
class PairingClient(private val baseUrl: String) {

    suspend fun start(): StartOutcome = withContext(Dispatchers.IO) {
        try {
            val (status, fields) = post("/pair/start", null)
            PairingProtocol.startOutcome(status, fields)
        } catch (e: IOException) {
            StartOutcome.Failed("PC に繋がりません（${e.message}）")
        }
    }

    suspend fun answer(pairingId: String, answer: String): AnswerOutcome = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("pairingId", pairingId).put("answer", answer).toString()
            val (status, fields) = post("/pair/answer", body)
            PairingProtocol.answerOutcome(status, fields)
        } catch (e: IOException) {
            AnswerOutcome.Failed("PC に繋がりません（${e.message}）")
        }
    }

    private fun post(path: String, json: String?): Pair<Int, Fields> {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write((json ?: "").toByteArray()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to parse(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): Fields {
        if (text.isBlank()) return Fields()
        return try {
            val json = JSONObject(text)
            Fields(
                pairingId = json.optString("pairingId").ifEmpty { null },
                candidates = json.optJSONArray("candidates")?.let { array -> List(array.length()) { array.getString(it) } },
                reason = json.optString("reason").ifEmpty { null },
                lockedUntil = json.optString("lockedUntil").ifEmpty { null },
                key = json.optString("key").ifEmpty { null },
            )
        } catch (e: JSONException) {
            throw IOException("PC の応答が JSON ではありません", e)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}

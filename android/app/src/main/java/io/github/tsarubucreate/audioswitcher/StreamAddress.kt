package io.github.tsarubucreate.audioswitcher

/**
 * 利用者が入れた接続先を、PC の配信の URL（`http://ホスト:ポート/stream`）に直す。
 *
 * 受け付ける書き方: `192.168.1.5`、`192.168.1.5:48100`、`http://192.168.1.5:48100/stream`。
 * ポートを省いたら [DEFAULT_PORT]（PC 側の既定）を使う。
 */
object StreamAddress {
    const val DEFAULT_PORT = 48100
    private const val STREAM_PATH = "/stream"

    private val hostPattern = Regex("^[A-Za-z0-9.-]+$")

    /** 配信の URL。読めなければ null を返す。 */
    fun toStreamUrl(input: String): String? = toBaseUrl(input)?.let { it + STREAM_PATH }

    /** PC の受け口の土台の URL（`http://ホスト:ポート`）。読めなければ null を返す。 */
    fun toBaseUrl(input: String): String? {
        var rest = input.trim()
        if (rest.startsWith("http://", ignoreCase = true)) {
            rest = rest.substring("http://".length)
        }
        rest = rest.substringBefore('/')
        if (rest.isEmpty()) return null

        val host: String
        val port: Int
        val colon = rest.lastIndexOf(':')
        if (colon >= 0) {
            host = rest.substring(0, colon)
            port = rest.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = rest
            port = DEFAULT_PORT
        }

        if (!hostPattern.matches(host) || port !in 1..65535) return null
        return "http://$host:$port"
    }
}

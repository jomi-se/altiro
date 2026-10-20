package org.altiro.network

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest

/** Explicit development diagnostics transport. No DNS, proxy, redirect or ordinary dictation. */
class LabRelayClient
internal constructor(
    link: String,
    private val openConnection: (URI) -> HttpURLConnection,
) {
    constructor(
        link: String
    ) : this(link, { it.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection })

    private val destination = parse(link)

    val host: String
        get() = destination.host

    fun send(report: ByteArray): Boolean {
        require(report.size in 1..8_388_608)
        val connection = openConnection(destination.uri)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(report.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${destination.token}")
            connection.outputStream.use { it.write(report) }
            if (connection.responseCode != 200) return false
            val receipt = connection.inputStream.use { it.readNBytes(65).decodeToString().trim() }
            val expected =
                MessageDigest.getInstance("SHA-256").digest(report).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            return receipt == expected
        } finally {
            connection.disconnect()
        }
    }

    data class Destination(val uri: URI, val host: String, val token: String)

    companion object {
        fun parse(link: String): Destination {
            require(link.length <= 256)
            val uri = URI(link)
            require(uri.scheme == "http" && uri.userInfo == null && uri.query == null)
            require(uri.path == "/reports" && uri.port in 1024..65535)
            val host = requireNotNull(uri.host)
            val octets = host.split('.').map { it.toIntOrNull() }
            require(octets.size == 4 && octets.all { it != null && it in 0..255 })
            require(octets[0] == 100 && octets[1]!! in 64..127)
            require(host == octets.joinToString("."))
            val token = requireNotNull(uri.fragment)
            require(token.matches(Regex("[a-f0-9]{64}")))
            return Destination(
                URI("http", null, host, uri.port, "/reports", null, null),
                host,
                token,
            )
        }
    }
}

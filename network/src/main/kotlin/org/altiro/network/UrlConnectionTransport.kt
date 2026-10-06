package org.altiro.network

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** One HTTPS request per hop; redirects are returned to the caller for host review. */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : HttpTransport {
    override fun open(
        uri: URI,
        register: (Closeable) -> Unit,
    ): HttpResponse {
        if (uri.scheme != "https") throw IOException("HTTPS required")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        // Byte count and hash are checked against the stored, uncompressed file.
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Altiro")
        register(Closeable(connection::disconnect))
        val status =
            try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            }
        return object : HttpResponse {
            override val status = status
            override val location: String? = connection.getHeaderField("Location")
            override val contentLength = connection.contentLengthLong
            override val contentEncoding: String? = connection.contentEncoding

            override fun body(): InputStream = connection.inputStream

            override fun close() = connection.disconnect()
        }
    }
}

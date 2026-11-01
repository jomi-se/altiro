package org.altiro.network

import java.net.URI
import java.util.Locale

/** Fixed self-hosted speech-server contract; never a general-purpose URL resolver. */
class RemoteSpeechEndpoint private constructor(internal val uri: URI) {
    val normalized: String = uri.toASCIIString()
    val host: String = uri.host
    internal val healthUri = URI("https", null, uri.host, uri.port, "/health", null, null)

    companion object {
        fun parse(value: String): RemoteSpeechEndpoint? {
            if (value.length > 2048) return null
            return try {
                val input = URI(value.trim())
                if (
                    !input.scheme.equals("https", ignoreCase = true) ||
                        input.host.isNullOrEmpty() ||
                        input.rawUserInfo != null ||
                        input.rawQuery != null ||
                        input.rawFragment != null ||
                        input.port !in -1..65535 ||
                        input.port == 0 ||
                        input.rawPath !in listOf("", "/", "/v1/audio/transcriptions") ||
                        input.rawAuthority.endsWith(":") ||
                        '%' in input.host
                )
                    return null
                RemoteSpeechEndpoint(
                    URI(
                        "https",
                        null,
                        input.host.lowercase(Locale.ROOT),
                        if (input.port == 443) -1 else input.port,
                        "/v1/audio/transcriptions",
                        null,
                        null,
                    )
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

package org.altiro.network

import org.junit.Assert.*
import org.junit.Test

class RemoteSpeechEndpointTest {
    @Test
    fun rootAndExactRouteUseSameOriginIncludingMagicDnsAndIpv6() {
        val cases =
            mapOf(
                " https://SPEECH.example.net:443/ " to
                    "https://speech.example.net/v1/audio/transcriptions",
                "https://speech:8443" to "https://speech:8443/v1/audio/transcriptions",
                "https://speech.example.net/v1/audio/transcriptions" to
                    "https://speech.example.net/v1/audio/transcriptions",
                "https://[2001:db8::1]:8443/" to
                    "https://[2001:db8::1]:8443/v1/audio/transcriptions",
            )
        cases.forEach { (input, expected) ->
            val endpoint = requireNotNull(RemoteSpeechEndpoint.parse(input))
            assertEquals(expected, endpoint.normalized)
            assertEquals(
                expected.substringBefore("/v1/") + "/health",
                endpoint.healthUri.toString(),
            )
        }
    }

    @Test
    fun rejectsCleartextAmbiguousAuthoritiesAndEveryOtherRoute() {
        for (input in
            listOf(
                "http://speech.example.net",
                "https://user:secret@speech.example.net/",
                "https://speech.example.net/?key=secret",
                "https://speech.example.net/#",
                "https://speech.example.net:0/",
                "https://speech.example.net:65536/",
                "https://speech.example.net:/",
                "https://speech.example.net/v1",
                "https://speech.example.net/v1/audio/transcriptions/",
                "https://speech.example.net/v1/audio/%74ranscriptions",
                "https://speech.example.net/../v1/audio/transcriptions",
                "https://speech.example.net//",
                "https://speech.example.net\\@other.example.net/",
                "https://[fe80::1%25eth0]/",
                "https:///v1/audio/transcriptions",
                "not a URL",
            )) assertNull(input, RemoteSpeechEndpoint.parse(input))
    }
}

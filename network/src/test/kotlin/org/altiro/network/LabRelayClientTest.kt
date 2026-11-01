package org.altiro.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LabRelayClientTest {
    private val token = "a".repeat(64)

    @Test
    fun receiptMustMatchExactBytesAndRedirectsNeverCountAsDelivery() {
        val raw = "{\"schema\":1}".toByteArray()
        val digest =
            MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        for ((code, receipt, expected) in
            listOf(
                Triple(200, digest + "\n", true),
                Triple(200, "b".repeat(64), false),
                Triple(302, digest, false),
            )) {
            val connection = FakeConnection(code, receipt)
            val client = LabRelayClient("http://100.64.0.1:8774/reports#$token") { connection }
            assertEquals(expected, client.send(raw))
            assertArrayEquals(raw, connection.sent.toByteArray())
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.closed)
            assertEquals("Bearer $token", connection.getRequestProperty("Authorization"))
        }
    }

    private class FakeConnection(private val code: Int, private val receipt: String) :
        HttpURLConnection(URL("http://100.64.0.1:8774/reports")) {
        val sent = ByteArrayOutputStream()
        var closed = false

        override fun connect() = Unit

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getOutputStream() = sent

        override fun getInputStream() = ByteArrayInputStream(receipt.toByteArray())

        override fun getResponseCode() = code
    }

    @Test
    fun onlyDirectTailnetAddressesAndReportRouteAreAccepted() {
        for (host in listOf("100.64.0.1", "100.127.255.254")) {
            val result = LabRelayClient.parse("http://$host:8774/reports#$token")
            assertEquals(host, result.host)
            assertNull(result.uri.fragment)
            assertEquals(token, result.token)
        }
        for (link in
            listOf(
                "http://127.0.0.1:8774/reports#$token",
                "http://100.63.255.255:8774/reports#$token",
                "http://100.128.0.1:8774/reports#$token",
                "http://example.invalid:8774/reports#$token",
                "http://100.064.0.1:8774/reports#$token",
                "http://100.64.0.1:8774/other#$token",
                "http://100.64.0.1:8774/reports?target=other#$token",
                "http://user@100.64.0.1:8774/reports#$token",
                "https://100.64.0.1:8774/reports#$token",
                "http://100.64.0.1:80/reports#$token",
                "http://100.64.0.1:8774/reports#short",
            )) {
            assertThrows(link, IllegalArgumentException::class.java) { LabRelayClient.parse(link) }
        }
    }
}

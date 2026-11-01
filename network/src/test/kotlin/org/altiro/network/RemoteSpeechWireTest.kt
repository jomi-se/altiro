package org.altiro.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.altiro.core.Wav
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real loopback TLS and HTTP framing; the scripted responses establish no GPU execution claim. */
class RemoteSpeechWireTest {
    @Test(timeout = 15000)
    fun healthThenOneFixedLengthMultipartUploadPreservesAudioMetadataAndUnicode() {
        val requests = CopyOnWriteArrayList<Request>()
        WireServer { exchange ->
                requests.add(capture(exchange))
                if (exchange.requestURI.path == "/health") reply(exchange, 200, READY)
                else
                    reply(
                        exchange,
                        200,
                        """{"text":"¡Sí, José! \"bonjour\"\n\uD83D\uDE80","device":"cuda","model":"large-v3-turbo"}""",
                    )
            }
            .use { server ->
                val audio = audio()
                val client = client("wire-test-token")
                val decision =
                    remoteSpeechDecision(
                        cancelled = { false },
                        readiness = { client.health(server.endpoint) },
                        transcribe = {
                            client.transcribe(server.endpoint, audio, "es", "José; María")
                        },
                    )
                assertEquals(
                    RemoteSpeechDecision.Transcript("¡Sí, José! \"bonjour\"\n🚀"),
                    decision,
                )
                assertEquals(
                    listOf("/health", "/v1/audio/transcriptions"),
                    requests.map { it.path },
                )
                val health = requests[0]
                assertEquals("GET", health.method)
                assertNull(health.authorization)
                assertEquals(0, health.body.size)
                val upload = requests[1]
                assertEquals("POST", upload.method)
                assertEquals("Bearer wire-test-token", upload.authorization)
                assertNull(upload.transferEncoding)
                assertEquals(upload.body.size.toString(), upload.contentLength)
                assertEquals("identity", upload.acceptEncoding)
                assertTrue(requests.all { it.tlsProtocol.startsWith("TLS") })
                val contentType = requireNotNull(upload.contentType)
                assertTrue(contentType.startsWith("multipart/form-data; boundary="))
                val boundary = contentType.substringAfter("boundary=")
                val expected = ByteArrayOutputStream()
                fun append(text: String) = expected.write(text.toByteArray(Charsets.UTF_8))
                for ((name, value) in
                    listOf(
                        "model" to "large-v3-turbo",
                        "language" to "es",
                        "response_format" to "json",
                        "prompt" to "José; María",
                    )) {
                    append(
                        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
                    )
                }
                append(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
                )
                expected.write(audio.readBytes())
                append("\r\n--$boundary--\r\n")
                assertArrayEquals(expected.toByteArray(), upload.body)
                assertTrue(audio.exists())
            }
    }

    @Test(timeout = 15000)
    fun unavailableHealthOverTlsSelectsLocalWithoutUploading() {
        val requests = CopyOnWriteArrayList<Request>()
        WireServer { exchange ->
                requests.add(capture(exchange))
                reply(
                    exchange,
                    503,
                    """{"ready":false,"busy":false,"model":"large-v3-turbo","device":"cuda"}""",
                )
            }
            .use { server ->
                val client = client()
                val audio = audio()
                val decision =
                    remoteSpeechDecision(
                        { false },
                        { client.health(server.endpoint) },
                        { client.transcribe(server.endpoint, audio, "auto") },
                    )
                assertEquals(RemoteSpeechDecision.LocalFallback("remote_not_ready"), decision)
                assertEquals(listOf("GET"), requests.map { it.method })
                assertEquals(listOf("/health"), requests.map { it.path })
                assertTrue(audio.exists())
            }
    }

    @Test(timeout = 15000)
    fun postErrorsRedirectsAndLostResponsesNeverReplayOrForwardCredentials() {
        val redirected = CopyOnWriteArrayList<Request>()
        WireServer { exchange ->
                redirected.add(capture(exchange))
                reply(exchange, 200, """{"text":"must not be contacted"}""")
            }
            .use { otherOrigin ->
                val requests = CopyOnWriteArrayList<Request>()
                val status = AtomicInteger(429)
                WireServer { exchange ->
                        requests.add(capture(exchange))
                        if (status.get() == 0) {
                            // An accepted body followed by a lost response is an uncertain outcome.
                            exchange.close()
                        } else {
                            exchange.responseHeaders.set(
                                "Location",
                                otherOrigin.endpoint.normalized,
                            )
                            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
                            reply(exchange, status.get(), """{"error":"private server detail"}""")
                        }
                    }
                    .use { server ->
                        val client = client("wire-test-token")
                        val audio = audio()
                        for ((responseStatus, code) in
                            listOf(
                                429 to "remote_busy",
                                503 to "remote_not_ready",
                                307 to "remote_redirect",
                                401 to "remote_unauthorized",
                                0 to "remote_transport_error",
                            )) {
                            status.set(responseStatus)
                            val before = requests.size
                            val error =
                                assertThrows(RemoteSpeechException::class.java) {
                                    client.transcribe(server.endpoint, audio, "auto")
                                }
                            assertEquals(code, error.code)
                            assertEquals(code, error.message)
                            assertNull(error.cause)
                            assertEquals(
                                "POST was replayed for HTTP $responseStatus",
                                before + 1,
                                requests.size,
                            )
                            assertEquals("POST", requests.last().method)
                            assertEquals("Bearer wire-test-token", requests.last().authorization)
                            assertTrue(redirected.isEmpty())
                        }
                        assertTrue(audio.exists())
                    }
            }
    }

    @Test(timeout = 15000)
    fun defaultTrustAndHostnameVerificationRejectTheUntrustedOrWrongHostCertificate() {
        val requests = CopyOnWriteArrayList<Request>()
        WireServer { exchange ->
                requests.add(capture(exchange))
                reply(exchange, 200, READY)
            }
            .use { server ->
                val untrusted =
                    assertThrows(RemoteSpeechException::class.java) {
                        // Bypass any ambient proxy, retaining the platform's default TLS trust
                        // unchanged.
                        RemoteSpeechClient(
                                null,
                                { uri ->
                                    uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
                                },
                            )
                            .health(server.endpoint)
                    }
                assertEquals("remote_transport_error", untrusted.code)
                val wrongHost =
                    requireNotNull(
                        RemoteSpeechEndpoint.parse(
                            server.endpoint.normalized.replace("localhost", "127.0.0.1")
                        )
                    )
                val mismatch =
                    assertThrows(RemoteSpeechException::class.java) {
                        client().health(wrongHost)
                    }
                assertEquals("remote_transport_error", mismatch.code)
                assertTrue(requests.isEmpty())
                // Only this connection's private trust store is changed. Hostname verification
                // remains on.
                assertTrue(client().health(server.endpoint))
                assertEquals(1, requests.size)
            }
    }

    @Test(timeout = 15000)
    fun cancellationWhileTlsBodyIsPendingReturnsPromptlyAndRejectsTheLateTranscript() {
        val bodyPending = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val handlerDone = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Request>()
        WireServer { exchange ->
                requests.add(capture(exchange))
                val prefix = "{\"text\":\"".toByteArray(Charsets.UTF_8)
                val suffix = "late success\"}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, (prefix.size + suffix.size).toLong())
                try {
                    exchange.responseBody.write(prefix)
                    exchange.responseBody.flush()
                    bodyPending.countDown()
                    assertTrue(
                        "Test failed to release response",
                        releaseBody.await(5, TimeUnit.SECONDS),
                    )
                    try {
                        exchange.responseBody.write(suffix)
                        exchange.responseBody.flush()
                    } catch (_: IOException) {
                        // Closing the canceled TLS connection may make the late write fail.
                    }
                } finally {
                    handlerDone.countDown()
                }
            }
            .use { server ->
                val client = client()
                val cancelled = AtomicBoolean(false)
                val audio = audio()
                val caller = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "speech-wire-caller").apply { isDaemon = true }
                }
                try {
                    val result =
                        caller.submit<RemoteSpeechDecision> {
                            remoteSpeechDecision(
                                cancelled::get,
                                { true },
                                {
                                    client.transcribe(
                                        server.endpoint,
                                        audio,
                                        "auto",
                                        cancelled = cancelled::get,
                                    )
                                },
                            )
                        }
                    assertTrue(bodyPending.await(3, TimeUnit.SECONDS))
                    cancelled.set(true)
                    client.cancel()
                    val error =
                        assertThrows(ExecutionException::class.java) {
                            result.get(2, TimeUnit.SECONDS)
                        }
                    assertTrue(error.cause is CancellationException)
                    releaseBody.countDown()
                    assertTrue(handlerDone.await(2, TimeUnit.SECONDS))
                    assertEquals(1, requests.size)
                    assertEquals("POST", requests.single().method)
                    assertTrue(audio.exists())
                } finally {
                    cancelled.set(true)
                    client.cancel()
                    releaseBody.countDown()
                    caller.shutdownNow()
                    assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
    }

    private fun client(token: String? = null): RemoteSpeechClient =
        RemoteSpeechClient(
            token,
            { uri ->
                (uri.toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    sslSocketFactory = clientTls.socketFactory
                }
            },
        )

    private fun audio(): File =
        temporary.newFile().apply {
            outputStream().use { output ->
                output.write(Wav.header(20_000))
                output.write(ByteArray(40_000) { (it % 251).toByte() })
            }
        }

    private data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val contentLength: String?,
        val transferEncoding: String?,
        val acceptEncoding: String?,
        val tlsProtocol: String,
        val body: ByteArray,
    )

    private fun capture(exchange: HttpsExchange): Request =
        Request(
            exchange.requestMethod,
            exchange.requestURI.path,
            exchange.requestHeaders.getFirst("Authorization"),
            exchange.requestHeaders.getFirst("Content-Type"),
            exchange.requestHeaders.getFirst("Content-Length"),
            exchange.requestHeaders.getFirst("Transfer-Encoding"),
            exchange.requestHeaders.getFirst("Accept-Encoding"),
            exchange.sslSession.protocol,
            exchange.requestBody.use { it.readBytes() },
        )

    private fun reply(exchange: HttpsExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private class WireServer(handler: (HttpsExchange) -> Unit) : Closeable {
        private val failure = AtomicReference<Throwable?>()
        private val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "speech-wire-server").apply { isDaemon = true }
        }
        private val server =
            HttpsServer.create(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                    0,
                )
                .apply {
                    httpsConfigurator = HttpsConfigurator(serverTls)
                    this.executor = this@WireServer.executor
                    createContext("/") { exchange ->
                        try {
                            handler(exchange as HttpsExchange)
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                        } finally {
                            exchange.close()
                        }
                    }
                    start()
                }
        val endpoint =
            requireNotNull(RemoteSpeechEndpoint.parse("https://localhost:${server.address.port}"))

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            assertTrue("HTTPS fixture did not stop", executor.awaitTermination(3, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("HTTPS fixture failed", it) }
        }
    }

    companion object {
        @ClassRule @JvmField val temporary = TemporaryFolder()
        private lateinit var serverTls: SSLContext
        private lateinit var clientTls: SSLContext
        private const val READY =
            """{"ready":true,"busy":false,"model":"large-v3-turbo","device":"cuda"}"""

        @BeforeClass
        @JvmStatic
        fun generateEphemeralLocalhostCertificate() {
            val keyStoreFile = File(temporary.root, "loopback.p12")
            val keytoolLog = File(temporary.root, "keytool.log")
            val password = UUID.randomUUID().toString()
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process =
                ProcessBuilder(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        "loopback",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keyStoreFile.absolutePath,
                        "-storepass",
                        password,
                        "-keypass",
                        password,
                        "-noprompt",
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(keytoolLog)
                    .start()
            try {
                assertTrue("keytool did not finish", process.waitFor(20, TimeUnit.SECONDS))
                assertEquals("keytool failed", 0, process.exitValue())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    assertTrue("keytool did not stop", process.waitFor(3, TimeUnit.SECONDS))
                }
            }
            val keys =
                KeyStore.getInstance("PKCS12").apply {
                    keyStoreFile.inputStream().use { load(it, password.toCharArray()) }
                }
            val keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keys, password.toCharArray())
                }
            serverTls =
                SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
            val trustStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("loopback", keys.getCertificate("loopback"))
                }
            val trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(trustStore)
                }
            clientTls =
                SSLContext.getInstance("TLS").apply {
                    init(null, trustManagers.trustManagers, null)
                }
        }
    }
}

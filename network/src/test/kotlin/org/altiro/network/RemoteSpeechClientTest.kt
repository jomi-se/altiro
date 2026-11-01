package org.altiro.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.altiro.core.Wav
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteSpeechClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint =
        requireNotNull(RemoteSpeechEndpoint.parse("https://speech.example.net:8443"))
    private val ready = """{"ready":true,"busy":false,"model":"large-v3-turbo","device":"cuda"}"""

    @Test
    fun healthUsesOnlySameOriginGetAndRequiresReadyGpuModelAndIdleSlot() {
        val destinations = mutableListOf<URI>()
        val connection = FakeConnection(body = ready.toByteArray())
        val client =
            RemoteSpeechClient(
                "test-token",
                {
                    destinations.add(it)
                    connection
                },
            )
        assertTrue(client.health(endpoint))
        assertEquals(listOf(URI("https://speech.example.net:8443/health")), destinations)
        assertEquals("GET", connection.requestMethod)
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(1200, connection.connectTimeout)
        assertEquals(1200, connection.readTimeout)
        assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
        val cases =
            listOf(
                ready.replace("\"ready\":true", "\"ready\":false") to "remote_not_ready",
                ready.replace("\"busy\":false", "\"busy\":true") to "remote_busy",
                ready.replace("cuda", "cpu") to "remote_incompatible_server",
                ready.replace("large-v3-turbo", "other") to "remote_incompatible_server",
                ready.replace("\"ready\":true", "\"ready\":\"true\"") to "remote_invalid_response",
                ready.replace("\"busy\":false,", "") to "remote_invalid_response",
            )
        cases.forEach { (body, code) ->
            expectCode(code) { client(FakeConnection(body = body.toByteArray())).health(endpoint) }
        }
    }

    @Test
    fun uploadStreamsCanonicalWavAndOnlyExplicitFieldsThenPreservesUnicodeAndEmptyText() {
        val audio = wav(9000)
        val connection =
            FakeConnection(
                body =
                    """{"text":"¡Hola! \"sí\"\n\uD83D\uDE80","ignored":{"a":[1,-2.5e+3,true,null]}}"""
                        .toByteArray()
            )
        var opens = 0
        val client =
            RemoteSpeechClient(
                "test-token",
                {
                    assertEquals(endpoint.uri, it)
                    opens++
                    connection
                },
            )
        assertEquals("¡Hola! \"sí\"\n🚀", client.transcribe(endpoint, audio, "es", "José; María"))
        assertEquals(1, opens)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("Bearer test-token", connection.getRequestProperty("Authorization"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        val sent = connection.sent.toByteArray()
        assertEquals(sent.size.toLong(), connection.fixedLength)
        val form = sent.toString(Charsets.UTF_8)
        assertTrue(form.contains("name=\"model\"\r\n\r\nlarge-v3-turbo\r\n"))
        assertTrue(form.contains("name=\"language\"\r\n\r\nes\r\n"))
        assertTrue(form.contains("name=\"prompt\"\r\n\r\nJosé; María\r\n"))
        assertTrue(form.contains("filename=\"recording.wav\""))
        assertFalse(form.contains(audio.name))
        assertTrue(connection.largestWrite <= 8192)
        assertTrue(audio.exists())
        assertEquals("", client(FakeConnection()).transcribe(endpoint, audio, "auto"))
    }

    @Test
    fun statusErrorsNeverReadBodyNeverFollowRedirectsAndNeverReplayPost() {
        val audio = wav()
        for ((status, code) in
            listOf(
                301 to "remote_redirect",
                302 to "remote_redirect",
                307 to "remote_redirect",
                308 to "remote_redirect",
                401 to "remote_unauthorized",
                403 to "remote_unauthorized",
                413 to "remote_audio_too_large",
                429 to "remote_busy",
                503 to "remote_not_ready",
                500 to "remote_server_error",
                422 to "remote_http_error",
            )) {
            val connection = FakeConnection(status, "private remote error".toByteArray())
            var opens = 0
            val client =
                RemoteSpeechClient(
                    "test-token",
                    {
                        opens++
                        connection
                    },
                )
            val error = expectCode(code) { client.transcribe(endpoint, audio, "auto") }
            assertEquals(code, error.message)
            assertNull(error.cause)
            assertEquals(1, opens)
            assertEquals(0, connection.bodyOpens)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(audio.exists())
        }
        var opens = 0
        val broken =
            object : FakeConnection() {
                override fun getResponseCode(): Int =
                    throw IOException("private hostname and secret")
            }
        expectCode("remote_transport_error") {
            RemoteSpeechClient(
                    null,
                    {
                        opens++
                        broken
                    },
                )
                .transcribe(endpoint, audio, "auto")
        }
        assertEquals(1, opens)
    }

    @Test
    fun strictJsonRejectsWrongTypesTrailingDataDuplicatesAndMalformedUnicode() {
        val audio = wav()
        for (body in
            listOf(
                "{}",
                "{\"text\":null}",
                "{\"text\":123}",
                "{\"text\":true}",
                "[]",
                "{\"text\":\"ok\"} trailing",
                "{\"text\":\"one\",\"text\":\"two\"}",
                "{\"text\":\"\\uD800\"}",
                "{\"text\":\"\\uDC00\"}",
                "{\"text\":\"\\q\"}",
                "{\"text\":\"\\u１２３４\"}",
                "{\"text\":\"raw\nnewline\"}",
                "{\"text\":\"ok\",}",
                "{\"text\":\"ok\",\"x\":01}",
                "{\"text\":\"ok\",\"x\":1.}",
                "{\"text\":\"ok\",\"x\":1e}",
                "{\"text\":\"ok\",\"x\":[1,]}",
                "{\"text\":\"ok\",\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
            )) expectCode("remote_invalid_response") {
            client(FakeConnection(body = body.toByteArray())).transcribe(endpoint, audio, "auto")
        }
        expectCode("remote_invalid_response") {
            val invalid =
                byteArrayOf(
                    0x7b,
                    0x22,
                    0x74,
                    0x65,
                    0x78,
                    0x74,
                    0x22,
                    0x3a,
                    0x22,
                    0xc3.toByte(),
                    0x28,
                    0x22,
                    0x7d,
                )
            client(FakeConnection(body = invalid)).transcribe(endpoint, audio, "auto")
        }
    }

    @Test
    fun suppliedGpuIdentityMustMatchAndTranscriptLimitCountsUtf8Bytes() {
        val audio = wav()
        for (identity in
            listOf(
                "\"model\":\"other\"",
                "\"device\":\"cpu\"",
                "\"device\":null",
            )) expectCode("remote_incompatible_server") {
            client(FakeConnection(body = "{\"text\":\"ok\",$identity}".toByteArray()))
                .transcribe(endpoint, audio, "auto")
        }
        val accepted = "é".repeat(32768)
        assertEquals(
            accepted,
            client(FakeConnection(body = "{\"text\":\"$accepted\"}".toByteArray()))
                .transcribe(endpoint, audio, "auto"),
        )
        for (text in listOf("x".repeat(65537), accepted + "x")) {
            expectCode("remote_transcript_too_large") {
                client(FakeConnection(body = "{\"text\":\"$text\"}".toByteArray()))
                    .transcribe(endpoint, audio, "auto")
            }
        }
    }

    @Test
    fun boundsApplyToKnownAndUnknownResponseLengthsAndRejectCompressedOrNonJsonBodies() {
        val audio = wav()
        for (declared in listOf(-1L, 1024L * 1024 + 1)) {
            val connection = FakeConnection(body = ByteArray(1024 * 1024 + 1) { ' '.code.toByte() })
            connection.declaredLength = declared
            expectCode("remote_response_too_large") {
                client(connection).transcribe(endpoint, audio, "auto")
            }
            if (declared > 0) assertEquals(0, connection.bodyOpens)
        }
        expectCode("remote_response_too_large") {
            client(FakeConnection(body = ByteArray(16385))).health(endpoint)
        }
        for (encoding in listOf("gzip", "br")) {
            val connection = FakeConnection()
            connection.encoding = encoding
            expectCode("remote_invalid_response") {
                client(connection).transcribe(endpoint, audio, "auto")
            }
            assertEquals(0, connection.bodyOpens)
        }
        val html = FakeConnection()
        html.mediaType = "text/html"
        expectCode("remote_invalid_response") { client(html).transcribe(endpoint, audio, "auto") }
    }

    @Test
    fun excessiveOrInvalidAudioAndOptionsAreRejectedBeforeOpeningNetwork() {
        var opens = 0
        val client =
            RemoteSpeechClient(
                null,
                {
                    opens++
                    FakeConnection()
                },
            )
        val audio = wav()
        expectCode("remote_invalid_options") { client.transcribe(endpoint, audio, "de") }
        expectCode("remote_invalid_options") {
            client.transcribe(endpoint, audio, "es", "é".repeat(2049))
        }
        expectCode("remote_invalid_options") { client.transcribe(endpoint, audio, "es", "\uD800") }
        val bad = temporary.newFile().apply { writeBytes(ByteArray(46)) }
        expectCode("remote_invalid_audio") { client.transcribe(endpoint, bad, "auto") }
        val tooLong = temporary.newFile()
        RandomAccessFile(tooLong, "rw").use { it.setLength(44L + (Wav.MAX_FRAMES + 1L) * 2) }
        expectCode("remote_audio_too_long") { client.transcribe(endpoint, tooLong, "auto") }
        val tooLarge = temporary.newFile()
        RandomAccessFile(tooLarge, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        expectCode("remote_audio_too_large") { client.transcribe(endpoint, tooLarge, "auto") }
        expectCode("remote_invalid_options") {
            RemoteSpeechClient(
                    "secret\r\nInjected: yes",
                    {
                        opens++
                        FakeConnection()
                    },
                )
                .transcribe(endpoint, audio, "auto")
        }
        assertEquals(0, opens)
    }

    @Test
    fun cancellationDuringConnectDisconnectsBeforeAnyUploadAndClientCanBeReused() {
        val entered = CountDownLatch(1)
        val connection =
            object : FakeConnection() {
                override fun connect() {
                    entered.countDown()
                    awaitIgnoringInterrupts(disconnected)
                }
            }
        val next = AtomicReference<FakeConnection>(connection)
        val client = RemoteSpeechClient(null, { next.get() })
        val audio = wav()
        val result = background { client.transcribe(endpoint, audio, "auto") }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        client.cancel()
        result.assertCode("remote_cancelled")
        assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
        assertEquals(0, connection.outputOpens)
        assertTrue(audio.exists())
        next.set(FakeConnection())
        assertEquals("", client.transcribe(endpoint, audio, "auto"))
    }

    @Test
    fun predicateCancellationDuringHealthOrPostReadClosesStreamAndCannotReturnLateSuccess() {
        for (health in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val streamClosed = CountDownLatch(1)
            val connection =
                object : FakeConnection(body = ready.toByteArray()) {
                    override fun getInputStream(): InputStream =
                        object : InputStream() {
                            override fun read(): Int {
                                entered.countDown()
                                awaitIgnoringInterrupts(disconnected)
                                return -1
                            }

                            override fun close() {
                                streamClosed.countDown()
                            }
                        }
                }
            val cancelled = AtomicBoolean(false)
            val audio = wav()
            val result = background {
                if (health) client(connection).health(endpoint, cancelled::get)
                else
                    client(connection)
                        .transcribe(endpoint, audio, "auto", cancelled = cancelled::get)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            result.assertCode("remote_cancelled")
            assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
            assertTrue(streamClosed.await(1, TimeUnit.SECONDS))
            assertTrue(audio.exists())
        }
    }

    @Test
    fun overallDeadlineReturnsEvenWhenPlatformConnectIgnoresDisconnectAndInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val connection =
            object : FakeConnection(body = ready.toByteArray()) {
                override fun connect() {
                    entered.countDown()
                    try {
                        awaitIgnoringInterrupts(release)
                    } finally {
                        exited.countDown()
                    }
                }
            }
        try {
            val client = RemoteSpeechClient(null, { connection }, healthDeadlineMillis = 250)
            val result = background { client.health(endpoint) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            result.assertCode("remote_timeout")
            assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
            assertEquals(0, connection.bodyOpens)
        } finally {
            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancelBeforeRequestNeverOpensConnection() {
        var opens = 0
        val client =
            RemoteSpeechClient(
                null,
                {
                    opens++
                    FakeConnection()
                },
            )
        expectCode("remote_cancelled") { client.health(endpoint) { true } }
        expectCode("remote_cancelled") {
            client.transcribe(endpoint, wav(), "auto", cancelled = { true })
        }
        assertEquals(0, opens)
    }

    @Test
    fun callerPredicateExceptionStillDisconnectsOutstandingHealthRequest() {
        val entered = CountDownLatch(1)
        val connection =
            object : FakeConnection() {
                override fun connect() {
                    entered.countDown()
                    awaitIgnoringInterrupts(disconnected)
                }
            }
        val caller = AtomicReference<Thread?>()
        val result = background {
            caller.set(Thread.currentThread())
            client(connection).health(endpoint) {
                if (Thread.currentThread() === caller.get() && entered.count == 0L)
                    throw java.util.concurrent.CancellationException()
                false
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        result.thread.join(1500)
        assertFalse(result.thread.isAlive)
        assertTrue(result.result.get() is java.util.concurrent.CancellationException)
        assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
    }

    private fun wav(frames: Int = 1): File =
        temporary.newFile().apply {
            outputStream().use {
                it.write(Wav.header(frames.toLong()))
                it.write(ByteArray(frames * 2))
            }
        }

    private fun client(connection: FakeConnection) = RemoteSpeechClient(null, { connection })

    private fun expectCode(code: String, action: () -> Unit): RemoteSpeechException {
        val error = assertThrows(RemoteSpeechException::class.java, action)
        assertEquals(code, error.code)
        return error
    }

    private fun background(action: () -> Any?): Background {
        val result = AtomicReference<Throwable?>()
        val thread =
            Thread {
                    try {
                        action()
                    } catch (error: Throwable) {
                        result.set(error)
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        return Background(thread, result)
    }

    private class Background(val thread: Thread, val result: AtomicReference<Throwable?>) {
        fun assertCode(code: String) {
            thread.join(1500)
            assertFalse("Request failed to finish promptly", thread.isAlive)
            assertEquals(code, (result.get() as? RemoteSpeechException)?.code)
        }
    }

    private open class FakeConnection(
        private val status: Int = 200,
        private val body: ByteArray = "{\"text\":\"\"}".toByteArray(),
    ) : HttpURLConnection(URL("https://speech.example.net:8443/v1/audio/transcriptions")) {
        val sent = ByteArrayOutputStream()
        val disconnected = CountDownLatch(1)
        var bodyOpens = 0
        var outputOpens = 0
        var fixedLength = -1L
        var largestWrite = 0
        var declaredLength = -1L
        var encoding: String? = null
        var mediaType = "application/json; charset=utf-8"

        override fun connect() = Unit

        override fun disconnect() {
            disconnected.countDown()
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getContentLengthLong() = declaredLength

        override fun getContentEncoding() = encoding

        override fun getContentType() = mediaType

        override fun setFixedLengthStreamingMode(length: Long) {
            fixedLength = length
        }

        override fun getInputStream(): InputStream {
            bodyOpens++
            return ByteArrayInputStream(body)
        }

        override fun getOutputStream(): OutputStream {
            outputOpens++
            return object : OutputStream() {
                override fun write(value: Int) {
                    sent.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    largestWrite = maxOf(largestWrite, length)
                    sent.write(bytes, offset, length)
                }
            }
        }
    }

    companion object {
        private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    /* Platform-like block. */
                }
            }
        }
    }
}

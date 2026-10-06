package org.altiro.network

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.altiro.core.ModelSpec
import org.altiro.core.VerifiedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val payload = ByteArray(200_000) { (it * 31).toByte() }
    private val spec = spec("new", payload)
    private val previous = "previous verified model".toByteArray()
    private val source =
        URI("https://huggingface.co/owner/repo/resolve/${"a".repeat(40)}/ggml-test.bin")
    private val cdn = "https://us.aws.cdn.hf.co/xet-bridge-us/abc?signature=secret"

    private class Reply(
        override val status: Int,
        override val location: String? = null,
        override val contentLength: Long = -1,
        override val contentEncoding: String? = null,
        val stream: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) },
    ) : HttpResponse {
        override fun body() = stream()

        override fun close() = Unit
    }

    private class Script(vararg replies: (URI) -> HttpResponse) : HttpTransport {
        private val queue = ArrayDeque(replies.toList())
        val opened = mutableListOf<URI>()

        override fun open(
            uri: URI,
            register: (Closeable) -> Unit,
        ): HttpResponse {
            opened += uri
            return queue.removeFirst()(uri)
        }
    }

    @Test
    fun onlyPinnedResolveUrlsAreBinarySources() {
        val commit = "5359861c739e955e79d9a303bcbc70fb988958b1"
        fun direct(url: String) = ModelSourcePolicy.directDownload(url, "ggml-small.bin")
        assertNotNull(
            direct("https://huggingface.co/ggerganov/whisper.cpp/resolve/$commit/ggml-small.bin")
        )
        // The Chilean entries point at a training-model page, which is attribution only.
        assertNull(
            direct("https://huggingface.co/rcastrovexler/whisper-small-es-cl-2/tree/$commit")
        )
        assertNull(
            direct("http://huggingface.co/ggerganov/whisper.cpp/resolve/$commit/ggml-small.bin")
        )
        assertNull(
            direct("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin")
        )
        assertNull(
            direct("https://huggingface.co/ggerganov/whisper.cpp/resolve/$commit/ggml-base.bin")
        )
        assertNull(
            direct("https://u@huggingface.co/ggerganov/whisper.cpp/resolve/$commit/ggml-small.bin")
        )
        assertNull(
            direct(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/$commit/ggml-small.bin?x=1"
            )
        )
        assertNull(direct("https://huggingface.co.evil.example/a/b/resolve/$commit/ggml-small.bin"))
        assertTrue(ModelSourcePolicy.trustedHop(URI(cdn)))
        assertTrue(!ModelSourcePolicy.trustedHop(URI("https://evilhf.co/file")))
        assertTrue(!ModelSourcePolicy.trustedHop(URI("http://us.aws.cdn.hf.co/file")))
    }

    @Test
    fun followsReviewedRedirectAndInstallsVerifiedFile() {
        val destination = File(folder.root, "model.bin")
        val transport =
            Script(
                { Reply(302, location = cdn) },
                {
                    Reply(
                        200,
                        contentLength = spec.bytes,
                        stream = { ByteArrayInputStream(payload) },
                    )
                },
            )
        var last = -1
        val outcome =
            ModelDownloader(transport).install(source, destination, spec, DownloadCancellation()) {
                last = it
            }
        assertEquals(DownloadOutcome.Installed, outcome)
        assertEquals(listOf(source, URI(cdn)), transport.opened)
        assertEquals(100, last)
        assertTrue(VerifiedModel.matches(destination, spec))
    }

    @Test
    fun rejectsDowngradeForeignHostAndRedirectLoops() {
        for (target in listOf("http://us.aws.cdn.hf.co/file", "https://example.com/file")) {
            val transport = Script({ Reply(302, location = target) })
            assertEquals(
                DownloadOutcome.Failed(DownloadFailure.UNTRUSTED_REDIRECT),
                ModelDownloader(transport)
                    .install(source, existing(), spec, DownloadCancellation()),
            )
            assertEquals(listOf(source), transport.opened)
        }
        val loop = Script(*Array(4) { { _: URI -> Reply(307, location = "/again") } })
        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.TOO_MANY_REDIRECTS),
            ModelDownloader(loop, maxRedirects = 2)
                .install(source, existing(), spec, DownloadCancellation()),
        )
        assertEquals(URI("https://huggingface.co/again"), loop.opened.last())
    }

    @Test
    fun interruptedOrCorruptTransferPreservesInstalledModel() {
        val destination = existing()
        val interrupted =
            Script({
                Reply(200, stream = { failingAfter(payload.size / 2) })
            })
        val corrupt =
            Script({
                Reply(200, stream = { ByteArrayInputStream(payload.copyOf().also { it[7]++ }) })
            })
        val mislabeled = Script({ Reply(200, contentLength = spec.bytes + 1) })
        for ((transport, failure) in
            listOf(
                interrupted to DownloadFailure.INTERRUPTED,
                corrupt to DownloadFailure.VERIFICATION_FAILED,
                mislabeled to DownloadFailure.VERIFICATION_FAILED,
            )) {
            assertEquals(
                DownloadOutcome.Failed(failure),
                ModelDownloader(transport)
                    .install(source, destination, spec, DownloadCancellation()),
            )
            assertTrue(VerifiedModel.matches(destination, spec("previous", previous)))
            assertEquals(listOf("model.bin"), folder.root.list()!!.toList())
        }
    }

    @Test
    fun cancelClosesStalledReadAndLeavesNoPartialFile() {
        val destination = existing()
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stalled =
            object : InputStream() {
                private var sent = false

                override fun read() = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!sent) {
                        sent = true
                        return len.coerceAtMost(1024)
                    }
                    reading.countDown()
                    released.await()
                    throw IOException("socket closed")
                }
            }
        val transport = HttpTransport { _, register ->
            register(Closeable { released.countDown() })
            Reply(200, stream = { stalled })
        }
        val cancellation = DownloadCancellation(closer = Closeable::close)
        var outcome: DownloadOutcome? = null
        val worker = thread {
            outcome = ModelDownloader(transport).install(source, destination, spec, cancellation)
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        cancellation.cancel()
        worker.join(5_000)
        assertEquals(DownloadOutcome.Cancelled, outcome)
        assertTrue(VerifiedModel.matches(destination, spec("previous", previous)))
        assertEquals(listOf("model.bin"), folder.root.list()!!.toList())
    }

    @Test
    fun refusesBeforeConnectingWhenCancelledOrShortOfStorage() {
        val transport = Script()
        val cancelled = DownloadCancellation().apply { cancel() }
        assertEquals(
            DownloadOutcome.Cancelled,
            ModelDownloader(transport).install(source, existing(), spec, cancelled),
        )
        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.INSUFFICIENT_STORAGE),
            ModelDownloader(transport, availableBytes = { spec.bytes })
                .install(source, existing(), spec, DownloadCancellation()),
        )
        assertEquals(emptyList<URI>(), transport.opened)
    }

    @Test
    fun mapsHostAndNetworkFailuresToSanitizedCategories() {
        fun outcome(reply: (URI) -> HttpResponse) =
            ModelDownloader(Script(reply)).install(source, existing(), spec, DownloadCancellation())
        assertEquals(DownloadOutcome.Failed(DownloadFailure.UNAVAILABLE), outcome { Reply(404) })
        assertEquals(DownloadOutcome.Failed(DownloadFailure.SERVER_BUSY), outcome { Reply(503) })
        assertEquals(DownloadOutcome.Failed(DownloadFailure.REFUSED), outcome { Reply(403) })
        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.OFFLINE),
            outcome { throw UnknownHostException("huggingface.co") },
        )
    }

    @Test
    fun transportRefusesPlainHttpWithoutConnecting() {
        var registered = false
        val error =
            runCatching {
                    UrlConnectionTransport().open(URI("http://127.0.0.1:9/model")) {
                        registered = true
                    }
                }
                .exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(!registered)
    }

    private fun existing(): File =
        File(folder.root, "model.bin").apply { if (!isFile) writeBytes(previous) }

    private fun failingAfter(count: Int): InputStream =
        object : InputStream() {
            private val data = ByteArrayInputStream(payload, 0, count)

            override fun read() = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                data.read(b, off, len).takeIf { it >= 0 } ?: throw IOException("reset")
        }

    private fun spec(
        id: String,
        bytes: ByteArray,
    ) =
        ModelSpec(
            id,
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            },
        )
}

package dev.hackatlas.mobile.data.network

import dev.hackatlas.mobile.data.CanonicalFixtures
import dev.hackatlas.mobile.data.RemoteCatalogRepository
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.domain.CatalogError
import dev.hackatlas.mobile.domain.CatalogException
import dev.hackatlas.mobile.domain.CatalogQuery
import dev.hackatlas.mobile.domain.CatalogSource
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.*

/** Real Android/JVM engine and TCP sockets, not MockEngine. No external network. */
class PlatformHttpEngineTest {
    @Test fun publicGetRecoversWhenAnIdleConnectionDiesOnReuse(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val received = Collections.synchronizedList(mutableListOf<String>())
            val worker = Executors.newSingleThreadExecutor { Thread(it, "catalog-test-server").apply { isDaemon = true } }
            val serving = worker.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    val reader = socket.getInputStream().bufferedReader()
                    received += request(reader)
                    respond(socket, 200, CanonicalFixtures.files.getValue("catalog-page.json"))
                    received += request(reader)
                    // A network change can leave this pooled socket unusable. Drop
                    // the next GET before responding; the client must use a new socket.
                }
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    received += request(socket.getInputStream().bufferedReader())
                    respond(socket, 200, CanonicalFixtures.files.getValue("hackathon-detail.json"))
                }
            }
            val source = CatalogSource.Remote("http://127.0.0.1:${server.localPort}/api/v1/", allowDebugHttp = true)
            val engine = createPlatformHttpEngine()
            val client = createApiClient(engine, source)
            try {
                val repo = RemoteCatalogRepository(client, source)
                val id = repo.catalog(CatalogQuery()).items.first().id
                assertEquals(id, repo.detail(id).hackathon.id)
                serving.get(8, TimeUnit.SECONDS)
                assertEquals(3, received.size)
                assertTrue(received.all { it.startsWith("GET /api/v1/hackathons") })
                assertEquals(received[1], received[2])
            } finally {
                client.close()
                engine.close()
                server.close()
                worker.shutdownNow()
                worker.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun serverErrorIsStillExposedWithoutAutomaticHttpRetry(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val worker = Executors.newSingleThreadExecutor { Thread(it, "catalog-test-server").apply { isDaemon = true } }
            val serving = worker.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    request(socket.getInputStream().bufferedReader())
                    respond(socket, 503, """{"type":"about:blank","title":"Unavailable","status":503}""")
                }
            }
            val source = CatalogSource.Remote("http://127.0.0.1:${server.localPort}/api/v1/", allowDebugHttp = true)
            val engine = createPlatformHttpEngine()
            val client = createApiClient(engine, source)
            try {
                val failure = assertFailsWith<CatalogException> { RemoteCatalogRepository(client, source).catalog(CatalogQuery()) }
                assertEquals(CatalogError.Http(503, null), failure.error)
                serving.get(8, TimeUnit.SECONDS)
            } finally {
                client.close()
                engine.close()
                server.close()
                worker.shutdownNow()
                worker.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun verificationPostsAreNotReplayedWhenProxyReturns503WithImmediateRetry(): Unit = runBlocking {
        for (confirm in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 8_000
                val source = CatalogSource.Remote("http://127.0.0.1:${server.localPort}/api/v1/", allowDebugHttp = true)
                val disk = object : SecureTokenStore {
                    override val apiBaseUrl = source.baseUrl
                    override suspend fun read() = StoredSession(
                        VerificationHarness.bearer, "2030-01-01T00:00:00Z",
                        VerificationHarness.pending.body!!.getValue("id").toString().trim('"'),
                        "11111111-1111-4111-8111-111111111111",
                    )
                    override suspend fun write(session: StoredSession) = error("Unexpected session write")
                    override suspend fun clear() = error("Unexpected session clear")
                }
                val worker = Executors.newSingleThreadExecutor()
                val serving = worker.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        val reader = socket.getInputStream().bufferedReader()
                        assertTrue(request(reader).startsWith("GET /api/v1/me "))
                        respond(socket, 200, VerificationHarness.pending.body.toString())
                        val action = if (confirm) "confirm" else "request"
                        assertTrue(request(reader).startsWith("POST /api/v1/auth/email-verification/$action "))
                        respond(socket, 503, """{"type":"about:blank","title":"Unavailable","status":503,"detail":"Retry manually","instance":"/api/v1/auth/email-verification/$action","requestId":"test","code":"SERVICE_UNAVAILABLE","fieldErrors":[]}""",
                            "Retry-After: 0\r\n", "application/problem+json")
                        socket.soTimeout = 1200
                        try {
                            request(reader)
                            respond(socket, 204, "")
                            2
                        } catch (_: SocketTimeoutException) {
                            1
                        }
                    }
                }
                val session = AuthSessionClient(createAuthHttpEngine(), source, disk, AuthProtocol.CLOSED_N2) {
                    Instant.parse("2026-11-10T12:00:00Z")
                }
                try {
                    session.restore()
                    val owner = assertNotNull(session.localIdentity.value)
                    val client = N2AccountClient(session)
                    val failure = assertFailsWith<AuthException> {
                        if (confirm) client.confirmVerification(owner,
                            EmailActionChallenge(VerificationHarness.challengeId, Instant.parse("2026-11-10T12:10:00Z"), 60), "01234567")
                        else client.requestVerification(owner)
                    }
                    assertEquals(N2HttpAction.RETRY_MANUALLY, assertIs<AuthError.Http>(failure.error).n2Action)
                    assertEquals(1, serving.get(8, TimeUnit.SECONDS))
                } finally {
                    session.close()
                    server.close()
                    worker.shutdownNow()
                }
            }
        }
    }

    private fun request(reader: java.io.BufferedReader): String {
        val line = reader.readLine() ?: error("Connection closed before request")
        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: error("Connection closed before headers")
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true))
                contentLength = header.substringAfter(':').trim().toInt()
        }
        repeat(contentLength) { check(reader.read() >= 0) }
        return line
    }

    private fun respond(socket: Socket, status: Int, body: String, headers: String = "", mediaType: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status Result\r\nContent-Type: $mediaType\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: keep-alive\r\n$headers\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }
}

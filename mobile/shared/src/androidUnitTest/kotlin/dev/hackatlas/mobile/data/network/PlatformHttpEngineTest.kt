package dev.hackatlas.mobile.data.network

import dev.hackatlas.mobile.data.CanonicalFixtures
import dev.hackatlas.mobile.data.RemoteCatalogRepository
import dev.hackatlas.mobile.domain.CatalogError
import dev.hackatlas.mobile.domain.CatalogException
import dev.hackatlas.mobile.domain.CatalogQuery
import dev.hackatlas.mobile.domain.CatalogSource
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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

    private fun request(reader: java.io.BufferedReader): String {
        val line = reader.readLine() ?: error("Connection closed before request")
        while (true) {
            val header = reader.readLine() ?: error("Connection closed before headers")
            if (header.isEmpty()) return line
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }
}

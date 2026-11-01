package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.data.HackathonPageDto
import dev.hackatlas.mobile.data.toDomain
import dev.hackatlas.mobile.domain.CatalogPage
import dev.hackatlas.mobile.data.network.createApiClient
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.call.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.Serializable

/** Owns an isolated client and engine; use a dedicated engine, with retries/redirects disabled. */
class AuthSessionClient(
    private val engine: HttpClientEngine,
    private val source: CatalogSource.Remote,
    private val store: SecureTokenStore,
    private val now: () -> Instant = { Clock.System.now() },
) {
    init { require(source.baseUrl == store.apiBaseUrl) { "Auth store API scope mismatch" } }
    private val client = createApiClient(engine, source)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.NotLoaded)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    private val mutableIdentity = MutableStateFlow<LocalSessionIdentity?>(null)
    val localIdentity = mutableIdentity.asStateFlow()

    suspend fun register(email: String, password: String, displayName: String): ProfileDto = mutex.withLock {
        authenticate("auth/register", Register(email, password, displayName), 201)
    }

    suspend fun login(email: String, password: String): ProfileDto = mutex.withLock {
        authenticate("auth/login", Login(email, password), 200)
    }

    suspend fun restore(): ProfileDto? = mutex.withLock {
        val session = readSession() ?: run { mutableState.value = AuthState.SignedOut; return@withLock null }
        profile(session)
    }

    suspend fun profile(): ProfileDto = mutex.withLock {
        profile(readSession() ?: throw AuthException(AuthError.Unauthorized))
    }

    suspend fun listSaved(expectedUserId: String, page: Int = 0, size: Int = 50): CatalogPage = mutex.withLock {
        require(page >= 0 && size in 1..50)
        val session = ownedSession(expectedUserId)
        val response = transport { client.get(source.baseUrl + "me/saved") {
            bearerAuth(session.accessToken); parameter("page", page); parameter("size", size)
        } }
        requireProtected(response, 200)
        val dto = decode<HackathonPageDto>(response)
        invalidResponse {
            require(dto.page == page && dto.size == size && dto.totalElements >= 0 && dto.totalPages >= 0)
            require(dto.items.size <= size && dto.items.size <= dto.totalElements)
            require(dto.totalPages == (dto.totalElements.toLong() + size - 1).div(size).toInt())
            require(dto.hasNext == (page.toLong() + 1 < dto.totalPages))
            Instant.parse(dto.asOf)
            CatalogPage(dto.items.map { it.toDomain() }, dto.page, dto.size, dto.totalElements, dto.totalPages, dto.hasNext, dto.asOf)
        }
    }

    suspend fun save(expectedUserId: String, id: String) = mutateSaved(expectedUserId, id, HttpMethod.Put)
    suspend fun unsave(expectedUserId: String, id: String) = mutateSaved(expectedUserId, id, HttpMethod.Delete)

    private suspend fun mutateSaved(expectedUserId: String, id: String, verb: HttpMethod) = mutex.withLock {
        require(USER_ID.matches(id))
        val session = ownedSession(expectedUserId)
        val response = transport { client.request(source.baseUrl + "me/saved/$id") {
            method = verb; bearerAuth(session.accessToken)
        } }
        requireProtected(response, 204)
    }

    private suspend fun ownedSession(expectedUserId: String): StoredSession {
        require(USER_ID.matches(expectedUserId))
        val session = readSession() ?: run { localClear(AuthState.SignInRequired); throw AuthException(AuthError.Unauthorized) }
        if (session.userId != expectedUserId) throw AuthException(AuthError.Unauthorized)
        return session
    }

    private suspend fun requireProtected(response: HttpResponse, expected: Int) {
        if (response.status.value == 401) {
            localClear(AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        if (response.status.value != expected) throw AuthException(response.error())
    }

    suspend fun logout(): LogoutResult = mutex.withLock {
        try {
            val session = readStored()
            if (session == null) LogoutResult.NoLocalSession
            else if (Instant.parse(session.expiresAt) <= now()) LogoutResult.AlreadyInvalid
            else {
                val response = transport { client.post(source.baseUrl + "auth/logout") { bearerAuth(session.accessToken) } }
                when (response.status.value) {
                    204 -> LogoutResult.Revoked
                    401 -> LogoutResult.AlreadyInvalid
                    else -> LogoutResult.LocalOnly(response.error())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: AuthException) {
            LogoutResult.LocalOnly(failed.error)
        } finally {
            localClear(AuthState.SignedOut)
        }
    }

    private suspend inline fun <reified T : Any> authenticate(path: String, body: T, expected: Int): ProfileDto {
        val response = transport { client.post(source.baseUrl + path) {
            contentType(ContentType.Application.Json); setBody(body)
        } }
        if (response.status.value != expected) throw AuthException(response.error())
        val dto = decode<AuthResponse>(response)
        val session = invalidResponse {
            require(dto.tokenType == "Bearer")
            validateProfile(dto.user)
            StoredSession(dto.accessToken, dto.expiresAt, dto.user.id).also {
                it.validate(); require(Instant.parse(it.expiresAt) > now())
            }
        }
        // Once received, persist and publish together, even if the caller is canceled during disk IO.
        withContext(NonCancellable) {
            storage { store.write(session) }
            mutableIdentity.value = LocalSessionIdentity(session.userId, session.expiresAt)
            mutableState.value = AuthState.Authenticated(dto.user)
        }
        return dto.user
    }

    private suspend fun profile(session: StoredSession): ProfileDto {
        val response = transport { client.get(source.baseUrl + "me") { bearerAuth(session.accessToken) } }
        if (response.status.value == 401) {
            localClear(AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        if (response.status.value != 200) throw AuthException(response.error())
        val user = decode<ProfileDto>(response)
        invalidResponse { validateProfile(user); require(user.id == session.userId) }
        mutableState.value = AuthState.Authenticated(user)
        return user
    }

    private suspend fun readSession(): StoredSession? {
        val session = readStored() ?: run { mutableIdentity.value = null; return null }
        if (Instant.parse(session.expiresAt) <= now()) {
            localClear(AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        mutableIdentity.value = LocalSessionIdentity(session.userId, session.expiresAt)
        return session
    }

    private suspend fun readStored(): StoredSession? = storage { store.read()?.also { it.validate() } }

    private suspend fun localClear(next: AuthState) = withContext(NonCancellable) {
        // Never leave an old authenticated profile visible after a failed clear.
        mutableIdentity.value = null
        mutableState.value = AuthState.StorageUnavailable
        storage { store.clear() }
        mutableState.value = next
    }

    private suspend inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        mutableIdentity.value = null
        mutableState.value = AuthState.StorageUnavailable
        throw AuthException(AuthError.Storage)
    }

    private suspend inline fun <reified T : Any> decode(response: HttpResponse): T = try {
        response.body<T>()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { throw AuthException(AuthError.InvalidResponse) }

    private inline fun <T> invalidResponse(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw AuthException(AuthError.InvalidResponse) }

    private fun validateProfile(user: ProfileDto) {
        require(USER_ID.matches(user.id) && user.displayName.isNotBlank() && user.email.isNotBlank())
        require(user.stats.savedCount >= 0 && user.stats.appliedCount >= 0 && user.stats.interestsCount >= 0)
    }

    private fun HttpResponse.error() = AuthError.Http(status.value,
        headers[HttpHeaders.RetryAfter]?.toIntOrNull()?.takeIf { it >= 0 })

    private suspend inline fun <T> transport(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: HttpRequestTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: ConnectTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: SocketTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: IOException) { throw AuthException(AuthError.Network) }

    fun close() { client.close(); engine.close() }
}

@Serializable private class Login(val email: String, val password: String) {
    override fun toString() = "Login([REDACTED])"
}
@Serializable private class Register(val email: String, val password: String, val displayName: String) {
    override fun toString() = "Register([REDACTED])"
}
@Serializable private class AuthResponse(val tokenType: String, val accessToken: String, val expiresAt: String, val user: ProfileDto) {
    override fun toString() = "AuthResponse([REDACTED])"
}

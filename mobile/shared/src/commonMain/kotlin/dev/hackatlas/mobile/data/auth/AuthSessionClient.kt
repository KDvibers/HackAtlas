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
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Owns an isolated client and engine; use a dedicated engine, with retries/redirects disabled. */
class AuthSessionClient(
    private val engine: HttpClientEngine,
    private val source: CatalogSource.Remote,
    private val store: SecureTokenStore,
    internal val protocol: AuthProtocol = AuthProtocol.LEGACY,
    private val clearPrivateCaches: suspend () -> Unit = {},
    private val now: () -> Instant = { Clock.System.now() },
) {
    init { require(source.baseUrl == store.apiBaseUrl) { "Auth store API scope mismatch" } }
    private val client = createApiClient(engine, source)
    private val mutex = Mutex()
    private val mutableView = MutableStateFlow(SessionView(access = PrivateAccess().takeIf { protocol == AuthProtocol.CLOSED_N2 }))
    internal val view = mutableView.asStateFlow()
    val state: StateFlow<AuthState> = view.project { it.state }
    val localIdentity: StateFlow<LocalSessionIdentity?> = view.project { it.identity }
    private var cleanupPending = false
    private var authenticationAttempt = 0L
    private val closed = MutableStateFlow(false)

    internal fun currentPrivateAccess(): PrivateAccess? = view.value.access?.takeUnless { closed.value }?.let { access ->
        if (access.identity?.let { Instant.parse(it.expiresAt) <= now() } == true)
            access.copy(trust = PrivateTrust.UNKNOWN) else access
    }

    internal suspend fun expire(identity: LocalSessionIdentity) = mutex.withLock {
        if (!closed.value && view.value.identity == identity && Instant.parse(identity.expiresAt) <= now())
            clearN2(identity.cleanupBasis(), AuthState.SignInRequired)
    }

    internal fun millisUntilExpiry(identity: LocalSessionIdentity): Long =
        (Instant.parse(identity.expiresAt) - now()).inWholeMilliseconds.coerceAtLeast(1)

    internal suspend fun privateTransportUnavailable(access: PrivateAccess?) = mutex.withLock {
        if (access?.canWrite == true && access == currentPrivateAccess())
            publish(state.value, access.identity, PrivateTrust.VERIFIED_OFFLINE)
    }

    suspend fun register(email: String, password: String, displayName: String): ProfileDto =
        authenticate("auth/register", Register(email, password, displayName), 201)

    suspend fun login(email: String, password: String): ProfileDto =
        authenticate("auth/login", Login(email, password), 200)

    suspend fun restore(): ProfileDto? = requestProfile(restore = true)

    suspend fun profile(): ProfileDto = requireNotNull(requestProfile(restore = false))

    suspend fun updateProfile(expectedUserId: String, patch: JsonObject, access: PrivateAccess? = null): ProfileDto {
        validateProfilePatch(patch)
        return protected(expectedUserId, access, 200, {
            client.patch(source.baseUrl + "me") {
                bearerAuth(it.accessToken); contentType(ContentType.Application.Json); setBody(patch)
            }
        }) { response, session -> acceptProfile(response, session, verifiedByGet = false) }
    }

    suspend fun listSaved(expectedUserId: String, page: Int = 0, size: Int = 50, access: PrivateAccess? = null): CatalogPage {
        require(page >= 0 && size in 1..50)
        return protected(expectedUserId, access, 200, {
            client.get(source.baseUrl + "me/saved") {
                bearerAuth(it.accessToken); parameter("page", page); parameter("size", size)
            }
        }) { response, _ ->
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
    }

    suspend fun save(expectedUserId: String, id: String, access: PrivateAccess? = null) =
        mutateSaved(expectedUserId, id, HttpMethod.Put, access)
    suspend fun unsave(expectedUserId: String, id: String, access: PrivateAccess? = null) =
        mutateSaved(expectedUserId, id, HttpMethod.Delete, access)

    suspend fun listApplications(expectedUserId: String, page: Int = 0, size: Int = 50, access: PrivateAccess? = null): ApplicationPage {
        require(page >= 0 && size in 1..50)
        return protected(expectedUserId, access, 200, {
            client.get(source.baseUrl + "me/applications") {
                bearerAuth(it.accessToken); parameter("page", page); parameter("size", size)
            }
        }) { response, _ -> decode<ApplicationPage>(response).also { dto -> invalidResponse { dto.validate(page, size) } } }
    }

    suspend fun markApplied(expectedUserId: String, id: String, access: PrivateAccess? = null): ApplicationMark {
        require(USER_ID.matches(id))
        return protected(expectedUserId, access, 200, {
            client.put(source.baseUrl + "me/applications/$id") { bearerAuth(it.accessToken) }
        }) { response, _ -> decode<ApplicationMark>(response).also { dto -> invalidResponse { dto.validate(id) } } }
    }

    suspend fun removeApplication(expectedUserId: String, id: String, access: PrivateAccess? = null) {
        require(USER_ID.matches(id))
        protected(expectedUserId, access, 204, {
            client.delete(source.baseUrl + "me/applications/$id") { bearerAuth(it.accessToken) }
        }) { _, _ -> }
    }

    private suspend fun mutateSaved(expectedUserId: String, id: String, verb: HttpMethod, access: PrivateAccess?) {
        require(USER_ID.matches(id))
        protected(expectedUserId, access, 204, {
            client.request(source.baseUrl + "me/saved/$id") { method = verb; bearerAuth(it.accessToken) }
        }) { _, _ -> }
    }

    private suspend fun ownedSession(expectedUserId: String): StoredSession {
        require(USER_ID.matches(expectedUserId))
        val session = readSession() ?: run { localClear(AuthState.SignInRequired); throw AuthException(AuthError.Unauthorized) }
        if (session.userId != expectedUserId) throw AuthException(AuthError.Unauthorized)
        return session
    }

    private suspend fun requireLegacySuccess(response: HttpResponse, expected: Int) {
        if (response.status.value == 401) {
            localClear(AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        if (response.status.value != expected) throw AuthException(response.error())
    }

    suspend fun logout(): LogoutResult = if (protocol == AuthProtocol.CLOSED_N2) logoutN2() else mutex.withLock {
        try {
            val session = readStored()
            if (session == null) LogoutResult.NoLocalSession
            else if (session.generation != null) LogoutResult.LocalOnly(AuthError.Storage)
            else if (Instant.parse(session.expiresAt) <= now()) LogoutResult.AlreadyInvalid
            else revoke(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: AuthException) {
            LogoutResult.LocalOnly(failed.error)
        } finally {
            localClear(AuthState.SignedOut)
        }
    }

    private suspend fun revoke(session: StoredSession): LogoutResult {
        val response = transport { client.post(source.baseUrl + "auth/logout") { bearerAuth(session.accessToken) } }
        return when (response.status.value) {
            204 -> LogoutResult.Revoked
            401 -> LogoutResult.AlreadyInvalid
            else -> LogoutResult.LocalOnly(response.failure(authenticated = true))
        }
    }

    private suspend fun logoutN2(): LogoutResult {
        var storageFailed = false
        var readFailed = false
        val session = mutex.withLock {
            checkOpen()
            authenticationAttempt++
            val identity = view.value.identity
            publish(AuthState.StorageUnavailable, null)
            val stored = try { readStored() } catch (failure: AuthException) {
                if (failure.error != AuthError.Storage) throw failure
                readFailed = true
                null
            }
            try { clearN2(stored ?: identity?.cleanupBasis(), AuthState.SignedOut) }
            catch (failure: AuthException) {
                if (failure.error != AuthError.Storage) throw failure
                storageFailed = true
            }
            stored?.takeIf { it.generation != null && !it.cleanupRequired }
        }
        val result = try {
            when {
                readFailed -> LogoutResult.LocalOnly(AuthError.Storage)
                session == null -> LogoutResult.NoLocalSession
                Instant.parse(session.expiresAt) <= now() -> LogoutResult.AlreadyInvalid
                else -> revoke(session)
            }
        } catch (failure: AuthException) {
            LogoutResult.LocalOnly(failure.error)
        }
        if (storageFailed) throw AuthException(AuthError.Storage)
        return result
    }

    private suspend fun requestProfile(restore: Boolean): ProfileDto? {
        if (protocol == AuthProtocol.LEGACY) return mutex.withLock {
            val session = readSession() ?: return@withLock noSession(restore)
            val response = transport { requestMe(session) }
            requireLegacySuccess(response, 200)
            acceptProfile(response, session, verifiedByGet = true)
        }
        val request = mutex.withLock {
            val session = readSession() ?: return noSession(restore)
            SessionRequest(session, requireNotNull(view.value.access))
        }
        return executeN2(request, 200, ::requestMe) { response, session ->
            acceptProfile(response, session, verifiedByGet = true)
        }
    }

    private suspend fun requestMe(session: StoredSession) =
        client.get(source.baseUrl + "me") { bearerAuth(session.accessToken) }

    internal suspend fun <T> emailVerification(
        owner: LocalSessionIdentity,
        proof: EmailCodeProof?,
        proofExpiresAt: Instant? = null,
        decode: suspend (HttpResponse) -> T,
    ): T {
        val captured = captureVerification(owner, pendingOnly = true)
        val action = if (proof == null) "request" else "confirm"
        return executeN2(captured, 204, { session ->
            if (proofExpiresAt != null && proofExpiresAt <= now()) throw VerificationChallengeExpired()
            client.post(source.baseUrl + "auth/email-verification/$action") {
                bearerAuth(session.accessToken)
                setBody(emailActionPostBody(proof))
            }
        }, alternativeStatus = if (proof == null) 202 else null) { response, _ -> decode(response) }
    }

    /** Public recovery shares the fixed origin, while session attachment and 401 handling stay separate. */
    internal suspend fun <T> passwordReset(
        operation: PasswordResetOperation,
        body: OutgoingContent,
        proofExpiresAt: Instant? = null,
        decode: suspend (HttpResponse) -> T,
    ): T {
        check(protocol == AuthProtocol.CLOSED_N2)
        checkOpen()
        if (proofExpiresAt != null && proofExpiresAt <= now()) throw RecoveryProofExpired()
        val response = transport {
            client.post(source.baseUrl + operation.path) { setBody(body) }
        }
        checkOpen()
        if (response.status.value != operation.status)
            throw AuthException(response.readN2Failure(source, authenticated = false))
        return decode(response)
    }

    internal suspend fun checkVerification(owner: LocalSessionIdentity): Boolean =
        executeN2(captureVerification(owner, pendingOnly = false), 200, ::requestMe) { response, session ->
            acceptProfile(response, session, verifiedByGet = true)
            view.value.access?.canRead == true
        }

    private suspend fun captureVerification(owner: LocalSessionIdentity, pendingOnly: Boolean): SessionRequest =
        mutex.withLock {
            checkOpen()
            val access = view.value.access ?: throw StaleSession()
            if (access.identity != owner || pendingOnly && access.trust != PrivateTrust.PENDING) throw StaleSession()
            val session = readSession()?.takeIf { it.identity() == owner } ?: throw StaleSession()
            SessionRequest(session, requireNotNull(view.value.access))
        }

    private fun noSession(restore: Boolean): Nothing? {
        if (!restore) throw AuthException(AuthError.Unauthorized)
        // Keep the reason for signing in again after expiry or a 401.
        if (state.value != AuthState.SignInRequired) publish(AuthState.SignedOut, null)
        return null
    }

    private suspend fun <T> protected(
        userId: String, expectedAccess: PrivateAccess?, expectedStatus: Int,
        request: suspend (StoredSession) -> HttpResponse,
        decode: suspend (HttpResponse, StoredSession) -> T,
    ): T {
        if (protocol == AuthProtocol.LEGACY) return mutex.withLock {
            val session = ownedSession(userId)
            val response = transport { request(session) }
            requireLegacySuccess(response, expectedStatus)
            decode(response, session)
        }
        val captured = mutex.withLock {
            if (expectedAccess != null && currentPrivateAccess() != expectedAccess) throw StaleSession()
            val session = ownedSession(userId)
            val access = requireNotNull(currentPrivateAccess())
            if (!access.canWrite || expectedAccess != null && access != expectedAccess) throw StaleSession()
            SessionRequest(session, access)
        }
        return executeN2(captured, expectedStatus, request, decode = decode)
    }

    /** Network waits do not hold the session mutex. Only the originating session can consume a reply. */
    private suspend fun <T> executeN2(
        captured: SessionRequest, expectedStatus: Int,
        request: suspend (StoredSession) -> HttpResponse,
        alternativeStatus: Int? = null,
        decode: suspend (HttpResponse, StoredSession) -> T,
    ): T {
        val response = try { transport { request(captured.session) } }
        catch (failure: AuthException) {
            mutex.withLock {
                checkCurrent(captured)
                if (failure.error == AuthError.Network || failure.error == AuthError.Timeout) {
                    val current = requireNotNull(view.value.access)
                    if (current.canRead) publish(state.value, current.identity, PrivateTrust.VERIFIED_OFFLINE)
                }
            }
            throw failure
        }
        return mutex.withLock {
            checkCurrent(captured, requireSameTrust = false)
            val status = response.status.value
            val failure = if (status == expectedStatus || status == alternativeStatus) null
                else response.readN2Failure(source, authenticated = true)
            if (failure == AuthError.Unauthorized) {
                clearN2(captured.session, AuthState.SignInRequired)
                throw AuthException(failure)
            }
            if (failure != null) {
                if (failure is AuthError.Http && failure.n2Action == N2HttpAction.EMAIL_NOT_VERIFIED) {
                    val current = state.value as? AuthState.Authenticated
                    publish(current?.let { AuthState.Authenticated(it.user, EmailVerification.PENDING) } ?: state.value,
                        captured.access.identity, PrivateTrust.PENDING)
                }
                else checkCurrent(captured)
                throw AuthException(failure)
            }
            checkCurrent(captured)
            decode(response, captured.session)
        }
    }

    private suspend fun checkCurrent(captured: SessionRequest, requireSameTrust: Boolean = true) {
        checkOpen()
        if (view.value.identity != captured.access.identity) throw StaleSession()
        if (Instant.parse(captured.session.expiresAt) <= now()) {
            clearN2(captured.session, AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        if (requireSameTrust && view.value.access != captured.access) throw StaleSession()
    }

    private suspend inline fun <reified T : Any> authenticate(path: String, body: T, expected: Int): ProfileDto {
        if (protocol == AuthProtocol.LEGACY) return mutex.withLock {
            acceptAuthentication(requestAuthentication(path, body, expected))
        }
        val attempt = mutex.withLock { checkOpen(); ++authenticationAttempt }
        val received = requestAuthentication(path, body, expected)
        return mutex.withLock {
            checkOpen()
            if (attempt != authenticationAttempt) throw StaleSession()
            acceptAuthentication(received)
        }
    }

    private suspend inline fun <reified T : Any> requestAuthentication(
        path: String, body: T, expected: Int,
    ): AcceptedAuthentication {
        val response = transport { client.post(source.baseUrl + path) {
            contentType(ContentType.Application.Json); setBody(body)
        } }
        if (response.status.value != expected) throw AuthException(response.failure(authenticated = false))
        val (dto, verification) = decodeProfileResponse<AuthResponse>(response, wrappedInAuthResponse = true)
        val session = invalidResponse {
            require(dto.tokenType == "Bearer")
            validateProfile(dto.user)
            StoredSession(dto.accessToken, dto.expiresAt, dto.user.id, newGeneration()).also {
                it.validate(); require(Instant.parse(it.expiresAt) > now())
            }
        }
        return AcceptedAuthentication(dto.user, verification, session)
    }

    private suspend fun acceptAuthentication(accepted: AcceptedAuthentication): ProfileDto {
        val (user, verification, session) = accepted
        if (Instant.parse(session.expiresAt) <= now()) throw AuthException(AuthError.InvalidResponse)
        // Once received, persist and publish together, even if the caller is canceled during disk IO.
        withContext(NonCancellable) {
            if (protocol == AuthProtocol.CLOSED_N2) {
                val previous = readStored()
                if (cleanupPending || previous == null || previous.cleanupRequired ||
                    previous.generation == null || previous.userId != session.userId)
                    clearN2(previous, AuthState.SignedOut)
                // Close previous access before the storage operation can suspend.
                publish(AuthState.NotLoaded, null)
            }
            storage { store.write(session) }
            publish(AuthState.Authenticated(user, verification), session.identity(),
                if (verification == EmailVerification.PENDING) PrivateTrust.PENDING else PrivateTrust.UNKNOWN)
        }
        return user
    }

    private suspend fun acceptProfile(response: HttpResponse, session: StoredSession, verifiedByGet: Boolean): ProfileDto {
        try {
            val (user, verification) = decodeProfileResponse<ProfileDto>(response)
            invalidResponse { validateProfile(user); require(user.id == session.userId) }
            val trust = when {
                verification == EmailVerification.PENDING -> PrivateTrust.PENDING
                verification == EmailVerification.VERIFIED && verifiedByGet -> PrivateTrust.VERIFIED_ONLINE
                else -> view.value.access?.trust ?: PrivateTrust.UNKNOWN
            }
            publish(AuthState.Authenticated(user, verification), session.identity(), trust)
            return user
        } catch (failure: AuthException) {
            if (protocol == AuthProtocol.CLOSED_N2)
                publish(AuthState.NotLoaded, session.identity())
            throw failure
        }
    }

    private suspend fun readSession(): StoredSession? {
        if (protocol == AuthProtocol.CLOSED_N2) {
            checkOpen()
            if (cleanupPending) {
                val stored = try { readStored() } catch (failure: AuthException) {
                    if (failure.error != AuthError.Storage) throw failure
                    null
                }
                clearN2(stored, AuthState.SignInRequired)
                return null
            }
        }
        val session = readStored()
        if (protocol == AuthProtocol.CLOSED_N2 && session?.cleanupRequired == true) {
            clearN2(session, AuthState.SignInRequired)
            return null
        }
        if (session == null) {
            if (protocol == AuthProtocol.LEGACY) {
                if (view.value.identity != null) publish(state.value, null)
            } else if (state.value != AuthState.SignedOut && state.value != AuthState.SignInRequired) {
                clearN2(null, if (view.value.identity == null) AuthState.SignedOut else AuthState.SignInRequired)
            }
            return null
        }
        if (protocol == AuthProtocol.LEGACY && session.generation != null) {
            publish(AuthState.StorageUnavailable, null)
            throw AuthException(AuthError.Storage)
        }
        if (protocol == AuthProtocol.CLOSED_N2 && session.generation == null) {
            // A new mode must not send the old mode's Bearer, even in a misconfigured shared store.
            clearN2(session, AuthState.SignInRequired)
            return null
        }
        if (Instant.parse(session.expiresAt) <= now()) {
            if (protocol == AuthProtocol.CLOSED_N2) clearN2(session, AuthState.SignInRequired)
            else localClear(AuthState.SignInRequired)
            throw AuthException(AuthError.Unauthorized)
        }
        if (session.identity() != view.value.identity) {
            publish(if (protocol == AuthProtocol.CLOSED_N2) AuthState.NotLoaded else state.value, session.identity())
        }
        return session
    }

    private suspend fun readStored(): StoredSession? = storage { store.read()?.also { it.validate() } }

    private suspend fun localClear(next: AuthState) = withContext(NonCancellable) {
        // Never leave an old authenticated profile visible after a failed clear.
        publish(AuthState.StorageUnavailable, null)
        storage { store.clear() }
        publish(next, null)
    }

    private suspend fun clearN2(session: StoredSession?, next: AuthState) = withContext(NonCancellable) {
        cleanupPending = true
        publish(AuthState.StorageUnavailable, null)
        // Leave a credential-free marker if cache deletion cannot finish in this process.
        val markerWritten = session != null && (session.cleanupRequired || try {
            store.write(StoredSession("0".repeat(32), session.expiresAt, session.userId,
                session.generation ?: newGeneration(), cleanupRequired = true))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false })
        var failed = false
        suspend fun clean(block: suspend () -> Unit) {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        if (!markerWritten) clean { store.clear() }
        clean { clearPrivateCaches() }
        if (markerWritten && !failed) clean { store.clear() }
        if (failed) throw AuthException(AuthError.Storage)
        cleanupPending = false
        publish(next, null)
    }

    private fun StoredSession.identity() =
        LocalSessionIdentity(userId, expiresAt, source.baseUrl.takeIf { generation != null }, generation)

    private fun LocalSessionIdentity.cleanupBasis() = StoredSession("0".repeat(32), expiresAt, userId, generation)

    private fun checkOpen() { if (closed.value) throw StaleSession() }

    @OptIn(ExperimentalUuidApi::class)
    private fun newGeneration(): String? = if (protocol == AuthProtocol.CLOSED_N2) Uuid.random().toString() else null

    private fun publish(state: AuthState, identity: LocalSessionIdentity?, trust: PrivateTrust = PrivateTrust.UNKNOWN) {
        if (closed.value && identity != null) return
        val access = mutableView.value.access?.let {
            if (it.identity == identity && it.trust == trust) it else PrivateAccess(identity, trust, it.revision + 1)
        }
        mutableView.value = SessionView(state, identity, access)
    }

    private suspend inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        publish(AuthState.StorageUnavailable, null)
        throw AuthException(AuthError.Storage)
    }

    private suspend inline fun <reified T : Any> decode(response: HttpResponse): T = try {
        response.body<T>()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { throw AuthException(AuthError.InvalidResponse) }

    private suspend inline fun <reified T : Any> decodeProfileResponse(
        response: HttpResponse,
        wrappedInAuthResponse: Boolean = false,
    ): Pair<T, EmailVerification?> {
        if (protocol == AuthProtocol.LEGACY) return decode<T>(response) to null
        val json = decode<JsonObject>(response)
        val decoded = invalidResponse { ContractJson.decodeFromJsonElement<T>(json) }
        val profile = if (wrappedInAuthResponse) json["user"] as? JsonObject else json
        return decoded to (profile ?: throw AuthException(AuthError.InvalidResponse)).readN2EmailVerification()
    }

    private inline fun <T> invalidResponse(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw AuthException(AuthError.InvalidResponse) }

    private fun validateProfile(user: ProfileDto) {
        require(USER_ID.matches(user.id) && user.displayName.isNotBlank() && user.email.isNotBlank())
        validateProfilePatch(buildJsonObject {
            put("displayName",user.displayName);put("city",user.city?.let(::JsonPrimitive)?:JsonNull)
            put("countryCode",user.countryCode?.let(::JsonPrimitive)?:JsonNull)
            put("roleLabel",user.roleLabel?.let(::JsonPrimitive)?:JsonNull)
            put("avatarKey",user.avatarKey);put("interests",JsonArray(user.interests.map(::JsonPrimitive)))
        })
        require(user.stats.savedCount >= 0 && user.stats.appliedCount >= 0 && user.stats.interestsCount >= 0)
    }

    private fun HttpResponse.error() = AuthError.Http(status.value,
        headers[HttpHeaders.RetryAfter]?.toIntOrNull()?.takeIf { it >= 0 })

    private suspend fun HttpResponse.failure(authenticated: Boolean): AuthError =
        if (protocol == AuthProtocol.CLOSED_N2) readN2Failure(source, authenticated) else error()

    private suspend inline fun <T> transport(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: HttpRequestTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: ConnectTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: SocketTimeoutException) { throw AuthException(AuthError.Timeout) }
    catch (_: IOException) { throw AuthException(AuthError.Network) }

    fun close() {
        if (protocol == AuthProtocol.CLOSED_N2) {
            closed.value = true
            publish(AuthState.NotLoaded, null)
        }
        client.close()
        engine.close()
    }
}

internal data class SessionView(
    val state: AuthState = AuthState.NotLoaded,
    val identity: LocalSessionIdentity? = null,
    val access: PrivateAccess? = null,
)

private class StaleSession : CancellationException("Session access changed")
private data class SessionRequest(val session: StoredSession, val access: PrivateAccess)
private data class AcceptedAuthentication(
    val user: ProfileDto, val verification: EmailVerification?, val session: StoredSession,
)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private fun <T, R> StateFlow<T>.project(transform: (T) -> R): StateFlow<R> {
    val source = this
    return object : StateFlow<R> {
        override val value get() = transform(source.value)
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            source.map(transform).distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
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

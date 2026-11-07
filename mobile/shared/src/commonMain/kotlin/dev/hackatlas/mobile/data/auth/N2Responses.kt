package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.data.ProblemDto
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun JsonObject.readN2EmailVerification(): EmailVerification {
    val value = this["emailVerified"] ?: throw AuthException(AuthError.UnsupportedServer)
    val flag = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw AuthException(AuthError.InvalidResponse)
    return if (flag) EmailVerification.VERIFIED else EmailVerification.PENDING
}

/** Called for a response to a request issued by this source's auth transport. */
internal suspend fun HttpResponse.requireN2Success(
    source: CatalogSource.Remote,
    expected: Int,
    authenticated: Boolean,
    onPending: () -> Unit = {},
    onInvalidSession: suspend () -> Unit = {},
) {
    if (status.value == expected) return
    val error = readN2Failure(source, authenticated)
    when {
        error == AuthError.Unauthorized -> onInvalidSession()
        error is AuthError.Http && error.n2Action == N2HttpAction.EMAIL_NOT_VERIFIED -> onPending()
    }
    throw AuthException(error)
}

internal suspend fun HttpResponse.readN2Failure(source: CatalogSource.Remote, authenticated: Boolean): AuthError {
    val basePath = Url(source.baseUrl).encodedPath
    val request = call.request
    val requestPath = request.url.encodedPath
    val code = status.value
    if (!requestPath.startsWith(basePath)) {
        return if (authenticated && code == 401) AuthError.Unauthorized else AuthError.InvalidResponse
    }
    val operation = N2Operation(request.method, "/" + requestPath.removePrefix(basePath))
    if ((code == 404 || code == 501) && operation.lifecycle) return AuthError.UnsupportedServer
    if (code == 401 && operation.recovery) return AuthError.UnsupportedServer

    val problem = readProblem()
    if (code == 401 && authenticated && !(operation.deleteAccount && problem?.code == "INVALID_CREDENTIALS")) {
        return AuthError.Unauthorized
    }
    if (problem == null) return AuthError.InvalidResponse
    val action = when (problem.code) {
        "INVALID_CREDENTIALS" -> N2HttpAction.INVALID_CREDENTIALS.takeIf {
            code == 401 && (operation.login && !authenticated || operation.deleteAccount && authenticated)
        }
        "EMAIL_NOT_VERIFIED" -> N2HttpAction.EMAIL_NOT_VERIFIED.takeIf { code == 403 && authenticated && operation.guarded }
        "PASSWORD_RESET_REQUIRED" -> N2HttpAction.PASSWORD_RESET_REQUIRED.takeIf {
            code == 403 && operation.login && !authenticated
        }
        "FORBIDDEN" -> N2HttpAction.FORBIDDEN.takeIf { code == 403 }
        "VALIDATION_ERROR" -> N2HttpAction.INVALID_INPUT.takeIf { code == 400 }
        "EMAIL_ACTION_INVALID" -> N2HttpAction.INVALID_EMAIL_ACTION.takeIf { code == 400 && operation.consumesEmailAction }
        "EMAIL_ALREADY_EXISTS" -> N2HttpAction.EMAIL_ALREADY_EXISTS.takeIf { code == 409 && operation.register && !authenticated }
        "NOT_FOUND" -> N2HttpAction.NOT_FOUND.takeIf { code == 404 }
        "RATE_LIMITED" -> N2HttpAction.RATE_LIMITED.takeIf { code == 429 }
        "INTERNAL_ERROR" -> N2HttpAction.SERVER_ERROR.takeIf { code == 500 }
        "SERVICE_UNAVAILABLE" -> N2HttpAction.RETRY_MANUALLY.takeIf { code == 503 }
        else -> null
    } ?: return AuthError.InvalidResponse

    val retryAfter = if (action == N2HttpAction.RATE_LIMITED) {
        headers[HttpHeaders.RetryAfter]?.trim()?.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
            ?.toIntOrNull() ?: return AuthError.InvalidResponse
    } else null
    return AuthError.Http(code, retryAfter, action, problem.requestId)
}

private suspend fun HttpResponse.readProblem(): ProblemDto? {
    val mediaType = headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
    if (!"application/problem+json".equals(mediaType, ignoreCase = true)) return null
    return try {
        ContractJson.decodeFromString<ProblemDto>(bodyAsText()).takeIf { problem ->
            problem.status == status.value &&
                (headers["X-Request-Id"] == null || headers["X-Request-Id"] == problem.requestId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private data class N2Operation(val method: HttpMethod, val path: String) {
    private val post get() = method == HttpMethod.Post
    val login get() = post && path == "/auth/login"
    val register get() = post && path == "/auth/register"
    val deleteAccount get() = post && path == "/me/delete-account"
    val recovery get() = post && path in recoveryPaths
    val lifecycle get() = recovery || post && path in verificationPaths
    val consumesEmailAction get() = post && path in emailActionConsumers

    val guarded get() = when (method) {
        HttpMethod.Get -> path == "/me/saved" || path == "/me/applications"
        HttpMethod.Patch -> path == "/me"
        HttpMethod.Put, HttpMethod.Delete -> itemPath("/me/saved/") || itemPath("/me/applications/")
        HttpMethod.Post -> path == "/suggestions" || itemPath("/hackathons/", "/reports")
        else -> false
    }

    private fun itemPath(prefix: String, suffix: String = ""): Boolean =
        path.startsWith(prefix) && path.endsWith(suffix) && USER_ID.matches(path.removePrefix(prefix).removeSuffix(suffix))
}

private val verificationPaths = setOf("/auth/email-verification/request", "/auth/email-verification/confirm")
private val recoveryPaths = setOf("/auth/password-reset/request", "/auth/password-reset/verify", "/auth/password-reset/confirm")
private val emailActionConsumers = setOf("/auth/email-verification/confirm", "/auth/password-reset/verify", "/auth/password-reset/confirm")

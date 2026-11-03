package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ProfileClientTest {
    private val source = CatalogSource.Remote("https://profile.example.org/api/v1/")
    private val user = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val token = "synthetic-profile-" + "p".repeat(40)
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private class Disk(override val apiBaseUrl: String, var value: StoredSession?) : SecureTokenStore {
        override suspend fun read() = value
        override suspend fun write(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private fun disk() = Disk(source.baseUrl, StoredSession(token,"2026-12-01T00:00:00Z",user.id))
    private fun client(disk: Disk = disk(), handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        AuthSessionClient(MockEngine(handler),source,disk) { Instant.parse("2026-11-01T12:00:00Z") }

    @Test fun patchSendsOnlyProvidedFieldsPreservesNullAndPublishesServerProfile() = runTest {
        val patch = buildJsonObject { put("displayName","Edited"); put("city",JsonNull); put("interests",JsonArray(emptyList())) }
        val updated = user.copy(displayName="Edited",city=null,interests=emptyList())
        val c = client { req ->
            assertEquals(HttpMethod.Patch,req.method); assertEquals(source.baseUrl+"me",req.url.toString())
            assertEquals(listOf("Bearer $token"),req.headers.getAll(HttpHeaders.Authorization))
            assertEquals(patch,ContractJson.parseToJsonElement(req.body.toByteArray().decodeToString()))
            respond(ContractJson.encodeToString(updated),headers=headers)
        }
        try { assertEquals(updated,c.updateProfile(user.id,patch)); assertEquals(updated,assertIs<AuthState.Authenticated>(c.state.value).user) }
        finally { c.close() }
    }
    @Test fun invalidOrPrivilegedBodyNeverSent() = runTest {
        val c=client { error("No HTTP") }
        try {
            for(body in listOf(buildJsonObject{},buildJsonObject{put("email","other@example.org")},buildJsonObject{put("role","ADMIN")},
                buildJsonObject{put("displayName",JsonNull)},buildJsonObject{put("countryCode","kz")},buildJsonObject{put("interests",JsonArray(listOf(JsonPrimitive("Kotlin"),JsonPrimitive("Kotlin"))))})) {
                assertFailsWith<IllegalArgumentException> { c.updateProfile(user.id,body) }
            }
        } finally { c.close() }
    }
    @Test fun wrongExpectedUserNeverSendsAnotherAccountsToken() = runTest {
        val c=client { error("No HTTP") }
        try { assertFailsWith<AuthException>{c.updateProfile("22222222-2222-4222-8222-222222222222",buildJsonObject{put("displayName","N")})} }
        finally { c.close() }
    }
    @Test fun patch401ClearsIdentityAndCredential() = runTest {
        val disk=disk();val c=client(disk){respond("private",HttpStatusCode.Unauthorized)}
        try {
            assertEquals(AuthError.Unauthorized,assertFailsWith<AuthException>{c.updateProfile(user.id,buildJsonObject{put("city",JsonNull)})}.error)
            assertEquals(AuthState.SignInRequired,c.state.value);assertNull(c.localIdentity.value);assertNull(disk.value)
        } finally {c.close()}
    }
    @Test fun malformedOrForeignProfileNeverPublishesSuccess() = runTest {
        val foreign=ContractJson.encodeToString(user.copy(id="22222222-2222-4222-8222-222222222222"))
        for(body in listOf("{}",foreign)) {
            val c=client{respond(body,headers=headers)}
            try{assertEquals(AuthError.InvalidResponse,assertFailsWith<AuthException>{c.updateProfile(user.id,buildJsonObject{put("city",JsonNull)})}.error);assertFalse(c.state.value is AuthState.Authenticated)}
            finally{c.close()}
        }
    }
    @Test fun validationErrorHasNoRawBodyAndNoRetry() = runTest {
        var calls=0;val c=client{calls++;respond("private server detail",HttpStatusCode.BadRequest)}
        try{val e=assertFailsWith<AuthException>{c.updateProfile(user.id,buildJsonObject{put("city",JsonNull)})};assertEquals(AuthError.Http(400,null),e.error);assertFalse(e.toString().contains("private server detail"));assertEquals(1,calls)}
        finally{c.close()}
    }
    @Test fun structurallyValidButContractInvalidProfileNeverPublishes()=runTest {
        val invalid=listOf(user.copy(avatarKey="PHOTO"),user.copy(countryCode="KAZ"),user.copy(interests=listOf("x","x")),
            user.copy(displayName="x".repeat(61)),user.copy(city="x".repeat(101)),user.copy(roleLabel="a\u0000b"))
        for(profile in invalid) {
            val c=client{respond(ContractJson.encodeToString(profile),headers=headers)}
            try {
                assertEquals(AuthError.InvalidResponse,assertFailsWith<AuthException>{c.updateProfile(user.id,buildJsonObject{put("city",JsonNull)})}.error)
                assertFalse(c.state.value is AuthState.Authenticated)
            } finally{c.close()}
        }
    }
}

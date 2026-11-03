package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.domain.CatalogSource
import dev.hackatlas.mobile.ui.auth.AuthStore
import dev.hackatlas.mobile.ui.profile.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditStoreTest {
    private val source=CatalogSource.Remote("https://profile.example.org/api/v1/")
    private val user=ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private inner class Setup(val scope: TestScope) {
        var server=user;var patchCalls=0;var fail=false;var failAfterApply=false;var revoked=false;var gate:CompletableDeferred<Unit>?=null
        val disk=object:SecureTokenStore {
            override val apiBaseUrl=source.baseUrl
            var value:StoredSession?=StoredSession("synthetic-edit-"+"e".repeat(40),"2026-12-01T00:00:00Z",user.id)
            override suspend fun read()=value
            override suspend fun write(session:StoredSession){value=session}
            override suspend fun clear(){value=null}
        }
        val client=AuthSessionClient(MockEngine.create {
            dispatcher=StandardTestDispatcher(scope.testScheduler)
            addHandler { req ->
                when(req.method) {
                    HttpMethod.Get -> if(revoked)respond("",HttpStatusCode.Unauthorized) else respond(ContractJson.encodeToString(server),headers=headers)
                    HttpMethod.Post -> respond("",HttpStatusCode.NoContent)
                    HttpMethod.Patch -> {
                        patchCalls++;gate?.await()
                        if(fail) throw kotlinx.io.IOException("private")
                        val patch=ContractJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                        patch["displayName"]?.let{server=server.copy(displayName=it.jsonPrimitive.content)}
                        patch["city"]?.let{server=server.copy(city=it.jsonPrimitive.contentOrNull)}
                        if(failAfterApply)throw kotlinx.io.IOException("response lost")
                        respond(ContractJson.encodeToString(server),headers=headers)
                    }
                    else->error("Unexpected")
                }
            }
        },source,disk){Instant.parse("2026-11-01T12:00:00Z")}
        val auth=AuthStore(client,scope.backgroundScope)
        val edit=ProfileEditStore(auth,client,scope.backgroundScope)
        suspend fun start(){auth.start();scope.runCurrent();edit.open()}
    }
    private val headers get()=headersOf(HttpHeaders.ContentType,"application/json")
    @Test fun diffOmitsUnchangedFieldsUsesNullAndPreservesInterestSet() {
        val draft=ProfileDraft.from(user)
        assertTrue(profilePatch(user,draft).isEmpty())
        val change=draft.copy(city="",countryCode="",roleLabel="",displayName=" Edited ")
        val patch=profilePatch(user,change)
        assertEquals("Edited",patch["displayName"]?.jsonPrimitive?.content)
        if(user.city!=null) assertEquals(JsonNull,patch["city"])
        assertFalse("interests" in patch);assertFalse("email" in patch);assertFalse("stats" in patch)
        assertTrue(profilePatch(user,draft.copy(interests=draft.interests.reversed())).isEmpty())
    }
    @Test fun validationUsesContractLengthsAndAvatarAllowlist() {
        val valid=ProfileDraft.from(user)
        assertTrue(valid.validate().isEmpty())
        val bad=valid.copy(displayName=" ",city="x".repeat(101),countryCode="KAZ",roleLabel="x".repeat(101),avatarKey="PHOTO",interests=List(21){"tag$it"})
        assertEquals(setOf("displayName","city","countryCode","roleLabel","avatarKey","interests"),bad.validate().keys)
    }
    @Test fun unicodeLimitsCountCodePointsAndRejectBrokenText() {
        val draft=ProfileDraft.from(user).copy(displayName="😀".repeat(60),city="😀".repeat(100))
        assertTrue(draft.validate().isEmpty())
        assertTrue("displayName" in draft.copy(displayName="😀".repeat(61)).validate())
        assertTrue("city" in draft.copy(city="\uD800").validate())
        assertTrue("roleLabel" in draft.copy(roleLabel="a\u0000b").validate())
    }
    @Test fun unchangedAndInvalidDraftNeverPatchAndCancelDropsMemory()=runTest {
        val s=Setup(this)
        try{s.start();assertFalse(s.edit.save());s.edit.change{it.copy(displayName=" ")};assertFalse(s.edit.save());assertTrue(s.edit.state.value.errors.isNotEmpty());s.edit.close();assertNull(s.edit.state.value.draft);assertEquals(0,s.patchCalls)}finally{s.client.close()}
    }
    @Test fun duplicateSaveOneRequestAndConfirmedServerProfileOnly()=runTest {
        val s=Setup(this)
        try{s.start();s.gate=CompletableDeferred();s.edit.change{it.copy(displayName="Edited")};assertTrue(s.edit.save());assertFalse(s.edit.save());runCurrent();assertEquals(1,s.patchCalls);assertFalse(s.edit.state.value.saved);s.gate!!.complete(Unit);runCurrent();assertTrue(s.edit.state.value.saved);assertEquals("Edited",assertIs<AuthState.Authenticated>(s.auth.state.value.session).user.displayName)}finally{s.client.close()}
    }
    @Test fun failedSaveKeepsDraftForExplicitRetry()=runTest {
        val s=Setup(this)
        try{s.start();s.fail=true;s.edit.change{it.copy(displayName="Edited")};s.edit.save();runCurrent();assertEquals(AuthError.Network,s.edit.state.value.error);assertEquals("Edited",s.edit.state.value.draft?.displayName);assertFalse(s.edit.state.value.saved);s.fail=false;s.edit.save();runCurrent();assertTrue(s.edit.state.value.saved)}finally{s.client.close()}
    }
    @Test fun logoutDuringDelayedPatchDiscardsDraftAndLateUiSuccess()=runTest {
        val s=Setup(this)
        try{s.start();s.gate=CompletableDeferred();s.edit.change{it.copy(displayName="Edited")};s.edit.save();runCurrent();s.auth.logout();runCurrent();assertNull(s.edit.state.value.draft);s.gate!!.complete(Unit);runCurrent();assertNull(s.edit.state.value.draft);assertFalse(s.edit.state.value.saved);assertEquals(AuthState.SignedOut,s.auth.state.value.session)}finally{s.client.close()}
    }
    @Test fun foregroundRefreshDoesNotOverwriteDraftOrUnchangedPatchFields()=runTest {
        val s=Setup(this)
        try{s.start();s.edit.change{it.copy(displayName="Edited")};s.server=s.server.copy(city="Other city");s.auth.refresh();runCurrent();assertEquals("Edited",s.edit.state.value.draft?.displayName);assertFalse("city" in profilePatch(s.edit.state.value.baseline!!,s.edit.state.value.draft!!));s.edit.save();runCurrent();assertEquals("Other city",assertIs<AuthState.Authenticated>(s.auth.state.value.session).user.city)}finally{s.client.close()}
    }
    @Test fun revokedForegroundDiscardsDraftAndProfileRefreshKeepsSignInRequired()=runTest {
        val s=Setup(this)
        try {
            s.start();s.edit.change{it.copy(displayName="Unsaved")};s.revoked=true
            s.auth.onForeground();runCurrent()
            assertNull(s.edit.state.value.draft);assertNull(s.client.localIdentity.value)
            assertEquals(AuthState.SignInRequired,s.auth.state.value.session)
            // Closing the invalidated editor enters Profile, which refreshes again.
            s.auth.refresh();runCurrent()
            assertEquals(AuthState.SignInRequired,s.auth.state.value.session)
            assertTrue(s.auth.state.value.canAuthenticate)
        } finally{s.client.close()}
    }
    @Test fun appliedResponseLostThenRevertAndOtherEditHonorsCurrentDraft()=runTest {
        val s=Setup(this)
        try {
            s.start();s.failAfterApply=true
            s.edit.change{it.copy(displayName="Applied but lost")};s.edit.save();runCurrent()
            assertEquals("Applied but lost",s.server.displayName)
            s.edit.change{it.copy(displayName=user.displayName,city="New city")}
            assertNotNull(s.edit.state.value.error)
            s.failAfterApply=false;s.edit.save();runCurrent()
            assertTrue(s.edit.state.value.saved)
            assertEquals(user.displayName,s.server.displayName);assertEquals("New city",s.server.city)
        } finally{s.client.close()}
    }
}

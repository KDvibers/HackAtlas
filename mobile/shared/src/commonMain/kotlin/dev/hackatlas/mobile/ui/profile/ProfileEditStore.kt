package dev.hackatlas.mobile.ui.profile

import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.ui.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ProfileEditState(val baseline:ProfileDto?=null,val draft:ProfileDraft?=null,
    val busy:Boolean=false,val saved:Boolean=false,val error:AuthError?=null,val errors:Map<String,Boolean> = emptyMap(),
    val uncertainFields:Set<String> = emptySet()) {
    val dirty get()=baseline!=null && draft!=null && profilePatch(baseline,draft,uncertainFields).isNotEmpty()
    override fun toString()="ProfileEditState(busy=$busy,saved=$saved,error=$error)"
}

/** Main-thread, process memory only. Closing the UI cannot roll back a PATCH already sent. */
class ProfileEditStore(private val auth:AuthStore,private val client:AuthSessionClient,private val scope:CoroutineScope) {
    private val mutableState=MutableStateFlow(ProfileEditState())
    val state=mutableState.asStateFlow()
    private var generation=0L
    private var capturedAccess: PrivateAccess? = null
    init {scope.launch {auth.state.collect { value ->
        val owner=mutableState.value.baseline?.id ?: return@collect
        if(value.localIdentity?.userId!=owner || value.session !is AuthState.Authenticated ||
            value.busy==AuthOperation.LOGOUT || !accessStillValid()) close()
    }}}
    internal fun visibleFor(state: ProfileEditState, authState: AuthUiState?): ProfileEditState =
        if (authState?.privateAccess != null &&
            (!authState.canWritePrivate || capturedAccess != authState.privateAccess)) ProfileEditState() else state

    private fun accessStillValid() = client.protocol == AuthProtocol.LEGACY ||
        capturedAccess?.canWrite == true && capturedAccess == auth.currentPrivateAccess()

    fun open():Boolean {
        val current=auth.state.value
        val user=(current.session as? AuthState.Authenticated)?.user ?: return false
        if(current.busy!=null || !current.canWritePrivate || current.localIdentity?.userId!=user.id ||
            current.privateAccess != auth.currentPrivateAccess()) return false
        capturedAccess = current.privateAccess
        generation++;mutableState.value=ProfileEditState(user,ProfileDraft.from(user));return true
    }
    fun change(update:(ProfileDraft)->ProfileDraft) {
        val current=mutableState.value
        if(!current.busy && accessStillValid()) current.draft?.let{mutableState.value=current.copy(draft=update(it),errors=emptyMap())}
    }
    fun close(){generation++;capturedAccess=null;mutableState.value=ProfileEditState()}
    fun reload(){close();auth.refresh()}
    fun save():Boolean {
        val current=mutableState.value
        val base=current.baseline ?: return false;val draft=current.draft ?: return false
        if(current.busy || !accessStillValid()) return false
        val errors=draft.validate();mutableState.value=current.copy(errors=errors,error=null)
        if(errors.isNotEmpty() || !current.dirty) return false
        val turn=generation
        val access = capturedAccess
        val patch=profilePatch(base,draft,current.uncertainFields)
        mutableState.value=mutableState.value.copy(busy=true)
        scope.launch {
            try {
                client.updateProfile(base.id,patch,access)
                if(turn==generation && accessStillValid() && auth.state.value.localIdentity?.userId==base.id && auth.state.value.busy!=AuthOperation.LOGOUT)
                    mutableState.value=ProfileEditState(saved=true)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failed:AuthException){if(turn==generation && accessStillValid())mutableState.value=mutableState.value.copy(
                error=failed.error,uncertainFields=mutableState.value.uncertainFields+patch.keys)}
            finally{if(turn==generation)mutableState.value=mutableState.value.copy(busy=false)}
        }
        return true
    }
}

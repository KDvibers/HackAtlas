package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.IconAction
import org.jetbrains.compose.resources.stringResource

enum class PasswordRecoveryLoginNotice { CHANGED, OUTCOME_UNKNOWN }

/** Ephemeral inputs: never saveable, never passed into navigation or process state. */
@Composable
fun AuthScreen(
    store: AuthStore,
    mode: AuthFormMode,
    onMode: (AuthFormMode) -> Unit,
    onBack: () -> Unit,
    onRecoverPassword: ((String) -> Unit)? = null,
    recoveryNotice: PasswordRecoveryLoginNotice? = null,
) {
    val state by store.state.collectAsState()
    var email by remember(mode, state.formRevision) { mutableStateOf("") }
    var password by remember(mode, state.formRevision) { mutableStateOf("") }
    var name by remember(mode, state.formRevision) { mutableStateOf("") }
    var showPassword by remember(mode, state.formRevision) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val register = mode == AuthFormMode.REGISTER
    val submitting = state.busy == AuthOperation.LOGIN || state.busy == AuthOperation.REGISTER
    val submit = {
        if (store.submit(mode, email, password, name)) {
            password = ""; showPassword = false; focus.clearFocus()
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IconAction(AtlasIcon.Back, stringResource(Res.string.back), onBack)
        TechLabel(stringResource(Res.string.auth_kicker))
        PixelHeading(stringResource(if (register) Res.string.auth_register_title else Res.string.auth_login_title), fontSize = 24.sp)
        Text(stringResource(if (register) Res.string.auth_register_body else Res.string.auth_login_body),
            style = MaterialTheme.typography.bodyMedium, color = AtlasColors.TextSecondary)
        if (!register && recoveryNotice != null) Text(
            stringResource(if (recoveryNotice == PasswordRecoveryLoginNotice.CHANGED)
                Res.string.recovery_complete else Res.string.recovery_confirm_unknown),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = AtlasColors.Cyan,
        )
        AuthFeedback(state)
        if (state.canAuthenticate || submitting) {
            if (register) AuthField(name, { name = it }, stringResource(Res.string.auth_name),
                state.fields.name?.let { stringResource(Res.string.auth_name_invalid) }, !submitting,
                KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Words),
                KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
            AuthField(email, { email = it }, stringResource(Res.string.auth_email),
                state.fields.email?.let { stringResource(Res.string.auth_email_invalid) }, !submitting,
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
            val passwordError = state.fields.password?.let {
                stringResource(when (it) {
                    AuthFieldError.PASSWORD_LONG -> Res.string.auth_password_long
                    AuthFieldError.PASSWORD_INVALID -> Res.string.auth_password_invalid_characters
                    else -> if (register) Res.string.auth_password_short else Res.string.auth_password_required
                })
            }
            AuthField(password, { password = it }, stringResource(Res.string.auth_password), passwordError, !submitting,
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                KeyboardActions(onDone = { submit() }),
                visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    val label = stringResource(if (showPassword) Res.string.auth_hide_password else Res.string.auth_show_password)
                    TextButton(onClick = { showPassword = !showPassword }, enabled = !submitting,
                        modifier = Modifier.semantics { contentDescription = label }) {
                        Text(stringResource(if (showPassword) Res.string.auth_hide else Res.string.auth_show), fontSize = 11.sp)
                    }
                })
            if (register && passwordError == null) Text(stringResource(Res.string.auth_password_hint),
                color = AtlasColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            NeonButton(stringResource(if (submitting) Res.string.auth_submitting else if (register) Res.string.auth_register else Res.string.auth_login),
                submit, Modifier.fillMaxWidth(), enabled = !submitting)
            if (!register && onRecoverPassword != null) {
                val resetRequired = (state.error as? AuthError.Http)?.n2Action == N2HttpAction.PASSWORD_RESET_REQUIRED
                TextButton(
                    onClick = {
                        password = ""
                        showPassword = false
                        focus.clearFocus()
                        onRecoverPassword(email)
                    },
                    enabled = state.canAuthenticate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (resetRequired) Res.string.recovery_title else Res.string.recovery_forgot),
                        color = AtlasColors.Cyan)
                }
            }
            TextButton(onClick = {
                password = ""; email = ""; name = ""; showPassword = false
                store.dismissFeedback(); onMode(if (register) AuthFormMode.LOGIN else AuthFormMode.REGISTER)
            }, enabled = !submitting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (register) Res.string.auth_have_account else Res.string.auth_no_account), color = AtlasColors.Cyan)
            }
        } else if (state.busy == null) {
            NeonButton(stringResource(Res.string.auth_retry_session), store::refresh, Modifier.fillMaxWidth())
            TextButton(onClick = store::logout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.auth_logout_device), color = AtlasColors.TextSecondary)
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.auth_continue_guest), color = AtlasColors.TextSecondary)
        }
        Text(stringResource(Res.string.auth_guest_note), color = AtlasColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AuthField(value: String, onChange: (String) -> Unit, label: String, errorText: String?, enabled: Boolean,
    keyboard: KeyboardOptions, actions: KeyboardActions, visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, isError = errorText != null,
        supportingText = if (errorText != null) ({ Text(errorText) }) else null,
        shape = RoundedCornerShape(AtlasDimensions.FieldRadius), keyboardOptions = keyboard, keyboardActions = actions,
        visualTransformation = visual, trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AtlasColors.Surface, unfocusedContainerColor = AtlasColors.Surface,
            disabledContainerColor = AtlasColors.Surface, errorContainerColor = AtlasColors.Surface,
            focusedBorderColor = AtlasColors.Primary, unfocusedBorderColor = AtlasColors.Border,
            focusedTextColor = AtlasColors.TextPrimary, unfocusedTextColor = AtlasColors.TextPrimary,
            unfocusedLabelColor = AtlasColors.TextSecondary, cursorColor = AtlasColors.Primary))
}

@Composable
fun AuthFeedback(state: AuthUiState) {
    val text = when {
        state.busy != null -> stringResource(when (state.busy) {
            AuthOperation.RESTORE -> Res.string.auth_checking
            AuthOperation.LOGOUT -> Res.string.auth_logging_out
            else -> Res.string.auth_submitting
        })
        state.error != null -> authErrorText(state.error)
        state.session == AuthState.SignInRequired -> stringResource(Res.string.auth_expired)
        state.notice != null -> stringResource(when (state.notice) {
            AuthNotice.LOGGED_OUT -> Res.string.auth_logout_success
            AuthNotice.SESSION_INVALID -> Res.string.auth_logout_invalid
            AuthNotice.LOCAL_ONLY_LOGOUT -> Res.string.auth_logout_local_only
            AuthNotice.DEVICE_LOGOUT -> Res.string.auth_logout_device_done
        })
        else -> null
    } ?: return
    val shape = RoundedCornerShape(AtlasDimensions.CardRadius)
    Column(Modifier.fillMaxWidth().background(AtlasColors.SurfaceRaised, shape)
        .border(1.dp, AtlasColors.Border, shape).padding(16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AtlasColors.Primary,
            trackColor = AtlasColors.Border)
        Text(text, color = if (state.error != null) AtlasColors.Danger else AtlasColors.Cyan,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun authErrorText(error: AuthError): String = when (error) {
    AuthError.Network -> stringResource(Res.string.auth_network)
    AuthError.Timeout -> stringResource(Res.string.auth_timeout)
    AuthError.Storage -> stringResource(Res.string.auth_storage)
    AuthError.Unauthorized -> stringResource(Res.string.auth_expired)
    AuthError.InvalidResponse, AuthError.UnsupportedServer -> stringResource(Res.string.auth_invalid_response)
    is AuthError.Http -> when (error.status) {
        400 -> stringResource(Res.string.auth_validation)
        401 -> stringResource(Res.string.auth_invalid_credentials)
        403 -> stringResource(if (error.n2Action == N2HttpAction.PASSWORD_RESET_REQUIRED)
            Res.string.recovery_required else Res.string.auth_forbidden)
        409 -> stringResource(Res.string.auth_conflict)
        429 -> error.retryAfterSeconds?.let { stringResource(Res.string.auth_rate_limit_seconds, it) }
            ?: stringResource(Res.string.auth_rate_limit)
        else -> stringResource(Res.string.auth_server_error)
    }
}

package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import dev.hackatlas.mobile.ui.HackAtlasBrand
import dev.hackatlas.mobile.ui.IconAction
import dev.hackatlas.mobile.ui.WaveCardShape
import org.jetbrains.compose.resources.stringResource

enum class PasswordRecoveryLoginNotice { CHANGED, OUTCOME_UNKNOWN }

private val AuthOutline = lerp(CatalogueColors.Border, CatalogueColors.Muted, .35f)

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
            password = ""
            showPassword = false
            focus.clearFocus()
        }
    }
    val close = {
        focus.clearFocus()
        onBack()
    }
    Column(
        Modifier.fillMaxSize().background(CatalogueColors.Background).imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AuthScreenHeader(stringResource(if (register) Res.string.auth_register_title else Res.string.auth_login_title), close)
        if (!register && recoveryNotice != null) {
            Text(
                stringResource(
                    if (recoveryNotice == PasswordRecoveryLoginNotice.CHANGED) Res.string.recovery_complete
                    else Res.string.recovery_confirm_unknown,
                ),
                Modifier.fillMaxWidth().background(CatalogueColors.Surface, RoundedCornerShape(14.dp)).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = CatalogueColors.Turquoise, style = MaterialTheme.typography.bodyMedium,
            )
        }
        AuthFeedback(
            state, containerColor = CatalogueColors.Surface, borderColor = CatalogueColors.Border,
            progressColor = CatalogueColors.Lime, messageColor = CatalogueColors.Turquoise,
        )
        if (state.canAuthenticate || submitting) {
            val fieldColors = authScreenFieldColors()
            Column(
                Modifier.fillMaxWidth().clip(WaveCardShape).background(CatalogueColors.Surface)
                    .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), WaveCardShape)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (register) {
                    AuthField(
                        name, { name = it }, stringResource(Res.string.auth_name),
                        state.fields.name?.let { stringResource(Res.string.auth_name_invalid) }, !submitting,
                        KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Words),
                        KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        colors = fieldColors,
                    )
                }
                AuthField(
                    email, { email = it }, stringResource(Res.string.auth_email),
                    state.fields.email?.let { stringResource(Res.string.auth_email_invalid) }, !submitting,
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    colors = fieldColors,
                )
                val passwordError = state.fields.password?.let {
                    stringResource(when (it) {
                        AuthFieldError.PASSWORD_LONG -> Res.string.auth_password_long
                        AuthFieldError.PASSWORD_INVALID -> Res.string.auth_password_invalid_characters
                        else -> if (register) Res.string.auth_password_short else Res.string.auth_password_required
                    })
                }
                AuthField(
                    password, { password = it }, stringResource(Res.string.auth_password), passwordError, !submitting,
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    KeyboardActions(onDone = { submit() }),
                    visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        val label = stringResource(if (showPassword) Res.string.auth_hide_password else Res.string.auth_show_password)
                        TextButton(
                            onClick = { showPassword = !showPassword }, enabled = !submitting,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
                            colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Turquoise),
                        ) {
                            Text(stringResource(if (showPassword) Res.string.auth_hide else Res.string.auth_show), fontSize = 11.sp)
                        }
                    },
                    colors = fieldColors,
                )
                if (register && passwordError == null) {
                    Text(
                        stringResource(Res.string.auth_password_hint), color = CatalogueColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Turquoise),
                    ) {
                        Text(stringResource(if (resetRequired) Res.string.recovery_title else Res.string.recovery_forgot))
                    }
                }
                AuthPrimaryAction(
                    stringResource(when {
                        submitting -> Res.string.auth_submitting
                        register -> Res.string.auth_register
                        else -> Res.string.auth_login
                    }),
                    submit, enabled = !submitting,
                )
            }
            TextButton(
                onClick = {
                    password = ""
                    email = ""
                    name = ""
                    showPassword = false
                    focus.clearFocus()
                    store.dismissFeedback()
                    onMode(if (register) AuthFormMode.LOGIN else AuthFormMode.REGISTER)
                },
                enabled = !submitting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Turquoise),
            ) {
                Text(stringResource(if (register) Res.string.auth_have_account else Res.string.auth_no_account))
            }
        } else if (state.busy == null) {
            AuthPrimaryAction(stringResource(Res.string.auth_retry_session), store::refresh)
            TextButton(
                onClick = store::logout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Muted),
            ) {
                Text(stringResource(Res.string.auth_logout_device))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AuthOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CatalogueColors.Text),
            ) {
                Text(stringResource(Res.string.auth_continue_guest))
            }
            Text(
                stringResource(Res.string.auth_guest_note), Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuthScreenHeader(title: String, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconAction(AtlasIcon.Back, stringResource(Res.string.back), onBack, tint = CatalogueColors.Muted)
            Box(Modifier.weight(1f)) { HackAtlasBrand(showDemo = false) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val typography = MaterialTheme.typography
            val measurer = rememberTextMeasurer()
            val availableWidth = with(LocalDensity.current) { maxWidth.roundToPx() }
            val titleStyle = listOf(typography.displayLarge, typography.displayMedium, typography.displaySmall)
                .firstOrNull {
                    measurer.measure(AnnotatedString(title), it, softWrap = false, maxLines = 1).size.width <= availableWidth
                } ?: typography.displaySmall
            Text(title, Modifier.semantics { heading() }, color = CatalogueColors.Text, style = titleStyle)
        }
    }
}

@Composable
private fun AuthPrimaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CatalogueColors.Lime, contentColor = CatalogueColors.Ink,
            disabledContainerColor = CatalogueColors.Background, disabledContentColor = CatalogueColors.Muted,
        ),
    ) {
        Text(label, textAlign = TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        OutlineIcon(AtlasIcon.ArrowRight, Modifier.size(18.dp), LocalContentColor.current)
    }
}

@Composable
private fun authScreenFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CatalogueColors.Background, unfocusedContainerColor = CatalogueColors.Background,
    disabledContainerColor = CatalogueColors.Background, errorContainerColor = CatalogueColors.Background,
    focusedBorderColor = CatalogueColors.Lime, unfocusedBorderColor = AuthOutline,
    disabledBorderColor = CatalogueColors.Border, errorBorderColor = AtlasColors.Danger,
    focusedTextColor = CatalogueColors.Text, unfocusedTextColor = CatalogueColors.Text,
    disabledTextColor = CatalogueColors.Muted, errorTextColor = CatalogueColors.Text,
    focusedLabelColor = CatalogueColors.Lime, unfocusedLabelColor = CatalogueColors.Muted,
    disabledLabelColor = CatalogueColors.Muted, errorLabelColor = AtlasColors.Danger,
    errorSupportingTextColor = AtlasColors.Danger, cursorColor = CatalogueColors.Lime, errorCursorColor = AtlasColors.Danger,
)

@Composable
internal fun AuthField(
    value: String, onChange: (String) -> Unit, label: String, errorText: String?, enabled: Boolean,
    keyboard: KeyboardOptions, actions: KeyboardActions, visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = AtlasColors.Surface, unfocusedContainerColor = AtlasColors.Surface,
        disabledContainerColor = AtlasColors.Surface, errorContainerColor = AtlasColors.Surface,
        focusedBorderColor = AtlasColors.Primary, unfocusedBorderColor = AtlasColors.Border,
        focusedTextColor = AtlasColors.TextPrimary, unfocusedTextColor = AtlasColors.TextPrimary,
        unfocusedLabelColor = AtlasColors.TextSecondary, cursorColor = AtlasColors.Primary,
    ),
) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, isError = errorText != null,
        supportingText = if (errorText != null) ({ Text(errorText) }) else null,
        shape = RoundedCornerShape(AtlasDimensions.FieldRadius), keyboardOptions = keyboard, keyboardActions = actions,
        visualTransformation = visual, trailingIcon = trailing, colors = colors,
    )
}

@Composable
fun AuthFeedback(state: AuthUiState) = AuthFeedback(
    state, containerColor = AtlasColors.SurfaceRaised, borderColor = AtlasColors.Border,
    progressColor = AtlasColors.Primary, messageColor = AtlasColors.Cyan,
)

@Composable
private fun AuthFeedback(
    state: AuthUiState, containerColor: Color, borderColor: Color, progressColor: Color, messageColor: Color,
) {
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
    Column(
        Modifier.fillMaxWidth().background(containerColor, shape).border(1.dp, borderColor, shape).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = progressColor, trackColor = borderColor)
        }
        Text(text, color = if (state.error != null) AtlasColors.Danger else messageColor, style = MaterialTheme.typography.bodyMedium)
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

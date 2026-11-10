package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.auth.AuthError
import dev.hackatlas.mobile.data.auth.N2HttpAction
import dev.hackatlas.mobile.data.auth.contractTextLength
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.IconAction
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PasswordRecoveryScreen(store: PasswordRecoveryStore, state: PasswordRecoveryState, onLogin: () -> Unit) {
    val focus = LocalFocusManager.current
    val busy = state.busy != null
    val request = { if (state.canRequest) { focus.clearFocus(); store.requestCode() } }
    val verify = { if (state.canVerify) { focus.clearFocus(); store.verifyCode() } }
    val confirm = { if (state.canReset) { focus.clearFocus(); store.resetPassword() } }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(AtlasIcon.Back, stringResource(Res.string.back), onLogin)
        TechLabel(stringResource(Res.string.auth_kicker))
        PixelHeading(stringResource(Res.string.recovery_title), fontSize = 24.sp)
        RecoveryFeedback(state)

        when (state.step) {
            RecoveryStep.EMAIL -> {
                RecoveryText(stringResource(Res.string.recovery_email_body))
                val emailError = if (state.email.isNotEmpty() && !isPlausibleAuthEmail(state.email))
                    stringResource(Res.string.auth_email_invalid) else null
                AuthField(
                    state.email, store::changeEmail, stringResource(Res.string.auth_email), emailError, !busy,
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    KeyboardActions(onDone = { request() }),
                )
                NeonButton(stringResource(Res.string.verification_request), request, Modifier.fillMaxWidth(), state.canRequest)
            }
            RecoveryStep.CODE -> {
                RecoveryText(stringResource(Res.string.recovery_code_body))
                AuthField(
                    state.code, store::changeCode, stringResource(Res.string.verification_code), null, !busy,
                    KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    KeyboardActions(onDone = { verify() }), visual = PasswordVisualTransformation(),
                )
                RecoveryText(stringResource(Res.string.verification_ttl, state.proofSeconds))
                NeonButton(stringResource(Res.string.recovery_verify_code), verify, Modifier.fillMaxWidth(), state.canVerify)
                TextButton(onClick = request, enabled = state.canRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.verification_resend))
                }
                TextButton(onClick = store::restart, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.recovery_change_email))
                }
            }
            RecoveryStep.PASSWORD -> {
                val length = contractTextLength(state.password, allowNul = true)
                val passwordError = when {
                    length < 0 -> stringResource(Res.string.auth_password_invalid_characters)
                    length > 256 -> stringResource(Res.string.auth_password_long)
                    else -> null
                }
                AuthField(
                    state.password, store::changePassword, stringResource(Res.string.recovery_new_password), passwordError, !busy,
                    KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    KeyboardActions(onDone = { confirm() }), visual = PasswordVisualTransformation(),
                )
                RecoveryText(stringResource(Res.string.auth_password_hint))
                RecoveryText(stringResource(Res.string.recovery_grant_ttl, state.proofSeconds))
                NeonButton(stringResource(Res.string.recovery_set_password), confirm, Modifier.fillMaxWidth(), state.canReset)
            }
            RecoveryStep.UNCERTAIN -> {
                RecoveryText(stringResource(Res.string.recovery_confirm_unknown))
                NeonButton(stringResource(Res.string.auth_login), onLogin, Modifier.fillMaxWidth())
                TextButton(onClick = store::restart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.recovery_restart))
                }
            }
            RecoveryStep.UNAVAILABLE -> RecoveryText(stringResource(Res.string.recovery_unavailable))
            RecoveryStep.CLOSED, RecoveryStep.COMPLETE -> Unit
        }
        if (state.resendSeconds > 0 && (state.step == RecoveryStep.EMAIL || state.step == RecoveryStep.CODE))
            RecoveryText(stringResource(Res.string.verification_resend_in, state.resendSeconds))
        if (state.retrySeconds > 0 && (state.step == RecoveryStep.CODE || state.step == RecoveryStep.PASSWORD))
            RecoveryText(stringResource(Res.string.auth_rate_limit_seconds, state.retrySeconds))
    }
}

@Composable
private fun RecoveryFeedback(state: PasswordRecoveryState) {
    val error = state.error
    val message = when {
        state.busy != null -> stringResource(Res.string.auth_submitting)
        state.notice == RecoveryNotice.NEW_CODE_REQUIRED && error is AuthError.Http &&
            error.n2Action == N2HttpAction.RETRY_MANUALLY ->
            stringResource(Res.string.recovery_unavailable_new_code)
        state.notice == RecoveryNotice.NEW_CODE_REQUIRED -> stringResource(Res.string.recovery_new_code_required)
        state.notice == RecoveryNotice.CODE_EXPIRED -> stringResource(Res.string.verification_code_expired)
        state.notice == RecoveryNotice.GRANT_EXPIRED -> stringResource(Res.string.recovery_grant_expired)
        error is AuthError.Http && error.n2Action == N2HttpAction.INVALID_EMAIL_ACTION ->
            stringResource(Res.string.verification_invalid)
        error is AuthError.Http && error.n2Action == N2HttpAction.RATE_LIMITED -> stringResource(Res.string.auth_rate_limit)
        error != null && state.step != RecoveryStep.UNAVAILABLE -> authErrorText(error)
        else -> null
    } ?: return
    Text(
        message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error == null) AtlasColors.Cyan else AtlasColors.Danger,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RecoveryText(message: String) {
    Text(message, color = AtlasColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
}

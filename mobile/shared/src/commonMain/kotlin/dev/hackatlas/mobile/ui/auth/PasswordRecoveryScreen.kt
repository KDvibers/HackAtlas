package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.hackatlas.mobile.data.auth.AuthError
import dev.hackatlas.mobile.data.auth.N2HttpAction
import dev.hackatlas.mobile.data.auth.contractTextLength
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PasswordRecoveryScreen(store: PasswordRecoveryStore, state: PasswordRecoveryState, onLogin: () -> Unit) {
    val focus = LocalFocusManager.current
    val busy = state.busy != null
    val request = {
        if (state.canRequest) {
            focus.clearFocus()
            store.requestCode()
        }
    }
    val verify = {
        if (state.canVerify) {
            focus.clearFocus()
            store.verifyCode()
        }
    }
    val confirm = {
        if (state.canReset) {
            focus.clearFocus()
            store.resetPassword()
        }
    }
    val restart = {
        focus.clearFocus()
        store.restart()
    }
    val close = {
        focus.clearFocus()
        onLogin()
    }
    val fieldColors = authFormFieldColors()
    val resendHint = state.resendSeconds.takeIf { it > 0 }?.let {
        stringResource(Res.string.verification_resend_in, it)
    }
    val retryHint = state.retrySeconds.takeIf { it > 0 }?.let {
        stringResource(Res.string.auth_rate_limit_seconds, it)
    }
    val hintStyle = MaterialTheme.typography.bodySmall

    AuthScreenLayout(stringResource(Res.string.recovery_title), close) {
        RecoveryFeedback(state)

        when (state.step) {
            RecoveryStep.EMAIL -> AuthFormPanel {
                RecoveryText(stringResource(Res.string.recovery_email_body))
                val emailError = if (state.email.isNotEmpty() && !isPlausibleAuthEmail(state.email))
                    stringResource(Res.string.auth_email_invalid) else null
                AuthField(
                    state.email, store::changeEmail, stringResource(Res.string.auth_email), emailError, !busy,
                    KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    KeyboardActions(onDone = { request() }), colors = fieldColors,
                )
                if (resendHint != null) RecoveryText(resendHint, hintStyle)
                AuthPrimaryAction(stringResource(Res.string.verification_request), request, state.canRequest)
            }
            RecoveryStep.CODE -> {
                AuthFormPanel {
                    RecoveryText(stringResource(Res.string.recovery_code_body))
                    AuthField(
                        state.code, store::changeCode, stringResource(Res.string.verification_code), null, !busy,
                        KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        KeyboardActions(onDone = { verify() }), visual = PasswordVisualTransformation(),
                        colors = fieldColors,
                    )
                    RecoveryText(stringResource(Res.string.verification_ttl, state.proofSeconds), hintStyle)
                    if (retryHint != null) RecoveryText(retryHint, hintStyle)
                    AuthPrimaryAction(stringResource(Res.string.recovery_verify_code), verify, state.canVerify)
                }
                Column {
                    AuthSecondaryAction(
                        stringResource(Res.string.verification_resend), request, state.canRequest, hint = resendHint,
                    )
                    AuthSecondaryAction(stringResource(Res.string.recovery_change_email), restart, !busy)
                }
            }
            RecoveryStep.PASSWORD -> AuthFormPanel {
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
                    colors = fieldColors,
                )
                RecoveryText(stringResource(Res.string.auth_password_hint), hintStyle)
                RecoveryText(stringResource(Res.string.recovery_grant_ttl, state.proofSeconds), hintStyle)
                if (retryHint != null) RecoveryText(retryHint, hintStyle)
                AuthPrimaryAction(stringResource(Res.string.recovery_set_password), confirm, state.canReset)
            }
            RecoveryStep.UNCERTAIN -> {
                AuthFormPanel {
                    RecoveryText(stringResource(Res.string.recovery_confirm_unknown))
                    AuthPrimaryAction(stringResource(Res.string.auth_login), close)
                }
                AuthSecondaryAction(stringResource(Res.string.recovery_restart), restart)
            }
            RecoveryStep.UNAVAILABLE -> AuthFormPanel {
                RecoveryText(stringResource(Res.string.recovery_unavailable))
            }
            RecoveryStep.CLOSED, RecoveryStep.COMPLETE -> Unit
        }
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
    AuthStatusPanel(message, busy = state.busy != null, isError = error != null)
}

@Composable
private fun RecoveryText(message: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(message, color = CatalogueColors.Muted, style = style)
}

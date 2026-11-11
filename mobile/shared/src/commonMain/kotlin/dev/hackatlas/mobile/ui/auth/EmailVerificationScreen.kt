package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.hackatlas.mobile.data.auth.AuthError
import dev.hackatlas.mobile.data.auth.N2HttpAction
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EmailVerificationScreen(
    store: EmailVerificationStore,
    state: EmailVerificationState,
    onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val busy = state.busy != null
    val close = {
        focus.clearFocus()
        onBack()
    }
    val request = {
        focus.clearFocus()
        store.requestCode()
    }
    val confirm = {
        if (state.canConfirm) {
            focus.clearFocus()
            store.confirmCode()
        }
    }
    val resendHint = state.resendSeconds.takeIf { it > 0 }?.let {
        stringResource(Res.string.verification_resend_in, it)
    }
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val hintStyle = MaterialTheme.typography.bodySmall

    AuthScreenLayout(stringResource(Res.string.verification_title), close) {
        VerificationFeedback(state)

        when {
            state.error == AuthError.UnsupportedServer -> Unit
            state.needsProfileCheck || !state.pending -> AuthFormPanel {
                AuthPrimaryAction(stringResource(Res.string.verification_check), store::checkProfile, enabled = !busy)
            }
            state.challenge != null -> {
                AuthFormPanel {
                    Text(stringResource(Res.string.verification_code_body), color = CatalogueColors.Muted, style = bodyStyle)
                    AuthField(
                        state.code, store::changeCode, stringResource(Res.string.verification_code), errorText = null,
                        enabled = !busy && state.confirmSeconds == 0,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        actions = KeyboardActions(onDone = { confirm() }),
                        visual = PasswordVisualTransformation(),
                        colors = authFormFieldColors(),
                    )
                    Text(
                        stringResource(Res.string.verification_ttl, state.challengeSeconds),
                        color = CatalogueColors.Muted, style = hintStyle,
                    )
                    if (state.confirmSeconds > 0) {
                        Text(
                            stringResource(Res.string.auth_rate_limit_seconds, state.confirmSeconds),
                            color = CatalogueColors.Muted, style = hintStyle,
                        )
                    }
                    AuthPrimaryAction(stringResource(Res.string.verification_confirm), confirm, state.canConfirm)
                }
                AuthSecondaryAction(
                    stringResource(Res.string.verification_resend), request, state.canRequest, hint = resendHint,
                )
            }
            else -> AuthFormPanel {
                Text(stringResource(Res.string.verification_body), color = CatalogueColors.Muted, style = bodyStyle)
                if (resendHint != null) Text(resendHint, color = CatalogueColors.Muted, style = hintStyle)
                AuthPrimaryAction(stringResource(Res.string.verification_request), request, state.canRequest)
            }
        }
    }
}

@Composable
private fun VerificationFeedback(state: EmailVerificationState) {
    val error = state.error
    val message = when {
        state.busy != null -> stringResource(
            if (state.busy == VerificationOperation.REQUEST) Res.string.auth_submitting else Res.string.verification_checking,
        )
        error is AuthError.Http && error.n2Action == N2HttpAction.INVALID_EMAIL_ACTION ->
            stringResource(Res.string.verification_invalid)
        error is AuthError.Http && error.n2Action == N2HttpAction.RATE_LIMITED ->
            stringResource(Res.string.auth_rate_limit)
        error == AuthError.UnsupportedServer -> stringResource(Res.string.verification_unavailable)
        error != null -> authErrorText(error)
        state.profileStillPending -> stringResource(Res.string.verification_still_pending)
        state.challengeExpired -> stringResource(Res.string.verification_code_expired)
        else -> null
    } ?: return
    AuthStatusPanel(message, busy = state.busy != null, isError = error != null)
}

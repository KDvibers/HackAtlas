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
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.IconAction
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EmailVerificationScreen(
    store: EmailVerificationStore,
    state: EmailVerificationState,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(AtlasIcon.Back, stringResource(Res.string.back), onBack)
        TechLabel(stringResource(Res.string.auth_kicker))
        PixelHeading(stringResource(Res.string.verification_title), fontSize = 24.sp)
        Text(
            stringResource(if (state.challenge == null) Res.string.verification_body else Res.string.verification_code_body),
            color = AtlasColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        VerificationFeedback(state)
        if (state.error == AuthError.UnsupportedServer) return@Column
        if (state.needsProfileCheck || !state.pending) {
            NeonButton(
                stringResource(Res.string.verification_check), store::checkProfile, Modifier.fillMaxWidth(),
                enabled = state.busy == null,
            )
        } else if (state.challenge != null) {
            AuthField(
                state.code, store::changeCode, stringResource(Res.string.verification_code), errorText = null,
                enabled = state.busy == null && state.confirmSeconds == 0,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                actions = KeyboardActions(onDone = { store.confirmCode() }),
                visual = PasswordVisualTransformation(),
            )
            Text(stringResource(Res.string.verification_ttl, state.challengeSeconds), color = AtlasColors.TextMuted)
            NeonButton(
                stringResource(Res.string.verification_confirm), store::confirmCode, Modifier.fillMaxWidth(),
                enabled = state.canConfirm,
            )
            TextButton(onClick = store::requestCode, enabled = state.canRequest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.verification_resend))
            }
        } else {
            NeonButton(
                stringResource(Res.string.verification_request), store::requestCode, Modifier.fillMaxWidth(),
                enabled = state.canRequest,
            )
        }
        if (state.resendSeconds > 0 && !state.needsProfileCheck)
            Text(stringResource(Res.string.verification_resend_in, state.resendSeconds), color = AtlasColors.TextMuted)
        if (state.confirmSeconds > 0 && !state.needsProfileCheck)
            Text(stringResource(Res.string.auth_rate_limit_seconds, state.confirmSeconds), color = AtlasColors.TextMuted)
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
    Text(
        message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error == null) AtlasColors.Cyan else AtlasColors.Danger,
        style = MaterialTheme.typography.bodyMedium,
    )
}

package dev.hackatlas.mobile.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hackatlas.mobile.data.auth.AuthError
import dev.hackatlas.mobile.design.AtlasDimensions
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.domain.FilterMetadata
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import dev.hackatlas.mobile.ui.ProfileIdentity
import dev.hackatlas.mobile.ui.ProfilePanel
import dev.hackatlas.mobile.ui.ProfilePrimaryAction
import dev.hackatlas.mobile.ui.ProfileSectionHeading
import dev.hackatlas.mobile.ui.auth.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(
    store: ProfileEditStore,
    metadata: FilterMetadata?,
    metadataLoading: Boolean,
    onRetryMetadata: () -> Unit,
    onBack: () -> Unit,
) {
    val state by store.state.collectAsState()
    var confirmReload by remember { mutableStateOf(false) }
    val draft = state.draft ?: return
    val focus = LocalFocusManager.current
    val close = {
        focus.clearFocus()
        onBack()
    }
    val editable = !state.busy
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    val keyboard = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
    val fieldColors = authFormFieldColors()
    val hintStyle = MaterialTheme.typography.bodySmall

    AuthScreenLayout(stringResource(Res.string.profile_edit_title), close) {
        ProfilePanel {
            ProfileIdentity(
                name = stringResource(Res.string.profile_avatar),
                initial = draft.displayName.trim().firstOrNull()?.uppercase(),
                hooded = draft.avatarKey == "HOODED",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("HOODED" to Res.string.profile_avatar_hooded, "INITIALS" to Res.string.profile_avatar_initials)
                    .forEach { (key, label) ->
                        ProfileChoice(stringResource(label), draft.avatarKey == key, editable) {
                            store.change { it.copy(avatarKey = key) }
                        }
                    }
            }
        }

        ProfilePanel {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.baseline?.email.orEmpty(), color = CatalogueColors.Text, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.profile_readonly_email), color = CatalogueColors.Muted, style = hintStyle)
            }
            AuthField(
                draft.displayName, { value -> store.change { it.copy(displayName = value) } },
                stringResource(Res.string.auth_name),
                if ("displayName" in state.errors) stringResource(Res.string.auth_name_invalid) else null,
                editable, keyboard, next, colors = fieldColors,
            )
            AuthField(
                draft.city, { value -> store.change { it.copy(city = value) } },
                stringResource(Res.string.profile_city),
                if ("city" in state.errors) stringResource(Res.string.profile_text_long) else null,
                editable, keyboard, next, colors = fieldColors,
            )
            AuthField(
                draft.countryCode, { value -> store.change { it.copy(countryCode = value) } },
                stringResource(Res.string.profile_country),
                if ("countryCode" in state.errors) stringResource(Res.string.profile_country_invalid) else null,
                editable, KeyboardOptions(
                    imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                ), next, colors = fieldColors,
            )
            Text(stringResource(Res.string.profile_country_hint), color = CatalogueColors.Muted, style = hintStyle)
            AuthField(
                draft.roleLabel, { value -> store.change { it.copy(roleLabel = value) } },
                stringResource(Res.string.profile_role),
                if ("roleLabel" in state.errors) stringResource(Res.string.profile_text_long) else null,
                editable, keyboard.copy(imeAction = ImeAction.Done), KeyboardActions(onDone = { focus.clearFocus() }),
                colors = fieldColors,
            )
            Text(stringResource(Res.string.profile_optional_hint), color = CatalogueColors.Muted, style = hintStyle)
        }

        ProfilePanel {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProfileSectionHeading(AtlasIcon.Sparkles, stringResource(Res.string.interests))
                Text(stringResource(Res.string.profile_interests_limit), color = CatalogueColors.Muted, style = hintStyle)
            }
            if (metadata == null) {
                Text(
                    stringResource(if (metadataLoading) Res.string.profile_interests_loading else Res.string.profile_interests_error),
                    color = CatalogueColors.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                if (!metadataLoading) {
                    AuthSecondaryAction(stringResource(Res.string.profile_interests_retry), onRetryMetadata)
                }
            }
            val choices = metadata?.interests.orEmpty()
            val ids = (choices.map { it.id } + draft.interests).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ids.forEach { id ->
                    val selected = id in draft.interests
                    ProfileChoice(choices.firstOrNull { it.id == id }?.label ?: id, selected, editable && (selected || draft.interests.size < 20)) {
                        store.change { it.copy(interests = if (selected) it.interests - id else it.interests + id) }
                    }
                }
            }
        }

        if (state.busy) {
            AuthStatusPanel(stringResource(Res.string.profile_save_inflight), busy = true, isError = false)
        } else if (state.error != null) {
            val message = if ((state.error as? AuthError.Http)?.status == 400) Res.string.profile_save_validation
                else Res.string.profile_save_error
            AuthStatusPanel(stringResource(message), busy = false, isError = true)
            AuthSecondaryAction(
                stringResource(Res.string.profile_check_saved),
                onClick = {
                    if (state.dirty) confirmReload = true else store.reload()
                },
            )
        }
        AuthPrimaryAction(
            stringResource(if (state.busy) Res.string.profile_saving else Res.string.profile_save),
            onClick = {
                focus.clearFocus()
                store.save()
            },
            enabled = editable && state.dirty,
        )
        AuthSecondaryAction(stringResource(Res.string.auth_cancel), close)
    }
    if (confirmReload) {
        ProfileEditDiscardDialog(
            confirmLabel = stringResource(Res.string.profile_check_saved),
            onConfirm = {
                confirmReload = false
                store.reload()
            },
            onDismiss = { confirmReload = false },
        )
    }
}

@Composable
internal fun ProfileEditDiscardDialog(confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_discard_title)) },
        text = { Text(stringResource(Res.string.profile_discard_body)) },
        shape = RoundedCornerShape(AtlasDimensions.CardRadius),
        containerColor = CatalogueColors.Surface,
        titleContentColor = CatalogueColors.Text,
        textContentColor = CatalogueColors.Muted,
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProfilePrimaryAction(stringResource(Res.string.profile_continue_edit), onDismiss)
                TextButton(
                    onClick = onConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Muted),
                ) {
                    Text(confirmLabel, textAlign = TextAlign.Center)
                }
            }
        },
    )
}

@Composable
private fun ProfileChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(AtlasDimensions.ChipRadius),
        border = BorderStroke(1.dp, if (selected && enabled) CatalogueColors.Lime else CatalogueColors.Border),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CatalogueColors.Background, labelColor = CatalogueColors.Text,
            selectedContainerColor = CatalogueColors.Lime, selectedLabelColor = CatalogueColors.Ink,
            disabledContainerColor = CatalogueColors.Background, disabledLabelColor = CatalogueColors.Muted,
        ),
    )
}

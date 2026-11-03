package dev.hackatlas.mobile.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import dev.hackatlas.mobile.domain.FilterMetadata
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.IconAction
import dev.hackatlas.mobile.ui.auth.AuthField
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(store:ProfileEditStore,metadata:FilterMetadata?,metadataLoading:Boolean,onRetryMetadata:()->Unit,onBack:()->Unit) {
    val state by store.state.collectAsState()
    var confirmReload by remember{mutableStateOf(false)}
    val draft=state.draft ?: return
    val focus=LocalFocusManager.current
    val next=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Down)})
    val keyboard=KeyboardOptions(imeAction=ImeAction.Next,capitalization=KeyboardCapitalization.Sentences)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        IconAction(AtlasIcon.Back,stringResource(Res.string.back),onBack)
        TechLabel(stringResource(Res.string.profile_edit_kicker))
        PixelHeading(stringResource(Res.string.profile_edit_title),fontSize=24.sp)
        Text(stringResource(Res.string.profile_edit_body),color=AtlasColors.TextSecondary,style=MaterialTheme.typography.bodyMedium)
        if(state.error!=null) {
            Text(stringResource(if((state.error as? AuthError.Http)?.status==400)Res.string.profile_save_validation else Res.string.profile_save_error),
                color=AtlasColors.Danger,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            TextButton(onClick={if(state.dirty)confirmReload=true else store.reload()},enabled=!state.busy){Text(stringResource(Res.string.profile_check_saved))}
        }
        if(state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(),color=AtlasColors.Primary,trackColor=AtlasColors.Border)
            Text(stringResource(Res.string.profile_save_inflight),color=AtlasColors.Cyan,style=MaterialTheme.typography.bodySmall)
        }
        Text(state.baseline?.email.orEmpty(),color=AtlasColors.TextSecondary,style=MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.profile_readonly_email),color=AtlasColors.TextMuted,style=MaterialTheme.typography.bodySmall)
        AuthField(draft.displayName,{value->store.change{it.copy(displayName=value)}},stringResource(Res.string.auth_name),
            if("displayName" in state.errors)stringResource(Res.string.auth_name_invalid) else null,!state.busy,keyboard,next)
        AuthField(draft.city,{value->store.change{it.copy(city=value)}},stringResource(Res.string.profile_city),
            if("city" in state.errors)stringResource(Res.string.profile_text_long) else null,!state.busy,keyboard,next)
        AuthField(draft.countryCode,{value->store.change{it.copy(countryCode=value)}},stringResource(Res.string.profile_country),
            if("countryCode" in state.errors)stringResource(Res.string.profile_country_invalid) else null,!state.busy,
            KeyboardOptions(imeAction=ImeAction.Next,capitalization=KeyboardCapitalization.Characters,autoCorrectEnabled=false),next)
        Text(stringResource(Res.string.profile_country_hint),color=AtlasColors.TextMuted,style=MaterialTheme.typography.bodySmall)
        AuthField(draft.roleLabel,{value->store.change{it.copy(roleLabel=value)}},stringResource(Res.string.profile_role),
            if("roleLabel" in state.errors)stringResource(Res.string.profile_text_long) else null,!state.busy,
            keyboard.copy(imeAction=ImeAction.Done),KeyboardActions(onDone={focus.clearFocus()}))
        Text(stringResource(Res.string.profile_optional_hint),color=AtlasColors.TextMuted,style=MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.profile_avatar),style=MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("HOODED" to Res.string.profile_avatar_hooded,"INITIALS" to Res.string.profile_avatar_initials).forEach{(key,label)->
                ProfileChoice(stringResource(label),draft.avatarKey==key,!state.busy){store.change{it.copy(avatarKey=key)}}
            }
        }
        Text(stringResource(Res.string.interests),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.profile_interests_limit),color=AtlasColors.TextMuted,style=MaterialTheme.typography.bodySmall)
        if(metadata==null) {
            Text(stringResource(if(metadataLoading)Res.string.profile_interests_loading else Res.string.profile_interests_error),color=AtlasColors.TextSecondary)
            if(!metadataLoading)TextButton(onClick=onRetryMetadata){Text(stringResource(Res.string.profile_interests_retry))}
        }
        val choices=metadata?.interests.orEmpty()
        val ids=choices.map{it.id}+draft.interests.filter{chosen->choices.none{it.id==chosen}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ids.distinct().forEach{id->
                val selected=id in draft.interests
                ProfileChoice(choices.firstOrNull{it.id==id}?.label?:id,selected,!state.busy && (selected||draft.interests.size<20)) {
                    store.change{it.copy(interests=if(selected)it.interests-id else it.interests+id)}
                }
            }
        }
        NeonButton(stringResource(if(state.busy)Res.string.profile_saving else Res.string.profile_save),
            {focus.clearFocus();store.save()},Modifier.fillMaxWidth(),enabled=!state.busy && state.dirty)
        TextButton(onClick=onBack,modifier=Modifier.fillMaxWidth()){Text(stringResource(Res.string.auth_cancel),color=AtlasColors.TextSecondary)}
    }
    if(confirmReload)AlertDialog(onDismissRequest={confirmReload=false},
        title={Text(stringResource(Res.string.profile_discard_title))},text={Text(stringResource(Res.string.profile_discard_body))},
        containerColor=AtlasColors.SurfaceRaised,titleContentColor=AtlasColors.TextPrimary,
        confirmButton={TextButton(onClick={confirmReload=false;store.reload()}){Text(stringResource(Res.string.profile_check_saved))}},
        dismissButton={TextButton(onClick={confirmReload=false}){Text(stringResource(Res.string.profile_continue_edit))}})
}

@Composable
private fun ProfileChoice(label:String,selected:Boolean,enabled:Boolean,onClick:()->Unit) {
    FilterChip(selected=selected,onClick=onClick,enabled=enabled,label={Text(label)},
        modifier=Modifier.heightIn(min=48.dp),shape=RoundedCornerShape(AtlasDimensions.ChipRadius),
        border=BorderStroke(1.dp,if(selected && enabled)AtlasColors.Primary else AtlasColors.Border),
        colors=FilterChipDefaults.filterChipColors(containerColor=AtlasColors.Surface,labelColor=AtlasColors.TextSecondary,
            selectedContainerColor=AtlasColors.Primary.copy(alpha=.15f),selectedLabelColor=AtlasColors.TextPrimary))
}

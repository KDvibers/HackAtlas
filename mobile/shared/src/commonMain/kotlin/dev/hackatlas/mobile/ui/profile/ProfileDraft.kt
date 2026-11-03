package dev.hackatlas.mobile.ui.profile

import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.data.auth.profileTextLength
import kotlinx.serialization.json.*

data class ProfileDraft(val displayName:String,val city:String,val countryCode:String,
    val roleLabel:String,val avatarKey:String,val interests:List<String>) {
    fun validate():Map<String,Boolean> = buildMap {
        if(profileTextLength(displayName.trim()) !in 1..60) put("displayName",true)
        if(profileTextLength(city.trim()) !in 0..100) put("city",true)
        if(countryCode.isNotBlank() && !Regex("[A-Z]{2}").matches(countryCode.trim().uppercase())) put("countryCode",true)
        if(profileTextLength(roleLabel.trim()) !in 0..100) put("roleLabel",true)
        if(avatarKey !in setOf("HOODED","INITIALS")) put("avatarKey",true)
        if(interests.size>20 || interests.distinct().size!=interests.size || interests.any{profileTextLength(it) !in 1..50}) put("interests",true)
    }
    override fun toString()="ProfileDraft([REDACTED])"
    companion object {
        fun from(user:ProfileDto)=ProfileDraft(user.displayName,user.city.orEmpty(),user.countryCode.orEmpty(),user.roleLabel.orEmpty(),user.avatarKey,user.interests.toList())
    }
}

fun profilePatch(base:ProfileDto,draft:ProfileDraft,uncertainFields:Set<String> = emptySet()):JsonObject=buildJsonObject {
    if("displayName" in uncertainFields || draft.displayName.trim()!=base.displayName.trim()) put("displayName",draft.displayName.trim())
    fun nullable(key:String,next:String,previous:String?) {
        val normalized=next.trim().ifEmpty{null}
        if(key in uncertainFields || normalized!=previous?.trim()?.ifEmpty{null}) put(key,normalized?.let(::JsonPrimitive)?:JsonNull)
    }
    nullable("city",draft.city,base.city);nullable("countryCode",draft.countryCode.uppercase(),base.countryCode)
    nullable("roleLabel",draft.roleLabel,base.roleLabel)
    if("avatarKey" in uncertainFields || draft.avatarKey!=base.avatarKey) put("avatarKey",draft.avatarKey)
    if("interests" in uncertainFields || draft.interests.toSet()!=base.interests.toSet()) put("interests",JsonArray(draft.interests.map(::JsonPrimitive)))
}

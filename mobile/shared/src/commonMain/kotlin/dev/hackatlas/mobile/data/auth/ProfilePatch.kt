package dev.hackatlas.mobile.data.auth

import kotlinx.serialization.json.*

/** Closed Starter UpdateProfile schema. Never accept arbitrary user/account fields. */
internal fun validateProfilePatch(patch: JsonObject) {
    require(patch.isNotEmpty() && patch.keys.all { it in setOf("displayName","city","countryCode","roleLabel","avatarKey","interests") })
    patch.forEach { (key,value) ->
        if(key == "interests") {
            require(value is JsonArray && value.size <= 20 && value.distinct().size == value.size)
            value.forEach { require(it is JsonPrimitive && it.isString && profileTextLength(it.content) in 1..50) }
        } else if(value == JsonNull) require(key in setOf("city","countryCode","roleLabel"))
        else {
            require(value is JsonPrimitive && value.isString)
            val text=value.content
            require(when(key) {
                "displayName" -> text.isNotBlank() && profileTextLength(text) in 1..60
                "city","roleLabel" -> profileTextLength(text) in 0..100
                "countryCode" -> Regex("[A-Z]{2}").matches(text)
                "avatarKey" -> text in setOf("HOODED","INITIALS")
                else -> false
            })
        }
    }
}

/** OpenAPI string lengths are Unicode code points; reject NUL/unpaired surrogates like B2. */
internal fun profileTextLength(text: String): Int = contractTextLength(text)

internal fun contractTextLength(text: String, allowNul: Boolean = false): Int {
    var index = 0
    var count = 0
    while (index < text.length) {
        val char = text[index++]
        if ((!allowNul && char == '\u0000') || char.isLowSurrogate()) return -1
        if (char.isHighSurrogate() && (index >= text.length || !text[index++].isLowSurrogate())) return -1
        count++
    }
    return count
}

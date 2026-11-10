package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.contractTextLength

enum class AuthFormMode { LOGIN, REGISTER }
enum class AuthFieldError { EMAIL_INVALID, PASSWORD_SHORT, PASSWORD_LONG, PASSWORD_INVALID, NAME_INVALID }
data class AuthFormErrors(val email: AuthFieldError? = null, val password: AuthFieldError? = null, val name: AuthFieldError? = null) {
    fun isEmpty() = email == null && password == null && name == null
}

/** Client-side hints; the server remains authoritative. Password is never normalized. */
fun validateAuthForm(mode: AuthFormMode, email: String, password: String, name: String): AuthFormErrors {
    val minimum = if (mode == AuthFormMode.REGISTER) 12 else 1
    val passwordLength = contractTextLength(password, allowNul = true)
    return AuthFormErrors(
        email = if (isPlausibleAuthEmail(email)) null else AuthFieldError.EMAIL_INVALID,
        password = when {
            passwordLength < 0 -> AuthFieldError.PASSWORD_INVALID
            passwordLength < minimum -> AuthFieldError.PASSWORD_SHORT
            passwordLength > 256 -> AuthFieldError.PASSWORD_LONG
            else -> null
        },
        name = if (mode == AuthFormMode.REGISTER && name.trim().length !in 1..60) AuthFieldError.NAME_INVALID else null,
    )
}

private val AUTH_EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

internal fun isPlausibleAuthEmail(email: String): Boolean =
    email.trim().let { contractTextLength(it) in 1..254 && AUTH_EMAIL.matches(it) }

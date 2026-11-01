package dev.hackatlas.mobile.ui.auth

enum class AuthFormMode { LOGIN, REGISTER }
enum class AuthFieldError { EMAIL_INVALID, PASSWORD_SHORT, PASSWORD_LONG, NAME_INVALID }
data class AuthFormErrors(val email: AuthFieldError? = null, val password: AuthFieldError? = null, val name: AuthFieldError? = null) {
    fun isEmpty() = email == null && password == null && name == null
}

/** Client-side hints; the server remains authoritative. Password is never normalized. */
fun validateAuthForm(mode: AuthFormMode, email: String, password: String, name: String): AuthFormErrors {
    val address = email.trim()
    val plausibleEmail = address.length <= 254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(address)
    val minimum = if (mode == AuthFormMode.REGISTER) 12 else 1
    return AuthFormErrors(
        email = if (plausibleEmail) null else AuthFieldError.EMAIL_INVALID,
        password = when {
            password.length < minimum -> AuthFieldError.PASSWORD_SHORT
            password.length > 256 -> AuthFieldError.PASSWORD_LONG
            else -> null
        },
        name = if (mode == AuthFormMode.REGISTER && name.trim().length !in 1..60) AuthFieldError.NAME_INVALID else null,
    )
}

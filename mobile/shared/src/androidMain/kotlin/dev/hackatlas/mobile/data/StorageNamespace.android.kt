package dev.hackatlas.mobile.data

import java.security.MessageDigest

actual fun storageNamespaceKey(canonicalBaseUrl: String): String =
    MessageDigest.getInstance("SHA-256").digest(canonicalBaseUrl.encodeToByteArray()).storageHex()

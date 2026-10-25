package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.GuestStoreScope

/** Lowercase SHA-256 of the UTF-8 input, identical on Android and iOS. */
expect fun storageNamespaceKey(canonicalBaseUrl: String): String

fun GuestStoreScope.snapshotFileName(): String = when (this) {
    GuestStoreScope.BundledDemo -> "demo-v1.json"
    is GuestStoreScope.Remote -> "remote-${storageNamespaceKey(canonicalBaseUrl)}-v1.json"
}

internal fun ByteArray.storageHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

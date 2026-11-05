package dev.hackatlas.mobile.data.saved

/** Closed directories: callers cannot pass arbitrary filesystem paths. SAVED preserves existing files. */
enum class PrivateCacheKind(val androidDirectory: String, val iosDirectory: String) {
    SAVED("account-saved", "AccountSaved"),
    APPLICATIONS("account-applications", "AccountApplications"),
}

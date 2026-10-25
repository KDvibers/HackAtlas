package dev.hackatlas.mobile.domain

/** Immutable identity of one guest store; the host selects it once for its repository owner. */
sealed interface GuestStoreScope {
    val documentScope: String

    data object BundledDemo : GuestStoreScope {
        override val documentScope: String = "demo-guest"
    }

    class Remote(canonicalBaseUrl: String) : GuestStoreScope {
        // Storage can identify a development HTTP origin. This does not enable HTTP transport.
        val canonicalBaseUrl: String = CatalogSource.Remote(canonicalBaseUrl, allowDebugHttp = true).baseUrl
        override val documentScope: String = "remote-guest@${this.canonicalBaseUrl}"

        override fun equals(other: Any?): Boolean = other is Remote && canonicalBaseUrl == other.canonicalBaseUrl
        override fun hashCode(): Int = canonicalBaseUrl.hashCode()
        override fun toString(): String = "Remote($canonicalBaseUrl)"
    }
}

/** Source/origin belongs to the repository scope; classification belongs to each stored copy. */
data class SavedEventKey(val id: String, val isDemo: Boolean)

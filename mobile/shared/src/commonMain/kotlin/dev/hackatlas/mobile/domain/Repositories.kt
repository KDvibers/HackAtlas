package dev.hackatlas.mobile.domain

import kotlinx.coroutines.flow.StateFlow

/** These M1 interfaces expose loaded local demo state; HTTP adapters are a later stage. */
interface HackathonRepository {
    val metadata: FilterMetadata
    val asOf: String
    fun catalog(query: CatalogQuery = CatalogQuery()): CatalogPage
    fun detail(id: String): HackathonDetail?
}

interface ProfileRepository {
    val profile: StateFlow<DemoProfile>
}

interface SavedRepository {
    val savedIds: StateFlow<Set<String>>
    fun savedHackathons(): List<Hackathon>
    fun setSaved(id: String, saved: Boolean)
    fun toggleSaved(id: String)
}

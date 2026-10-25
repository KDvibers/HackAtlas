package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*

fun Hackathon.savedKey() = SavedEventKey(id, isDemo)
fun SavedEventKey.listKey(): String = "$isDemo:${id.length}:$id"
fun GuestStoreScope.detailStateKey(key: SavedEventKey, local: Boolean): String =
    "${documentScope.length}:$documentScope:detail:$local:${key.listKey()}"

enum class QuickFilter { ALL, ONLINE, OFFLINE, OPEN, KAZAKHSTAN }

fun CatalogQuery.toggleQuickFilter(filter: QuickFilter): CatalogQuery = when (filter) {
    QuickFilter.ALL -> CatalogQuery(query = query, sort = sort, size = size)
    QuickFilter.ONLINE -> copy(format = if (format == EventFormat.ONLINE) null else EventFormat.ONLINE)
    QuickFilter.OFFLINE -> copy(format = if (format == EventFormat.OFFLINE) null else EventFormat.OFFLINE)
    QuickFilter.OPEN -> copy(registrationStatus = if (registrationStatus == RegistrationStatus.OPEN) null else RegistrationStatus.OPEN)
    QuickFilter.KAZAKHSTAN -> copy(countryCode = if (countryCode == "KZ") null else "KZ", city = null)
}.copy(page = 0)

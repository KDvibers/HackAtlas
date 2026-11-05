package dev.hackatlas.mobile.data.auth

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationMark(
    val hackathonId: String, val status: String, val selfReported: Boolean, val markedAt: String,
)

@Serializable
data class ApplicationPage(
    val items: List<ApplicationMark>, val page: Int, val size: Int, val totalElements: Int,
    val totalPages: Int, val hasNext: Boolean, val asOf: String,
)

internal fun ApplicationMark.validate(expectedId: String? = null) {
    require(USER_ID.matches(hackathonId) && status == "APPLIED" && selfReported)
    require(expectedId == null || hackathonId.equals(expectedId, ignoreCase = true))
    Instant.parse(markedAt)
}

internal fun ApplicationPage.validate(requestedPage: Int, requestedSize: Int) {
    require(page == requestedPage && size == requestedSize && totalElements >= 0 && totalPages >= 0)
    require(items.size <= size && items.size <= totalElements)
    require(totalPages.toLong() == (totalElements.toLong() + size - 1) / size)
    require(hasNext == (page.toLong() + 1 < totalPages))
    require(items.map { it.hackathonId.lowercase() }.distinct().size == items.size)
    items.forEach { it.validate() }
    Instant.parse(asOf)
}

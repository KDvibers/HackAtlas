package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.domain.CatalogError
import dev.hackatlas.mobile.domain.HackathonDetail

sealed interface DetailUiState {
    data object Idle : DetailUiState
    data class Loading(val id: String) : DetailUiState
    data class Content(val detail: HackathonDetail, val snapshot: SavedGuestEvent? = null) : DetailUiState
    data class Error(val id: String, val error: CatalogError) : DetailUiState
}

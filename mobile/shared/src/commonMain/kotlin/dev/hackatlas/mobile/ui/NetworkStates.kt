package dev.hackatlas.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hackatlas.mobile.design.AtlasColors
import dev.hackatlas.mobile.domain.CatalogError
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoadingPanel() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = AtlasColors.Primary, strokeWidth = 2.dp)
        Text(stringResource(Res.string.loading), color = AtlasColors.TextSecondary)
    }
}

@Composable
fun catalogErrorText(error: CatalogError): String = stringResource(when (error) {
    CatalogError.NotFound -> Res.string.event_not_found
    CatalogError.Network -> Res.string.network_error
    CatalogError.Timeout -> Res.string.network_timeout
    CatalogError.InvalidResponse -> Res.string.invalid_response
    is CatalogError.Http -> Res.string.server_error
})

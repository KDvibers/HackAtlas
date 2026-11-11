package dev.hackatlas.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.resources.Res
import dev.hackatlas.mobile.resources.close
import dev.hackatlas.mobile.resources.settings
import dev.hackatlas.mobile.resources.typography
import dev.hackatlas.mobile.ui.auth.AuthPrimaryAction
import dev.hackatlas.mobile.ui.auth.AuthSecondaryAction
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDialog(onOpenTypography: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 288.dp).verticalScroll(rememberScrollState()),
    ) {
        ProfilePanel {
            Text(
                stringResource(Res.string.settings),
                Modifier.fillMaxWidth().semantics { heading() },
                color = CatalogueColors.Text,
                style = MaterialTheme.typography.displayMedium.copy(lineHeight = TextUnit.Unspecified),
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 26.sp, stepSize = 1.sp),
                maxLines = 1,
            )
            AuthPrimaryAction(stringResource(Res.string.typography), onOpenTypography)
            AuthSecondaryAction(stringResource(Res.string.close), onDismiss)
        }
    }
}

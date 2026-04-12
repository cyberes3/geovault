package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.geovault.common.ui.model.GeoVaultStatusRenderModel
import com.geovault.common.ui.model.GeoVaultStatusVisualState
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.GeoVaultLayoutTokens

@Composable
fun GeoVaultStatusPane(
    model: GeoVaultStatusRenderModel,
    modifier: Modifier = Modifier,
    onPrimaryActionClick: (() -> Unit)? = null,
    onSecondaryActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(GeoVaultLayoutTokens.PanePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (model.visualState == GeoVaultStatusVisualState.Loading) {
            GeoVaultLoadingSpinner()
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusTitleGap))
        }

        val shouldShowSuccessIcon = model.visualState == GeoVaultStatusVisualState.Success
        if (shouldShowSuccessIcon) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Connected",
                tint = GeoVaultColorTokens.PrimaryBlue
            )
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusIconGap))
        }

        val title = model.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6
            )
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusTitleGap))
        }

        if (model.message.isNotBlank()) {
            Text(
                text = model.message,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val primary = model.primaryAction
        if (primary != null && onPrimaryActionClick != null) {
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.StatusActionGap))
            GeoVaultPrimaryButton(
                text = primary.label,
                enabled = primary.enabled,
                onClick = onPrimaryActionClick,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val secondary = model.secondaryAction
        if (secondary != null && onSecondaryActionClick != null) {
            Spacer(modifier = Modifier.height(GeoVaultLayoutTokens.FormActionGap))
            GeoVaultSecondaryButton(
                text = secondary.label,
                enabled = secondary.enabled,
                onClick = onSecondaryActionClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

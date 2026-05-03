package com.geovault.common.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import com.geovault.common.ui.theme.geoVaultTextFieldFillColor

/**
 * Form-row selector that opens a [GeoVaultSingleSelectDialog] when tapped.
 *
 * Visually this renders the same rounded primary-blue outlined trigger as other form fields,
 * with a floating label and a trailing caret. The dialog state is owned here so callers never
 * have to manage `showDialog` themselves — the common library is the single source of truth
 * for how a "pick one from a list" field looks and behaves.
 *
 * Implemented as a custom outlined container (not an [androidx.compose.material.OutlinedTextField])
 * so taps always reach our click handler; real text fields absorb focus which would make the
 * dialog-opening click feel flaky, especially on long option lists.
 */
@Composable
fun <T> GeoVaultSelectField(
    selectedValue: T,
    options: List<GeoVaultSelectOption<T>>,
    onValueSelected: (T) -> Unit,
    dialogTitle: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    searchable: Boolean = false,
    placeholder: String = "Select\u2026",
    emptyLabel: String = "No options",
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val onSelectedState = rememberUpdatedState(onValueSelected)

    SelectTrigger(
        label = label,
        valueText = selectedLabel,
        placeholder = placeholder,
        enabled = enabled,
        onClick = { if (enabled) showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        GeoVaultSingleSelectDialog(
            title = dialogTitle,
            options = options,
            selectedValue = selectedValue,
            onSelected = { onSelectedState.value(it) },
            onDismiss = { showDialog = false },
            searchable = searchable,
            emptyLabel = emptyLabel,
        )
    }
}

@Composable
private fun SelectTrigger(
    label: String?,
    valueText: String,
    placeholder: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (enabled) {
        GeoVaultColorTokens.MainBlue
    } else {
        GeoVaultColorTokens.MainBlue.copy(alpha = 0.5f)
    }
    val valueColor = when {
        !enabled -> geoVaultContentSecondaryColor()
        valueText.isEmpty() -> geoVaultContentSecondaryColor()
        else -> MaterialTheme.colors.onSurface
    }
    val triggerShape = RoundedCornerShape(8.dp)
    val fieldFill = geoVaultTextFieldFillColor()
    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.caption,
                color = borderColor,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(triggerShape)
                .background(fieldFill, triggerShape)
                .border(width = 1.dp, color = borderColor, shape = triggerShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = valueText.ifEmpty { placeholder },
                    style = MaterialTheme.typography.body1,
                    color = valueColor,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = borderColor,
            )
        }
    }
}

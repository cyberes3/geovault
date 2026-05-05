package com.geovault.common.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.common.ui.theme.geoVaultCardBorderColor
import com.geovault.common.ui.theme.geoVaultContentSecondaryColor
import kotlinx.coroutines.launch

enum class GeoVaultAddRemoveRowActionState {
    IDLE,
    ADDING,
    REMOVING,
    ADDED_DELETE,
    DISABLED,
}

@Composable
fun GeoVaultAddRemoveRowCard(
    name: String,
    subtitle: String?,
    state: GeoVaultAddRemoveRowActionState,
    borderColor: Color,
    enabled: Boolean = true,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    addIconTooltip: String? = null,
    removeIconTooltip: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (state == GeoVaultAddRemoveRowActionState.ADDING ||
                    state == GeoVaultAddRemoveRowActionState.REMOVING
                ) {
                    Modifier
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onAdd)
                }
            ),
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                leadingContent()
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.caption,
                        color = geoVaultContentSecondaryColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (state) {
                GeoVaultAddRemoveRowActionState.ADDING,
                GeoVaultAddRemoveRowActionState.REMOVING,
                -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GeoVaultLoadingSpinner(spinnerSize = 20.dp, strokeWidth = 2.dp)
                    }
                }
                GeoVaultAddRemoveRowActionState.ADDED_DELETE -> {
                    GeoVaultIconButton(onClick = onRemove, enabled = enabled, tooltip = removeIconTooltip) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.MainBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                GeoVaultAddRemoveRowActionState.IDLE -> {
                    GeoVaultIconButton(onClick = onAdd, enabled = enabled, tooltip = addIconTooltip) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.MainBlue,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                GeoVaultAddRemoveRowActionState.DISABLED -> {
                    GeoVaultIconButton(onClick = onAdd, enabled = true, tooltip = addIconTooltip) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = GeoVaultColorTokens.MainBlue,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun <T> GeoVaultAsyncAddRemoveCardList(
    items: List<T>,
    keyOf: (T) -> Any,
    nameFor: (T) -> String,
    isAdded: (T) -> Boolean,
    onAdd: suspend (T) -> Result<Unit>,
    onDelete: suspend (T) -> Result<Unit>,
    modifier: Modifier = Modifier,
    subtitleFor: (T) -> String? = { null },
    enabled: Boolean = true,
    borderColor: Color = defaultGeoVaultAddRemoveBorderColor(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    addIconTooltip: String? = null,
    removeIconTooltip: String? = null,
    leadingContent: (@Composable (T) -> Unit)? = null,
    onMutationFailed: (T, Throwable) -> Unit = { _, _ -> },
    onAddSucceeded: (T) -> Unit = {},
    onDeleteSucceeded: (T) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val pendingStates = remember { mutableStateMapOf<Any, GeoVaultAddRemoveRowActionState>() }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        items(items, key = { keyOf(it) }) { item ->
            val key = keyOf(item)
            val pendingState = pendingStates[key]
            val state = pendingState ?: GeoVaultAddRemoveRowStatePolicy.resolve(
                GeoVaultAddRemoveRowFlags(isAdded = isAdded(item)),
            )
            GeoVaultAddRemoveRowCard(
                name = nameFor(item),
                subtitle = subtitleFor(item),
                state = state,
                borderColor = borderColor,
                enabled = enabled && pendingState == null,
                onAdd = {
                    if (!enabled || pendingStates.containsKey(key) || isAdded(item)) return@GeoVaultAddRemoveRowCard
                    pendingStates[key] = GeoVaultAddRemoveRowActionState.ADDING
                    scope.launch {
                        val result = runCatching { onAdd(item) }.fold(
                            onSuccess = { it },
                            onFailure = { Result.failure(it) },
                        )
                        pendingStates.remove(key)
                        result
                            .onSuccess { onAddSucceeded(item) }
                            .onFailure { onMutationFailed(item, it) }
                    }
                },
                onRemove = {
                    if (!enabled || pendingStates.containsKey(key) || !isAdded(item)) return@GeoVaultAddRemoveRowCard
                    pendingStates[key] = GeoVaultAddRemoveRowActionState.REMOVING
                    scope.launch {
                        val result = runCatching { onDelete(item) }.fold(
                            onSuccess = { it },
                            onFailure = { Result.failure(it) },
                        )
                        pendingStates.remove(key)
                        result
                            .onSuccess { onDeleteSucceeded(item) }
                            .onFailure { onMutationFailed(item, it) }
                    }
                },
                addIconTooltip = addIconTooltip,
                removeIconTooltip = removeIconTooltip,
                leadingContent = leadingContent?.let { content -> { content(item) } },
            )
        }
    }
}

@Composable
private fun defaultGeoVaultAddRemoveBorderColor(): Color = geoVaultCardBorderColor()

package com.geovault.common.maps.ui.scaffold

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.components.GeoVaultIconButton

@Stable
data class GeoVaultMapDrawerTitleChip(
    val icon: ImageVector,
    val text: String,
)

/**
 * Typed receiver scope for [GeoVaultMapScaffold]'s `drawerHeader` slot.
 *
 * Giving the header a scope (rather than a plain `() -> Unit`) lets the scaffold expose the
 * canonical helpers ([TitleChip], [PlainTitle], [SearchAction], [SettingsAction]) without forcing a specific
 * layout on consumers — feature code remains free to arrange them inside any row/column
 * composition it needs.
 *
 * Implemented by the scaffold itself; feature code never needs to construct this directly.
 */
@Stable
interface GeoVaultMapDrawerHeaderScope : RowScope {

    /**
     * Mirrors [GeoVaultMapScaffold]'s [drawerDragEnabled]. When false, [SearchAction] and
     * [SettingsAction] use `enabled = false` unless the caller passes an explicit [enabled]
     * override (e.g. keep a close affordance active while the map loads).
     */
    val headerInteractionsEnabled: Boolean

    /**
     * Pill-shaped title chip with a leading icon, mirroring the old survey app's file
     * header — "[file-icon] filename" indicator that lives next to the drag handle.
     */
    @Composable
    fun TitleChip(
        @DrawableRes iconRes: Int,
        text: String,
        modifier: Modifier = Modifier,
    )

    /** Vector-icon overload for [TitleChip]. Accepts Material icons without needing a drawable. */
    @Composable
    fun TitleChip(
        icon: ImageVector,
        text: String,
        modifier: Modifier = Modifier,
    )

    /**
     * Plain title label used for the All-Data / non-chip header variant.
     * Uses the same typographic treatment as the old survey app's "All Points" header.
     */
    @Composable
    fun PlainTitle(
        text: String,
        modifier: Modifier = Modifier,
    )

    /** Leading close/X action for modal drawer contexts such as file-scoped map overlays. */
    @Composable
    fun CloseAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier = Modifier,
        icon: ImageVector = Icons.Filled.Close,
        tooltip: String? = null,
        /** When null, uses [headerInteractionsEnabled]. */
        enabled: Boolean? = null,
    )

    /**
     * Search action placed immediately left of [SettingsAction] in the typical layout:
     * `Spacer(Modifier.weight(1f)); SearchAction(...); SettingsAction(...)`.
     *
     * Renders as the canonical [GeoVaultIconButton] with long-press tooltip support.
     */
    @Composable
    fun SearchAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier = Modifier,
        icon: ImageVector = Icons.Filled.Search,
        tooltip: String? = null,
        /** When null, uses [headerInteractionsEnabled]. */
        enabled: Boolean? = null,
    )

    /**
     * Trailing settings/gear action. Renders as the canonical [GeoVaultIconButton] with
     * long-press tooltip support so every app's map-settings affordance looks identical.
     */
    @Composable
    fun SettingsAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier = Modifier,
        icon: ImageVector = Icons.Filled.Settings,
        tooltip: String? = null,
        /** When null, uses [headerInteractionsEnabled]. */
        enabled: Boolean? = null,
    )
}

/**
 * Default implementation used by [GeoVaultMapScaffold]. Extracted as a class that wraps the
 * live [RowScope] provided by the scaffold's header row so consumers of the scope can use
 * [RowScope.weight] on their title/action modifiers — that's the only way to push the
 * trailing settings button to the end of the row without forcing a specific layout on the
 * scaffold itself.
 */
internal class DefaultGeoVaultMapDrawerHeaderScope(
    rowScope: RowScope,
    override val headerInteractionsEnabled: Boolean,
) : GeoVaultMapDrawerHeaderScope, RowScope by rowScope {

    @Composable
    override fun TitleChip(
        iconRes: Int,
        text: String,
        modifier: Modifier,
    ) {
        TitleChipRow(text = text, modifier = modifier) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = GeoVaultMapScaffoldDefaults.TitleChipContentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    @Composable
    override fun TitleChip(
        icon: ImageVector,
        text: String,
        modifier: Modifier,
    ) {
        TitleChipRow(text = text, modifier = modifier) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = GeoVaultMapScaffoldDefaults.TitleChipContentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    @Composable
    private fun TitleChipRow(
        text: String,
        modifier: Modifier,
        icon: @Composable () -> Unit,
    ) {
        Row(
            modifier = modifier
                .background(
                    color = GeoVaultMapScaffoldDefaults.TitleChipBackgroundColor,
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Text(
                text = text,
                color = GeoVaultMapScaffoldDefaults.TitleChipContentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    override fun PlainTitle(
        text: String,
        modifier: Modifier,
    ) {
        Text(
            text = text,
            modifier = modifier,
            color = GeoVaultMapScaffoldDefaults.HeaderTitleColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun CloseAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier,
        icon: ImageVector,
        tooltip: String?,
        enabled: Boolean?,
    ) {
        HeaderIconAction(
            onClick = onClick,
            contentDescription = contentDescription,
            modifier = modifier,
            icon = icon,
            tooltip = tooltip,
            enabled = enabled,
        )
    }

    @Composable
    override fun SearchAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier,
        icon: ImageVector,
        tooltip: String?,
        enabled: Boolean?,
    ) {
        HeaderIconAction(
            onClick = onClick,
            contentDescription = contentDescription,
            modifier = modifier,
            icon = icon,
            tooltip = tooltip,
            enabled = enabled,
        )
    }

    @Composable
    override fun SettingsAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier,
        icon: ImageVector,
        tooltip: String?,
        enabled: Boolean?,
    ) {
        HeaderIconAction(
            onClick = onClick,
            contentDescription = contentDescription,
            modifier = modifier,
            icon = icon,
            tooltip = tooltip,
            enabled = enabled,
        )
    }

    @Composable
    private fun HeaderIconAction(
        onClick: () -> Unit,
        contentDescription: String,
        modifier: Modifier,
        icon: ImageVector,
        tooltip: String?,
        enabled: Boolean?,
    ) {
        GeoVaultIconButton(
            onClick = onClick,
            modifier = modifier.size(36.dp),
            enabled = enabled ?: headerInteractionsEnabled,
            tooltip = tooltip,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = GeoVaultMapScaffoldDefaults.HeaderActionColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Convenience helper for a "flex-grow" spacer between leading title and trailing action.
 * Inlined into the scaffold's header Row. Declared in this file to keep all header primitives
 * co-located.
 */
@Suppress("unused")
@Composable
internal fun GeoVaultMapDrawerHeaderSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.width(8.dp))
}

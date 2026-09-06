package com.geovault.common.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geovault.common.util.ClipboardCopyHelper
import com.geovault.common.ui.theme.GeoVaultColorTokens

/**
 * The GeoVault "outlined stroke card" primitive: a Material [Card] with rounded 8.dp corners,
 * 0.dp elevation, a 1.dp [GeoVaultColorTokens.MainBlue] stroke border, and the current
 * theme's surface color as background.
 *
 * This is the shared visual chrome for every detail-screen card across the GeoVault apps
 * (tracker params, survey point detail, coord-system detail, ...). Compose freely inside it:
 * the card owns the border/shape/background, the caller owns whatever labels, values, icons,
 * or buttons go inside. For the common "bold label + value" case, prefer
 * [GeoVaultOutlinedInfoCard] which already builds on top of this primitive.
 */
@Composable
fun GeoVaultOutlinedStrokeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = clickableModifier,
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        border = BorderStroke(1.dp, GeoVaultColorTokens.MainBlue),
        backgroundColor = MaterialTheme.colors.surface,
        content = content,
    )
}

/**
 * Behavior options for [GeoVaultOutlinedInfoCard]. Defaults give an inert display card; flip
 * the flags to opt in to the standard interactions.
 *
 * @property copyOnTap when true, the card becomes clickable and tapping copies the card's
 *   `value` to the system clipboard via [ClipboardCopyHelper] (using the card's `label` as
 *   the clip's display label) and shows a short "Copied" toast. Blank values and the em-dash
 *   placeholder ("—") are skipped, so the clipboard never receives a stub. Tapping a card
 *   whose value matches the clipboard's current contents is a silent no-op (no toast). When
 *   this is true, any explicit `onClick` passed to the card is ignored — copy-on-tap is the
 *   tap behavior.
 * @property labelMaxLines / [valueMaxLines] cap the label or value text to N lines with
 *   ellipsis overflow. Defaults to [Int.MAX_VALUE] (no cap). Used by the tracker's
 *   extended-params grid to keep tight two-column cells from growing unboundedly.
 */
data class GeoVaultOutlinedInfoCardOptions(
    val copyOnTap: Boolean = false,
    val labelMaxLines: Int = Int.MAX_VALUE,
    val valueMaxLines: Int = Int.MAX_VALUE,
)

/**
 * The shared "bold label, value beneath" detail-row card. Structure matches the
 * `item_param_card.xml` / `fragment_point_detail.xml` / `fragment_coord_system_detail.xml`
 * cards arm-for-arm: 12.dp inner padding, 12.sp bold [label], 14.sp [value] with 4.dp top
 * padding, tinted with the theme's `onSurface` color.
 *
 * Pass [options] to opt in to standard behaviors (copy-on-tap, line caps). For one-off tap
 * behaviors that aren't covered by [options], pass [onClick] instead — but
 * [GeoVaultOutlinedInfoCardOptions.copyOnTap] takes precedence if both are set.
 *
 * Built on top of [GeoVaultOutlinedStrokeCard] so a change to the card chrome only needs to
 * happen in one place.
 */
@Composable
fun GeoVaultOutlinedInfoCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    options: GeoVaultOutlinedInfoCardOptions = GeoVaultOutlinedInfoCardOptions(),
    onClick: (() -> Unit)? = null,
) {
    val resolvedOnClick = rememberCardOnClick(label, value, options, onClick)
    GeoVaultOutlinedStrokeCard(
        modifier = modifier.fillMaxWidth(),
        onClick = resolvedOnClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = options.labelMaxLines,
                overflow = if (options.labelMaxLines == Int.MAX_VALUE) {
                    TextOverflow.Clip
                } else {
                    TextOverflow.Ellipsis
                },
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colors.onSurface,
                fontSize = 14.sp,
                maxLines = options.valueMaxLines,
                overflow = if (options.valueMaxLines == Int.MAX_VALUE) {
                    TextOverflow.Clip
                } else {
                    TextOverflow.Ellipsis
                },
            )
        }
    }
}

/**
 * Resolves the effective tap handler for [GeoVaultOutlinedInfoCard]: copy-on-tap (built on
 * top of [ClipboardCopyHelper]) when the option is set, otherwise the caller's [onClick],
 * otherwise null (i.e. inert display card).
 */
@Composable
private fun rememberCardOnClick(
    label: String,
    value: String,
    options: GeoVaultOutlinedInfoCardOptions,
    onClick: (() -> Unit)?,
): (() -> Unit)? {
    if (!options.copyOnTap) return onClick
    val context = LocalContext.current
    val clipboard = remember(context) { ClipboardCopyHelper(context) }
    return remember(label, value, context, clipboard) {
        {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && trimmed != PLACEHOLDER_DASH) {
                if (clipboard.copyText(trimmed, label = label)) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private const val PLACEHOLDER_DASH = "\u2014"

package com.geovault.common.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Single source of truth for every brand color used by any GeoVault Android app.
 *
 * Layered structure (top-down):
 *  1. Brand scales (`Blue50..Blue900`, `Red50..Red900`, `Green50..Green900`,
 *     `Yellow50..Yellow900`, `Purple50..Purple900`, `Gray50..Gray900`) — mirror of
 *     `src/frontend/src/assets/css/colors.css`.
 *  2. Named neutrals: [White], [Black].
 *  3. Main aliases ([MainBlue] = [Blue500], …) — mirror of `src/frontend/src/assets/css/root.css`.
 *  4. Light semantic tokens ([Surface], [ListBackground], [TextPrimary], …).
 *  5. Cross-app semantic tokens ([ScrimMedium], [MapUnderlay], [MapLineworkHalo], …) —
 *     promoted from app-level ad-hoc constants so apps share one canonical palette.
 *  6. Dark mode: [Dark.Surface], [Dark.TextPrimary], … (alias-only).
 *  7. Feature colors: [Feature.Point], [Feature.LineString], … (geometry-type colors).
 *  8. Hex projection: [Hex.MainBlue], [Hex.Surface], … (`#RRGGBB` / `#AARRGGBB` strings)
 *     derived from the same [Color] instances via [toArgb], so a Compose token and its
 *     hex form cannot drift.
 *
 * Hex literals exist only in (1), (2), (7), and a small number of off-scale dark-mode
 * exceptions explicitly tagged below. Every other entry is an alias of (1)/(2)/(3) or a
 * `.copy(alpha = …)` of one. The XML resource layer (`values/colors.xml` +
 * `values-night/colors.xml`) is a deliberately minimal projection of this object,
 * mechanically locked to it by `GeoVaultColorTokensXmlSyncTest`.
 */
object GeoVaultColorTokens {

    // ── Brand scales (mirror of frontend `colors.css`) ───────────────────
    val Blue50 = Color(0xFFF3F6FAu.toInt())
    val Blue100 = Color(0xFFE4EAF5u.toInt())
    val Blue200 = Color(0xFFC4D2EDu.toInt())
    val Blue300 = Color(0xFF99B2E4u.toInt())
    val Blue400 = Color(0xFF6C93DEu.toInt())
    val Blue500 = Color(0xFF163D8Au.toInt())
    val Blue600 = Color(0xFF063288u.toInt())
    val Blue700 = Color(0xFF052055u.toInt())
    val Blue800 = Color(0xFF061D4Au.toInt())
    val Blue900 = Color(0xFF051638u.toInt())

    val Red50 = Color(0xFFFCF2F2u.toInt())
    val Red100 = Color(0xFFF9E1E1u.toInt())
    val Red200 = Color(0xFFF5BCBDu.toInt())
    val Red300 = Color(0xFFF28B8Du.toInt())
    val Red400 = Color(0xFFF45759u.toInt())
    val Red500 = Color(0xFFFF3E41u.toInt())
    val Red600 = Color(0xFFED0003u.toInt())
    val Red700 = Color(0xFF9E0002u.toInt())
    val Red800 = Color(0xFF760001u.toInt())
    val Red900 = Color(0xFF590001u.toInt())

    val Green50 = Color(0xFFF6FAF4u.toInt())
    val Green100 = Color(0xFFEBF4E6u.toInt())
    val Green200 = Color(0xFFD5E8C9u.toInt())
    val Green300 = Color(0xFFBAD8A5u.toInt())
    val Green400 = Color(0xFF9EC982u.toInt())
    val Green500 = Color(0xFF5B8A3Cu.toInt())
    val Green600 = Color(0xFF4D802Bu.toInt())
    val Green700 = Color(0xFF2B4619u.toInt())
    val Green800 = Color(0xFF253B16u.toInt())
    val Green900 = Color(0xFF1B2C10u.toInt())

    val Yellow50 = Color(0xFFFBF8F3u.toInt())
    val Yellow100 = Color(0xFFF7EFE2u.toInt())
    val Yellow200 = Color(0xFFF1DDBFu.toInt())
    val Yellow300 = Color(0xFFEDC791u.toInt())
    val Yellow400 = Color(0xFFEBB25Fu.toInt())
    val Yellow500 = Color(0xFFF4AC45u.toInt())
    val Yellow600 = Color(0xFFEA8A00u.toInt())
    val Yellow700 = Color(0xFF9C5C00u.toInt())
    val Yellow800 = Color(0xFF754500u.toInt())
    val Yellow900 = Color(0xFF583300u.toInt())

    val Purple50 = Color(0xFFFAF4F9u.toInt())
    val Purple100 = Color(0xFFF4E6F2u.toInt())
    val Purple200 = Color(0xFFE8C8E3u.toInt())
    val Purple300 = Color(0xFFDCA2D3u.toInt())
    val Purple400 = Color(0xFFD179C4u.toInt())
    val Purple500 = Color(0xFFCB48B7u.toInt())
    val Purple600 = Color(0xFFAE1F99u.toInt())
    val Purple700 = Color(0xFF721664u.toInt())
    val Purple800 = Color(0xFF54124Au.toInt())
    val Purple900 = Color(0xFF3F0D37u.toInt())

    val Gray50 = Color(0xFFF9FAFBu.toInt())
    val Gray100 = Color(0xFFF3F4F6u.toInt())
    val Gray200 = Color(0xFFE5E7EBu.toInt())
    val Gray300 = Color(0xFFD1D5DBu.toInt())
    val Gray400 = Color(0xFF9CA3AFu.toInt())
    val Gray500 = Color(0xFF6B7280u.toInt())
    val Gray600 = Color(0xFF4B5563u.toInt())
    val Gray700 = Color(0xFF374151u.toInt())
    val Gray800 = Color(0xFF1F2937u.toInt())
    val Gray900 = Color(0xFF111827u.toInt())

    // ── Named neutrals ───────────────────────────────────────────────────
    val White = Color(0xFFFFFFFFu.toInt())
    val Black = Color(0xFF000000u.toInt())

    // ── Main aliases (mirror of frontend `root.css`) ─────────────────────
    val MainBlue = Blue500
    val MainRed = Red500
    val MainGreen = Green500
    val MainYellow = Yellow500
    val MainPurple = Purple500
    val MainGray = Gray500

    // ── Light semantic tokens ────────────────────────────────────────────
    val Surface = White
    val ListBackground = Blue50
    val TextPrimary = Blue800
    val TextSecondary = Gray600
    val BorderLight = Blue200
    val Success = MainGreen
    val Error = MainRed
    val SnackbarSurface = Gray800
    val SnackbarMessage = Surface
    val SnackbarAction = MainYellow
    val ToggleTitle = Black
    val ToggleHelpText = Gray500
    val ToggleThumbOff = Gray200
    val ToggleTrackOff = Gray400

    // ── Cross-app semantic tokens (alias-only) ───────────────────────────
    /** 40% black scrim shared by dialogs, loading overlays, dimmed photos, etc. */
    val ScrimMedium = Black.copy(alpha = 0.40f)

    /** 63% black scrim for stronger dimming (full-screen modal backdrops). */
    val ScrimStrong = Black.copy(alpha = 0.63f)

    /** Tinted error surface for inline error chips / banners. */
    val ErrorSurfaceLight = Red50

    /** 16%-alpha dark gray hairline used for muted card borders. */
    val OutlineMuted = Gray700.copy(alpha = 0.16f)

    /** Map view underlay color shown beneath the basemap while tiles load. */
    val MapUnderlay = ListBackground

    /** Outer halo painted underneath KML lines for contrast against imagery basemaps. */
    val MapLineworkHalo = Surface

    /** Thin dark border painted between halo and fill on outlined map lines. */
    val MapLineworkBorder = Black

    /** Default fill color for unstyled map points. */
    val MapPointDefault = MainBlue

    /** Default text color for map labels (KML titles, tracker names, etc.). */
    val MapLabelText = Gray900

    /** Shared FAB-disabled background alpha used by `GeoVaultMapFabs`. */
    const val FabDisabledTint: Float = 0.55f

    // ── Dark-mode overrides (alias-only) ─────────────────────────────────
    object Dark {
        val Surface = Gray900
        val ListBackground = Blue900
        val TextPrimary = Gray200

        /** Off-scale tint with no exact tailwind equivalent. Kept as a literal. */
        val TextSecondary = Color(0xFFBFC8D6u.toInt())
        val BorderLight = Gray700
        val BlueLight = Gray800
        val Error = Red300
        val SnackbarSurface = Gray700
        val ToggleTitle = Gray200
        val ToggleHelpText = Gray400

        /** Off-scale tint with no exact tailwind equivalent. Kept as a literal. */
        val ToggleThumbOff = Color(0xFFBFC8D6u.toInt())
        val ToggleTrackOff = Gray500
    }

    // ── Feature colors (frontend `root.css` feature tokens) ──────────────
    object Feature {
        val Point = Color(0xFF93C5FDu.toInt())
        val LineString = Color(0xFF86EFACu.toInt())
        val Polygon = Color(0xFFFBBF24u.toInt())
        val Default = Gray300
    }

    // ── JVM-pure hex projection ──────────────────────────────────────────
    /**
     * String-typed mirror of the palette for MapLibre / KML parsers / any consumer that
     * cannot accept Compose [Color]. Built from the same [Color] instances above via
     * [toArgb], so the two surfaces cannot drift.
     */
    object Hex {
        val Blue50 = GeoVaultColorTokens.Blue50.toHexRgb()
        val Blue100 = GeoVaultColorTokens.Blue100.toHexRgb()
        val Blue200 = GeoVaultColorTokens.Blue200.toHexRgb()
        val Blue300 = GeoVaultColorTokens.Blue300.toHexRgb()
        val Blue400 = GeoVaultColorTokens.Blue400.toHexRgb()
        val Blue500 = GeoVaultColorTokens.Blue500.toHexRgb()
        val Blue600 = GeoVaultColorTokens.Blue600.toHexRgb()
        val Blue700 = GeoVaultColorTokens.Blue700.toHexRgb()
        val Blue800 = GeoVaultColorTokens.Blue800.toHexRgb()
        val Blue900 = GeoVaultColorTokens.Blue900.toHexRgb()

        val Red50 = GeoVaultColorTokens.Red50.toHexRgb()
        val Red500 = GeoVaultColorTokens.Red500.toHexRgb()

        val Green500 = GeoVaultColorTokens.Green500.toHexRgb()

        val Yellow500 = GeoVaultColorTokens.Yellow500.toHexRgb()

        val Gray500 = GeoVaultColorTokens.Gray500.toHexRgb()
        val Gray700 = GeoVaultColorTokens.Gray700.toHexRgb()
        val Gray900 = GeoVaultColorTokens.Gray900.toHexRgb()

        val White = GeoVaultColorTokens.White.toHexRgb()
        val Black = GeoVaultColorTokens.Black.toHexRgb()

        val MainBlue = GeoVaultColorTokens.MainBlue.toHexRgb()
        val MainRed = GeoVaultColorTokens.MainRed.toHexRgb()
        val MainGreen = GeoVaultColorTokens.MainGreen.toHexRgb()
        val MainYellow = GeoVaultColorTokens.MainYellow.toHexRgb()
        val MainPurple = GeoVaultColorTokens.MainPurple.toHexRgb()
        val MainGray = GeoVaultColorTokens.MainGray.toHexRgb()

        val Surface = GeoVaultColorTokens.Surface.toHexRgb()
        val ListBackground = GeoVaultColorTokens.ListBackground.toHexRgb()
        val TextPrimary = GeoVaultColorTokens.TextPrimary.toHexRgb()
        val TextSecondary = GeoVaultColorTokens.TextSecondary.toHexRgb()
        val BorderLight = GeoVaultColorTokens.BorderLight.toHexRgb()
        val Success = GeoVaultColorTokens.Success.toHexRgb()
        val Error = GeoVaultColorTokens.Error.toHexRgb()

        val ErrorSurfaceLight = GeoVaultColorTokens.ErrorSurfaceLight.toHexRgb()
        val MapUnderlay = GeoVaultColorTokens.MapUnderlay.toHexRgb()
        val MapLineworkHalo = GeoVaultColorTokens.MapLineworkHalo.toHexRgb()
        val MapLineworkBorder = GeoVaultColorTokens.MapLineworkBorder.toHexRgb()
        val MapPointDefault = GeoVaultColorTokens.MapPointDefault.toHexRgb()
        val MapLabelText = GeoVaultColorTokens.MapLabelText.toHexRgb()

        val ScrimMedium = GeoVaultColorTokens.ScrimMedium.toHexArgb()
        val ScrimStrong = GeoVaultColorTokens.ScrimStrong.toHexArgb()

        object Feature {
            val Point = GeoVaultColorTokens.Feature.Point.toHexRgb()
            val LineString = GeoVaultColorTokens.Feature.LineString.toHexRgb()
            val Polygon = GeoVaultColorTokens.Feature.Polygon.toHexRgb()
            val Default = GeoVaultColorTokens.Feature.Default.toHexRgb()
        }
    }
}

// ── Top-level Compose Color extensions (JVM-pure) ───────────────────────
/** `#RRGGBB` projection, alpha discarded. */
fun Color.toHexRgb(): String = "#%06X".format(0xFFFFFF and this.toArgb())

/** `#AARRGGBB` projection, including alpha. */
fun Color.toHexArgb(): String = "#%08X".format(this.toArgb().toLong() and 0xFFFFFFFFL)

# Map Setup Errors

GeoVault map clients show short setup messages with a stable error code. Use the
code in the message to find the matching admin fix below.

## `maplibre_not_configured`

The server did not advertise any MapLibre style source.

To fix it:

- Configure `maptiler.api_key` or the `MAPTILER_API_KEY` environment variable.
- Add MapTiler map IDs to `maptiler.maps` / `maptiler.hidden_maps` so `/api/tiles/sources/` advertises the required Android basemaps.
- Restart or reload the backend so `/api/tiles/sources/` returns the updated source list.

## `required_maplibre_basemaps_missing`

The Android common map library did not receive every basemap ID it expects.

Required IDs:

- `maptiler-streets`
- `maptiler-openstreetmap-dark`
- `maptiler-hybrid-v4`
- `maptiler-topo-v4`

To fix it:

- Add the corresponding MapTiler map IDs to `maptiler.maps` or `maptiler.hidden_maps`.
- Confirm `/api/tiles/sources/` includes each required source with a non-empty `client_config.style_url`.
- Restart or reload the backend after changing map configuration.

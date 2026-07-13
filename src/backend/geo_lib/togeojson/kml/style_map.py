"""
Port of the style-map-building half of togeojson's `lib/kml.ts`
(`getStyleId`/`buildStyleMap`), wrapped in a small class instead of a
closure-captured dict since it's genuinely stateful, document-scoped data.
"""

from __future__ import annotations

from geo_lib.togeojson.kml.extract_style import extract_style
from geo_lib.togeojson.shared import find_all, is_element, normalize_id, val_one


def _get_style_id(style) -> str:
    style_id = style.getAttribute("id")
    parent = style.parentNode
    if not style_id and is_element(parent) and parent.localName == "CascadingStyle":
        style_id = parent.getAttribute("kml:id") or parent.getAttribute("id")
    return normalize_id(style_id or "")


class StyleMap:
    """Maps normalized style ids (e.g. `"#redPin"`) to their resolved
    simplestyle property dicts, resolving `StyleMap`/`styleUrl` aliases at
    build time."""

    def __init__(self) -> None:
        self._styles: dict[str, dict] = {}

    @classmethod
    def build(cls, document) -> "StyleMap":
        style_map = cls()

        for style in find_all(document, "Style"):
            style_map._styles[_get_style_id(style)] = extract_style(style)

        for style_map_el in find_all(document, "StyleMap"):
            map_id = normalize_id(style_map_el.getAttribute("id") or "")

            def _resolve(style_url: str, _map_id: str = map_id) -> None:
                normalized = normalize_id(style_url)
                if normalized in style_map._styles:
                    style_map._styles[_map_id] = style_map._styles[normalized]

            val_one(style_map_el, "styleUrl", _resolve)

        return style_map

    def __contains__(self, key: str) -> bool:
        return key in self._styles

    def __getitem__(self, key: str) -> dict:
        return self._styles[key]

    def get(self, key: str, default: dict | None = None) -> dict | None:
        return self._styles.get(key, default)

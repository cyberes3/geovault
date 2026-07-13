"""Port of togeojson's `lib/gpx/extensions.ts`: extracting vendor extension
values (Garmin heart rate/cadence/power, etc) from a GPX point's
`<extensions>` element."""

from __future__ import annotations

import math

from geo_lib.togeojson.shared import is_element, js_parse_float, node_val

ExtendedValues = list[tuple[str, "str | float"]]

_HEART_ALIASES = {"heart", "gpxtpx:hr", "hr"}


def _abbreviate_name(name: str) -> str:
    return "heart" if name in _HEART_ALIASES else name


def _parse_numeric(val: str) -> "str | float":
    num = js_parse_float(val)
    return val if math.isnan(num) else num


def get_extensions(node) -> ExtendedValues:
    """
    Implemented iteratively (an explicit stack of sibling iterators, rather
    than recursing into nested `gpxtpx:TrackPointExtension` elements) so a
    pathologically deep extensions block degrades gracefully instead of
    raising `RecursionError` -- see `kml/geometry.py`'s `get_geometry()`
    docstring for the full rationale; the same class of issue applies here.
    Traversal order is unchanged from the recursive version.
    """
    values: ExtendedValues = []
    if node is None:
        return values

    stack = [iter(node.childNodes)]
    while stack:
        try:
            child = next(stack[-1])
        except StopIteration:
            stack.pop()
            continue

        if not is_element(child):
            continue
        name = _abbreviate_name(child.tagName)
        if name == "gpxtpx:TrackPointExtension":
            stack.append(iter(child.childNodes))
        else:
            values.append((name, _parse_numeric(node_val(child))))

    return values

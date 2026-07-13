"""Port of togeojson's `lib/gpx/properties.ts`: extracting common text
properties, vendor-namespaced properties, and `<link>`s from a GPX element."""

from __future__ import annotations

from geo_lib.togeojson.shared import find_all, find_all_ns, get_attribute_or_none, get_multi, node_val

NS = list[tuple[str, str]]


def extract_properties(ns: NS, node) -> dict:
    properties = get_multi(node, ["name", "cmt", "desc", "type", "time", "keywords"])

    for _prefix, url in ns:
        for child in find_all_ns(node, "*", url):
            # JS's String.replace(str, str) only replaces the first match.
            properties[child.tagName.replace(":", "_", 1)] = node_val(child).strip()

    links = find_all(node, "link")
    if links:
        properties["links"] = [
            {"href": get_attribute_or_none(link, "href"), **get_multi(link, ["text", "type"])}
            for link in links
        ]

    return properties

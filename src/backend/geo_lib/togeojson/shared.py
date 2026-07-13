"""
Core DOM and value helpers shared by the KML and GPX converters.

This is a port of togeojson's `lib/shared.ts`. The upstream library is built
against a browser-style DOM (backed by `@xmldom/xmldom` in Node), while this
port is built against Python's `xml.dom.minidom` (via `defusedxml.minidom`).
The two DOM implementations differ in a few load-bearing ways that this
module papers over so the rest of the port can be a faithful line-by-line
translation:

* `minidom` elements have no `.textContent` property at all (it's a DOM
  Level 2 Core feature CPython never implemented) -- `node_val()` below
  recreates it by walking child text/CDATA nodes.
* `minidom`'s `Element.getAttribute()` returns `""` for a missing attribute,
  where `xmldom`'s returns `null`. Almost every upstream call site already
  guards this with `attr || ""` (which collapses to the same result Python
  gives natively), so this only matters at the handful of sites that read an
  attribute with no fallback -- those use `get_attribute_or_none()` instead.
* JavaScript's `Number.parseFloat`/`Number()` have their own numeric-string
  parsing quirks (leading-substring parsing, NaN propagation, `Boolean("0")
  === true`, etc). `js_parse_float()` and `js_number()` reimplement those
  quirks so numeric parity holds even on malformed input.
* CPython's `xml.dom.minidom` implements `Element.getElementsByTagName()` /
  `getElementsByTagNameNS()` (and `Element.normalize()`) as *recursive*
  tree walks, one Python stack frame per level of element nesting --
  confirmed to raise `RecursionError` on a KML file only tens of KB in size
  with ~1000 levels of nested `<MultiGeometry>`, well under any existing
  file-size validation. `find_all()`/`find_all_ns()` below reimplement
  those two DOM methods as iterative traversals instead of delegating to
  them, and `node_val()` skips calling `normalize()` entirely (it exists to
  merge/drop adjacent-or-empty text nodes before reading `textContent`, but
  `node_val()`'s own text-concatenation loop already produces the same
  result whether or not the text nodes it walks were pre-merged, so the
  normalization step is redundant for this specific use and not worth its
  recursion risk).
"""

from __future__ import annotations

import math
import re
from typing import Callable, TypeVar
from xml.dom import Node
from xml.dom.minidom import Element

T = TypeVar("T")

_LENIENT_FLOAT_RE = re.compile(
    r"^[ \t\n\r\f\v\u00a0\ufeff]*([+-]?(?:Infinity|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?))"
)


def js_parse_float(value: str | None) -> float:
    """Emulate JavaScript's `Number.parseFloat`: parse a leading numeric
    substring and ignore trailing garbage, returning NaN if there is no
    valid leading number."""
    if value is None:
        return math.nan
    match = _LENIENT_FLOAT_RE.match(value)
    if not match:
        return math.nan
    token = match.group(1)
    if "Infinity" in token:
        return -math.inf if token.startswith("-") else math.inf
    try:
        return float(token)
    except ValueError:
        return math.nan


def js_number(value: str) -> float:
    """Emulate JavaScript's `Number(str)`: the entire (trimmed) string must
    be a valid number, an empty/whitespace-only string is 0, and anything
    else that doesn't parse is NaN."""
    trimmed = value.strip()
    if trimmed == "":
        return 0.0
    try:
        return float(trimmed)
    except ValueError:
        return math.nan


def find_all(element, tag_name: str) -> list[Element]:
    """
    Port of `$()`: all descendant elements with the given tag name
    (`"*"` matches any element), in document order.

    Deliberately reimplemented as an iterative DFS (an explicit stack of
    sibling iterators) rather than delegating to minidom's
    `Element.getElementsByTagName()`, whose CPython implementation
    recurses one stack frame per level of element nesting -- see this
    module's docstring. Traversal order matches
    `getElementsByTagName()` exactly (pre-order: visit node, then fully
    descend into it before moving to its next sibling).
    """
    matches: list[Element] = []
    stack = [iter(element.childNodes)]
    while stack:
        try:
            node = next(stack[-1])
        except StopIteration:
            stack.pop()
            continue
        if node.nodeType == Node.ELEMENT_NODE:
            if tag_name == "*" or node.tagName == tag_name:
                matches.append(node)
            stack.append(iter(node.childNodes))
    return matches


def find_all_ns(element, tag_name: str, ns: str) -> list[Element]:
    """
    Port of `$ns()`: all descendant elements with the given tag name and
    namespace URI (either may be `"*"` as a wildcard), in document order.

    See `find_all()` immediately above for why this is an iterative DFS
    instead of a call to `Element.getElementsByTagNameNS()`.
    """
    matches: list[Element] = []
    stack = [iter(element.childNodes)]
    while stack:
        try:
            node = next(stack[-1])
        except StopIteration:
            stack.pop()
            continue
        if node.nodeType == Node.ELEMENT_NODE:
            if (tag_name == "*" or node.localName == tag_name) and (ns == "*" or node.namespaceURI == ns):
                matches.append(node)
            stack.append(iter(node.childNodes))
    return matches


def is_element(node) -> bool:
    """Port of `isElement()`: a type guard for element nodes."""
    return node is not None and node.nodeType == Node.ELEMENT_NODE


def normalize_id(id_: str) -> str:
    """Port of `normalizeId()`: ensure an id string is `#`-prefixed."""
    return id_ if id_[:1] == "#" else f"#{id_}"


def get_attribute_or_none(node, name: str) -> str | None:
    """Read an attribute, distinguishing "missing" (`None`, matching
    xmldom's `null`) from "present but empty" (`""`). Plain
    `Element.getAttribute()` collapses both cases to `""` in minidom, which
    is fine everywhere the upstream code itself falls back with `|| ""`, but
    not at the few sites that pass the raw value straight into JSON output.
    """
    return node.getAttribute(name) if node.hasAttribute(name) else None


def _text_content(node) -> str:
    """
    Recreate the DOM Level 2 `textContent` getter, which minidom lacks.

    Implemented iteratively (an explicit stack of sibling iterators, rather
    than recursing into element descendants) so a pathologically deep
    element subtree degrades gracefully instead of raising `RecursionError`
    -- this is called on every text-bearing property lookup (`name`,
    `description`, ...), so it's exercised even more often than the
    geometry/extension recursion this same fix was applied to elsewhere in
    this package; see `kml/geometry.py`'s `get_geometry()` docstring for the
    full rationale. Traversal order (and thus the concatenated string) is
    unchanged from the recursive version.
    """
    if node.nodeType in (Node.TEXT_NODE, Node.CDATA_SECTION_NODE):
        return node.data
    if node.nodeType != Node.ELEMENT_NODE:
        return ""

    parts: list[str] = []
    stack = [iter(node.childNodes)]
    while stack:
        try:
            child = next(stack[-1])
        except StopIteration:
            stack.pop()
            continue

        if child.nodeType in (Node.TEXT_NODE, Node.CDATA_SECTION_NODE):
            parts.append(child.data)
        elif child.nodeType == Node.ELEMENT_NODE:
            stack.append(iter(child.childNodes))

    return "".join(parts)


def node_val(node) -> str:
    """
    Port of `nodeVal()`: the text content of a node, if any.

    Deliberately does not call `node.normalize()` first (unlike a literal
    line-for-line port would suggest) -- see this module's docstring for
    why that call is both redundant here and a `RecursionError` risk on
    deeply nested documents.
    """
    if node is None:
        return ""
    return _text_content(node) or ""


def get_one(node, tag_name: str, callback: Callable[[Element], None] | None = None) -> Element | None:
    """
    Port of `get1()`: the first descendant element with the given tag
    name, if any.

    Uses `find_all()` rather than `node.getElementsByTagName()` directly --
    see this module's docstring for why.
    """
    matches = find_all(node, tag_name)
    result = matches[0] if matches else None
    if result is not None and callback:
        callback(result)
    return result


def get(node, tag_name: str, callback: Callable[[Element, dict], dict] | None = None) -> dict:
    """
    Port of `get()`: run `callback(first_matching_child, properties)` and
    return its result, or `{}` if there's no match (or no callback).

    Uses `find_all()` rather than `node.getElementsByTagName()` directly --
    see this module's docstring for why.
    """
    properties: dict = {}
    if node is None:
        return properties
    matches = find_all(node, tag_name)
    result = matches[0] if matches else None
    if result is not None and callback:
        return callback(result, properties)
    return properties


def val_one(node, tag_name: str, callback: Callable[[str], dict | None] | None) -> dict:
    """Port of `val1()`: run `callback(text)` for the first matching child's
    text content, if it's non-empty, and return its result (or `{}`)."""
    val = node_val(get_one(node, tag_name))
    if val and callback:
        return callback(val) or {}
    return {}


def num_prop(node, tag_name: str, callback: Callable[[float], dict | None]) -> dict:
    """Port of `$num()`. Note the upstream quirk this faithfully preserves:
    the callback is skipped (and `{}` returned) not just when the parsed
    value is NaN, but also when it's exactly `0`, since JS treats `0` as
    falsy in the `val && callback` check."""
    val = js_parse_float(node_val(get_one(node, tag_name)))
    if math.isnan(val):
        return {}
    if val and callback:
        return callback(val) or {}
    return {}


def num_one(node, tag_name: str, callback: Callable[[float], None] | None = None) -> float | None:
    """Port of `num1()`: the first matching child's text, parsed as a
    lenient float, or `None` if there is no valid number."""
    val = js_parse_float(node_val(get_one(node, tag_name)))
    if math.isnan(val):
        return None
    if callback:
        callback(val)
    return val


def get_multi(node, property_names: list[str]) -> dict:
    """Port of `getMulti()`: read several same-named text properties at
    once, omitting any that are missing or empty."""
    properties: dict = {}

    def _set(name: str) -> Callable[[str], None]:
        def _callback(val: str) -> None:
            properties[name] = val

        return _callback

    for name in property_names:
        val_one(node, name, _set(name))
    return properties

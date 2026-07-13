"""
Unit tests for geo_lib.togeojson.shared: the core DOM/value helpers shared
by the KML and GPX converters. Uses small inline XML snippets rather than
corpus fixtures, since these are meant to pin down exact JS-parity edge
cases (NaN handling, textContent recreation, etc) independent of any real
KML/GPX document.
"""

import math

import defusedxml.minidom as minidom
import pytest

from geo_lib.togeojson.shared import (
    find_all,
    find_all_ns,
    get,
    get_attribute_or_none,
    get_multi,
    get_one,
    is_element,
    js_number,
    js_parse_float,
    node_val,
    normalize_id,
    num_one,
    num_prop,
    val_one,
)


def parse(xml: str):
    return minidom.parseString(xml)


class TestJsParseFloat:
    @pytest.mark.parametrize(
        "value,expected",
        [
            ("1.5", 1.5),
            ("-3", -3.0),
            ("  12.5  ", 12.5),
            ("12.5abc", 12.5),
            ("1e3", 1000.0),
            ("-1.5e-2", -0.015),
            (".5", 0.5),
            ("Infinity", math.inf),
            ("-Infinity", -math.inf),
        ],
    )
    def test_valid_leading_number(self, value, expected):
        assert js_parse_float(value) == expected

    @pytest.mark.parametrize("value", ["", "abc", "   ", None])
    def test_returns_nan(self, value):
        assert math.isnan(js_parse_float(value))


class TestJsNumber:
    @pytest.mark.parametrize(
        "value,expected",
        [
            ("1.5", 1.5),
            ("-3", -3.0),
            ("  12.5  ", 12.5),
            ("", 0.0),
            ("   ", 0.0),
        ],
    )
    def test_valid(self, value, expected):
        assert js_number(value) == expected

    @pytest.mark.parametrize("value", ["12.5abc", "abc", "1.2.3"])
    def test_returns_nan(self, value):
        assert math.isnan(js_number(value))


class TestDomHelpers:
    def test_find_all(self):
        doc = parse("<root><a/><b/><a/></root>")
        assert len(find_all(doc, "a")) == 2

    def test_find_all_ns_wildcards(self):
        doc = parse(
            '<root xmlns:x="http://x" xmlns:y="http://y">'
            "<x:foo/><y:foo/><x:bar/>"
            "</root>"
        )
        assert len(find_all_ns(doc, "*", "http://x")) == 2
        assert len(find_all_ns(doc, "foo", "*")) == 2
        assert len(find_all_ns(doc, "foo", "http://x")) == 1

    def test_is_element(self):
        doc = parse("<root>text</root>")
        root = doc.documentElement
        assert is_element(root) is True
        assert is_element(root.firstChild) is False
        assert is_element(None) is False

    @pytest.mark.parametrize(
        "value,expected",
        [("foo", "#foo"), ("#foo", "#foo"), ("", "#")],
    )
    def test_normalize_id(self, value, expected):
        assert normalize_id(value) == expected

    def test_get_attribute_or_none_distinguishes_missing_from_empty(self):
        doc = parse('<root a="" />')
        root = doc.documentElement
        assert get_attribute_or_none(root, "a") == ""
        assert get_attribute_or_none(root, "missing") is None


class TestNodeVal:
    def test_plain_text(self):
        doc = parse("<root>hello</root>")
        assert node_val(doc.documentElement) == "hello"

    def test_cdata(self):
        doc = parse("<root><![CDATA[<b>hi</b>]]></root>")
        assert node_val(doc.documentElement) == "<b>hi</b>"

    def test_mixed_text_and_cdata(self):
        doc = parse("<root>before<![CDATA[MID]]>after</root>")
        assert node_val(doc.documentElement) == "beforeMIDafter"

    def test_recurses_into_nested_elements_like_real_textcontent(self):
        doc = parse("<root>a<child>b</child>c</root>")
        assert node_val(doc.documentElement) == "abc"

    def test_ignores_comments(self):
        doc = parse("<root>a<!-- comment -->b</root>")
        assert node_val(doc.documentElement) == "ab"

    def test_empty_element_is_empty_string(self):
        doc = parse("<root></root>")
        assert node_val(doc.documentElement) == ""

    def test_none_node_is_empty_string(self):
        assert node_val(None) == ""

    def test_leaf_cdata_node_directly(self):
        doc = parse("<root><![CDATA[raw]]></root>")
        cdata_node = doc.documentElement.firstChild
        assert node_val(cdata_node) == "raw"


class TestGetOneAndGet:
    def test_get_one_returns_first_match(self):
        doc = parse("<root><a>1</a><a>2</a></root>")
        first = get_one(doc.documentElement, "a")
        assert node_val(first) == "1"

    def test_get_one_returns_none_when_missing(self):
        doc = parse("<root></root>")
        assert get_one(doc.documentElement, "missing") is None

    def test_get_one_invokes_callback_only_when_found(self):
        doc = parse("<root><a>1</a></root>")
        calls = []
        get_one(doc.documentElement, "a", lambda elem: calls.append(node_val(elem)))
        get_one(doc.documentElement, "missing", lambda elem: calls.append("should not happen"))
        assert calls == ["1"]

    def test_get_returns_empty_dict_for_none_node(self):
        assert get(None, "a") == {}

    def test_get_returns_empty_dict_when_no_match_or_no_callback(self):
        doc = parse("<root><a>1</a></root>")
        assert get(doc.documentElement, "missing", lambda elem, props: {"x": 1}) == {}
        assert get(doc.documentElement, "a") == {}

    def test_get_invokes_callback_with_match_and_properties(self):
        doc = parse("<root><a>1</a></root>")
        result = get(doc.documentElement, "a", lambda elem, props: {**props, "val": node_val(elem)})
        assert result == {"val": "1"}


class TestValOne:
    def test_calls_callback_with_nonempty_text(self):
        doc = parse("<root><a>hello</a></root>")
        result = val_one(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {"got": "hello"}

    def test_skips_callback_when_text_is_empty(self):
        doc = parse("<root><a></a></root>")
        result = val_one(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {}

    def test_skips_callback_when_missing(self):
        doc = parse("<root></root>")
        result = val_one(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {}

    def test_falls_back_to_empty_dict_when_callback_returns_falsy(self):
        doc = parse("<root><a>hello</a></root>")
        result = val_one(doc.documentElement, "a", lambda val: None)
        assert result == {}


class TestNumProp:
    """Covers the upstream `$num()` quirk: the callback (and any result) is
    skipped not just for NaN, but also for an exact `0` value, since JS
    treats `0` as falsy in `val && callback`."""

    def test_calls_callback_for_nonzero_number(self):
        doc = parse("<root><a>5</a></root>")
        result = num_prop(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {"got": 5.0}

    def test_skips_callback_for_zero(self):
        doc = parse("<root><a>0</a></root>")
        result = num_prop(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {}

    def test_skips_callback_for_non_numeric_text(self):
        doc = parse("<root><a>not a number</a></root>")
        result = num_prop(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {}

    def test_skips_callback_when_missing(self):
        doc = parse("<root></root>")
        result = num_prop(doc.documentElement, "a", lambda val: {"got": val})
        assert result == {}


class TestNumOne:
    def test_returns_parsed_value_for_zero(self):
        """Unlike num_prop/$num, num1 has no falsy-zero quirk."""
        doc = parse("<root><a>0</a></root>")
        assert num_one(doc.documentElement, "a") == 0.0

    def test_returns_parsed_value(self):
        doc = parse("<root><a>3.5</a></root>")
        assert num_one(doc.documentElement, "a") == 3.5

    def test_returns_none_for_non_numeric(self):
        doc = parse("<root><a>not a number</a></root>")
        assert num_one(doc.documentElement, "a") is None

    def test_returns_none_when_missing(self):
        doc = parse("<root></root>")
        assert num_one(doc.documentElement, "a") is None

    def test_invokes_callback_for_zero(self):
        doc = parse("<root><a>0</a></root>")
        calls = []
        num_one(doc.documentElement, "a", calls.append)
        assert calls == [0.0]


class TestGetMulti:
    def test_reads_multiple_present_properties(self):
        doc = parse("<root><name>Test</name><desc>hi</desc></root>")
        result = get_multi(doc.documentElement, ["name", "desc", "missing"])
        assert result == {"name": "Test", "desc": "hi"}

    def test_omits_empty_and_missing_properties(self):
        doc = parse("<root><name></name></root>")
        result = get_multi(doc.documentElement, ["name", "missing"])
        assert result == {}

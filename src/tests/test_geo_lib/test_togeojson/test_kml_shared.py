"""
Unit tests for geo_lib.togeojson.kml.shared, focused on the deliberate
behavioral fix over upstream togeojson: numeric ExtendedData/SimpleData
type converters (`int`/`uint`/`short`/`ushort`/`float`/`double`) fall back to
the original string when the value fails to parse as a number, instead of
silently emitting `None` (upstream emits `NaN`, which `JSON.stringify` turns
into `null`).

No file in the geovault-tests corpus happens to exercise this path (every
numeric-typed SimpleData value in the corpus is a clean number), so this is
covered here with inline XML rather than the golden-master corpus tests.
"""

import defusedxml.minidom as minidom

from geo_lib.togeojson.kml.converter import build_schema
from geo_lib.togeojson.kml.shared import extract_extended_data, type_converters


def parse(xml: str):
    return minidom.parseString(xml)


SCHEMA_XML = """
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
  <Schema name="Test" id="TestId">
    <SimpleField type="int" name="clean_int" />
    <SimpleField type="double" name="clean_double" />
    <SimpleField type="int" name="dirty_int" />
    <SimpleField type="bool" name="flag" />
  </Schema>
</Document>
</kml>
"""


class TestTypeConverters:
    def test_string_passthrough(self):
        assert type_converters["string"]("hello") == "hello"

    def test_numeric_converter_parses_clean_number(self):
        assert type_converters["int"]("42") == 42.0
        assert type_converters["double"]("3.14") == 3.14

    def test_numeric_converter_falls_back_to_original_string_on_parse_failure(self):
        # This is the fix: upstream's `Number("N/A")` is NaN, which
        # JSON.stringify turns into `null`, silently discarding the value.
        assert type_converters["int"]("N/A") == "N/A"
        assert type_converters["float"]("12.5abc") == "12.5abc"

    def test_numeric_converter_treats_empty_string_as_zero_not_a_failure(self):
        # Matches JS `Number("")` === 0 (not NaN), so this is not a case
        # the string-preserving fallback should ever trigger for.
        assert type_converters["float"]("") == 0.0

    def test_bool_converter_matches_js_truthiness_for_strings(self):
        # JS Boolean("0") and Boolean("false") are both true -- any
        # non-empty string is truthy in JS, and Python's bool(str) has the
        # same non-empty-string-is-truthy behavior.
        assert type_converters["bool"]("0") is True
        assert type_converters["bool"]("false") is True
        assert type_converters["bool"]("") is False


class TestExtractExtendedDataWithSchema:
    def test_clean_numeric_values_are_converted(self):
        doc = parse(SCHEMA_XML)
        schema = build_schema(doc)
        node = parse(
            """
            <Placemark xmlns="http://www.opengis.net/kml/2.2">
              <ExtendedData>
                <SchemaData schemaUrl="#TestId">
                  <SimpleData name="clean_int">42</SimpleData>
                  <SimpleData name="clean_double">3.14</SimpleData>
                </SchemaData>
              </ExtendedData>
            </Placemark>
            """
        ).documentElement
        result = extract_extended_data(node, schema)
        assert result == {"clean_int": 42.0, "clean_double": 3.14}

    def test_dirty_numeric_value_falls_back_to_string(self):
        doc = parse(SCHEMA_XML)
        schema = build_schema(doc)
        node = parse(
            """
            <Placemark xmlns="http://www.opengis.net/kml/2.2">
              <ExtendedData>
                <SchemaData schemaUrl="#TestId">
                  <SimpleData name="dirty_int">not-a-number</SimpleData>
                </SchemaData>
              </ExtendedData>
            </Placemark>
            """
        ).documentElement
        result = extract_extended_data(node, schema)
        assert result == {"dirty_int": "not-a-number"}

    def test_unschematized_field_defaults_to_string_converter(self):
        doc = parse(SCHEMA_XML)
        schema = build_schema(doc)
        node = parse(
            """
            <Placemark xmlns="http://www.opengis.net/kml/2.2">
              <ExtendedData>
                <SchemaData schemaUrl="#TestId">
                  <SimpleData name="untyped_field">42</SimpleData>
                </SchemaData>
              </ExtendedData>
            </Placemark>
            """
        ).documentElement
        result = extract_extended_data(node, schema)
        # No SimpleField declares "untyped_field", so it falls back to the
        # string converter and stays "42" (a string), not 42.0.
        assert result == {"untyped_field": "42"}

    def test_plain_data_elements_are_always_strings(self):
        node = parse(
            """
            <Placemark xmlns="http://www.opengis.net/kml/2.2">
              <ExtendedData>
                <Data name="foo"><value>bar</value></Data>
              </ExtendedData>
            </Placemark>
            """
        ).documentElement
        result = extract_extended_data(node, {})
        assert result == {"foo": "bar"}

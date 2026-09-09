from geo_lib.types.feature_properties import ICON_READ_ALIASES, ingest_icon_properties


class TestIconReadAliasesIngest:
    def test_writes_only_icon(self):
        properties = {
            'icon-href': 'assets/icons/flag.png',
            'iconUrl': 'assets/icons/other.png',
            'name': 'Point',
        }
        result = ingest_icon_properties(properties)
        assert result['icon'] == 'assets/icons/flag.png'
        assert result['name'] == 'Point'
        for alias in ICON_READ_ALIASES:
            if alias == 'icon':
                continue
            assert alias not in result

    def test_prefers_canonical_icon(self):
        properties = {
            'icon': 'assets/icons/canonical.png',
            'icon_url': 'assets/icons/alias.png',
        }
        result = ingest_icon_properties(properties)
        assert result['icon'] == 'assets/icons/canonical.png'
        assert 'icon_url' not in result

    def test_empty_aliases_remove_icon(self):
        properties = {'icon': '', 'icon-href': '   '}
        result = ingest_icon_properties(properties)
        assert 'icon' not in result

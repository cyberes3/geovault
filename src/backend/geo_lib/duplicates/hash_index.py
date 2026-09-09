from geo_lib.duplicates.identity import FeatureRef, GeoJsonHash


class HashIndex:
    def __init__(self, mapping: dict[str, FeatureRef] | None = None):
        self._by_hash: dict[str, FeatureRef] = dict(mapping or {})

    def add(self, geojson_hash: GeoJsonHash | str, ref: FeatureRef) -> None:
        key = geojson_hash.value if isinstance(geojson_hash, GeoJsonHash) else geojson_hash
        if key and key not in self._by_hash:
            self._by_hash[key] = ref

    def lookup(self, geojson_hash: GeoJsonHash | str) -> FeatureRef | None:
        key = geojson_hash.value if isinstance(geojson_hash, GeoJsonHash) else geojson_hash
        return self._by_hash.get(key)

    def lookup_many(self, hashes: list[str]) -> dict[str, FeatureRef]:
        found: dict[str, FeatureRef] = {}
        for value in hashes:
            ref = self._by_hash.get(value)
            if ref is not None:
                found[value] = ref
        return found

    def __contains__(self, geojson_hash: GeoJsonHash | str) -> bool:
        key = geojson_hash.value if isinstance(geojson_hash, GeoJsonHash) else geojson_hash
        return key in self._by_hash

    def __len__(self) -> int:
        return len(self._by_hash)

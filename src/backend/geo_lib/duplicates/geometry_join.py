import json
from typing import Any, Callable, Iterable

from django.contrib.gis.geos import GEOSGeometry
from django.db import connection, transaction

from geo_lib.duplicates.constants import COORDINATE_TOLERANCE
from geo_lib.duplicates.fingerprint import GeometryFingerprint
from geo_lib.duplicates.identity import FeatureRef
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger('GeometryJoin')

_JOIN_CHUNK = 500


class GeometryJoin:
    def __init__(
        self,
        refs_by_input_id: dict[int, list[FeatureRef]],
        fingerprints_by_ref: dict[tuple, GeometryFingerprint],
    ):
        self._refs_by_input_id = refs_by_input_id
        self._fingerprints_by_ref = fingerprints_by_ref

    def matches_for(self, input_id: int, fingerprint: GeometryFingerprint | None) -> list[FeatureRef]:
        if fingerprint is None:
            return []
        confirmed: list[FeatureRef] = []
        for ref in self._refs_by_input_id.get(input_id, []):
            key = _ref_key(ref)
            other = self._fingerprints_by_ref.get(key)
            if other is not None and fingerprint.matches(other):
                confirmed.append(ref)
        return confirmed

    def replace_refs(self, refs_by_input_id: dict[int, list[FeatureRef]]) -> None:
        self._refs_by_input_id = refs_by_input_id

    def candidate_store_ids(self) -> set[int]:
        return {
            ref.feature_store_id
            for refs in self._refs_by_input_id.values()
            for ref in refs
            if ref.feature_store_id is not None
        }

    def refs_by_input_id(self) -> dict[int, list[FeatureRef]]:
        return self._refs_by_input_id


def _ref_key(ref: FeatureRef) -> tuple:
    return (ref.feature_store_id, ref.draft_queue_id, ref.spatial_index)


def geos_from_geometry(geometry: dict[str, Any] | None) -> GEOSGeometry | None:
    if not geometry or not geometry.get('type'):
        return None
    if geometry.get('type') == 'GeometryCollection':
        return None
    if not geometry.get('coordinates'):
        return None
    try:
        return GEOSGeometry(json.dumps(geometry))
    except Exception:
        _logger.debug('GEOS parse failed for geometry type %s', geometry.get('type'))
        return None


def build_join_from_rows(
    candidate_rows: Iterable[tuple[int, FeatureRef, dict[str, Any]]],
) -> GeometryJoin:
    refs_by_input_id: dict[int, list[FeatureRef]] = {}
    fingerprints_by_ref: dict[tuple, GeometryFingerprint] = {}
    for input_id, ref, geometry in candidate_rows:
        fingerprint = GeometryFingerprint.of({'geometry': geometry})
        if fingerprint is None:
            continue
        refs_by_input_id.setdefault(input_id, []).append(ref)
        fingerprints_by_ref[_ref_key(ref)] = fingerprint
    return GeometryJoin(refs_by_input_id, fingerprints_by_ref)


def gist_join_feature_store(
    inputs: list[tuple[int, GEOSGeometry]],
    user_id: int,
) -> GeometryJoin:
    if not inputs:
        return GeometryJoin({}, {})
    rows: list[tuple[int, FeatureRef, dict[str, Any]]] = []
    for chunk in _chunks(inputs, _JOIN_CHUNK):
        rows.extend(_run_store_join(chunk, user_id))
    return build_join_from_rows(rows)


def gist_join_import_drafts(
    inputs: list[tuple[int, GEOSGeometry]],
    user_id: int,
    exclude_queue_id: int,
    older_than: tuple,
) -> GeometryJoin:
    if not inputs:
        return GeometryJoin({}, {})
    rows: list[tuple[int, FeatureRef, dict[str, Any]]] = []
    for chunk in _chunks(inputs, _JOIN_CHUNK):
        rows.extend(_run_draft_join(chunk, user_id, exclude_queue_id, older_than))
    return build_join_from_rows(rows)


def _chunks(items: list, size: int):
    for start in range(0, len(items), size):
        yield items[start:start + size]


def _run_store_join(
    inputs: list[tuple[int, GEOSGeometry]],
    user_id: int,
) -> list[tuple[int, FeatureRef, dict[str, Any]]]:
    return _run_temp_join(
        inputs,
        """
        SELECT i.input_id, f.id, f.timestamp, ST_AsGeoJSON(f.geometry)
        FROM import_geom_input i
        INNER JOIN api_featurestore f
            ON f.geometry IS NOT NULL
            AND f.user_id = %s
            AND f.scope IS NULL
            AND ST_DWithin(f.geometry, i.geom, %s)
        """,
        [user_id, COORDINATE_TOLERANCE],
        lambda row: FeatureRef(
            feature_store_id=row[1],
            name='',
            geometry_type='',
        ),
    )


def _run_draft_join(
    inputs: list[tuple[int, GEOSGeometry]],
    user_id: int,
    exclude_queue_id: int,
    older_than: tuple,
) -> list[tuple[int, FeatureRef, dict[str, Any]]]:
    uploaded_at, queue_id = older_than
    return _run_temp_join(
        inputs,
        """
        SELECT i.input_id, d.id, d.queue_id, d.spatial_index, d.name, d.geometry_type,
               ST_AsGeoJSON(d.geometry)
        FROM import_geom_input i
        INNER JOIN api_importdraftfeature d
            ON d.geometry IS NOT NULL
            AND d.user_id = %s
            AND d.queue_id <> %s
            AND ST_DWithin(d.geometry, i.geom, %s)
        INNER JOIN api_importqueue q
            ON q.id = d.queue_id
            AND q.imported = FALSE
            AND q.replacement IS NULL
            AND q.queue_status IN ('processing', 'ready')
            AND (q.timestamp, q.id) < (%s, %s)
        """,
        [user_id, exclude_queue_id, COORDINATE_TOLERANCE, uploaded_at, queue_id],
        lambda row: FeatureRef(
            draft_queue_id=row[2],
            spatial_index=row[3],
            name=row[4] or '',
            geometry_type=row[5] or '',
        ),
        geojson_index=6,
    )


def _run_temp_join(
    inputs: list[tuple[int, GEOSGeometry]],
    select_sql: str,
    params: list,
    ref_builder: Callable,
    geojson_index: int = 3,
) -> list[tuple[int, FeatureRef, dict[str, Any]]]:
    rows: list[tuple[int, FeatureRef, dict[str, Any]]] = []
    with transaction.atomic():
        with connection.cursor() as cursor:
            cursor.execute("DROP TABLE IF EXISTS import_geom_input")
            cursor.execute(
                """
                CREATE TEMP TABLE import_geom_input (
                    input_id integer PRIMARY KEY,
                    geom geometry
                )
                """
            )
            cursor.executemany(
                "INSERT INTO import_geom_input (input_id, geom) VALUES (%s, ST_GeomFromEWKT(%s))",
                [(input_id, geom.ewkt) for input_id, geom in inputs],
            )
            cursor.execute("CREATE INDEX import_geom_input_gist ON import_geom_input USING gist (geom)")
            cursor.execute(select_sql, params)
            for row in cursor.fetchall():
                raw_geojson = row[geojson_index]
                if not raw_geojson:
                    continue
                try:
                    geometry = json.loads(raw_geojson) if isinstance(raw_geojson, str) else raw_geojson
                except json.JSONDecodeError:
                    continue
                rows.append((row[0], ref_builder(row), geometry))
            cursor.execute("DROP TABLE IF EXISTS import_geom_input")
    return rows

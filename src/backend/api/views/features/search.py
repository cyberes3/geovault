import json

from django.db import connection
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.services.feature_serialization import build_feature_collection, geojson_feature_from_parts
from api.utils.responses import error_response, list_response, success_response
from geo_lib.contracts.pagination import PageQueryError, parse_page_query
from geo_lib.perf.list_projection import ListProjection
from geo_lib.perf.query_budget import QueryBudget
from geo_lib.tags.tag_query import TagQuery, TagQueryError
from website.auth_decorators import api_or_login_required_401
from website.settings_utils import get_required_setting

_projection = ListProjection()


@api_or_login_required_401()
@require_http_methods(["GET"])
def search_features(request):
    """
    API endpoint to search features by name, description, or tags.
    Searches across all user's features, not just those in view.

    Query parameters:
    - query: search text (required)
    """
    query = request.GET.get('query', '').strip()

    if not query:
        return error_response('query parameter is required', code=400)

    user_id = request.user.id
    table_name = FeatureStore._meta.db_table
    search_pattern = f'%{query}%'

    search_limit = QueryBudget(get_required_setting('MAX_FEATURES_PER_REQUEST')).clamp(100)

    try:
        tag_query = TagQuery.parse_search(query)
        tag_predicate, tag_params = tag_query.to_sql_predicate(feature_id_sql=f'{table_name}.id')
    except TagQueryError:
        tag_predicate, tag_params = '', []

    tag_or = f' OR {tag_predicate}' if tag_predicate else ''

    sql_query = f"""
        SELECT id, geojson, geojson_hash
        FROM {table_name}
        WHERE user_id = %s
          AND geometry IS NOT NULL
          AND scope IS NULL
          AND (
            geojson->'properties'->>'name' ILIKE %s OR
            geojson->'properties'->>'description' ILIKE %s
            {tag_or}
          )
        ORDER BY id
        LIMIT %s
    """

    params = [user_id, search_pattern, search_pattern, *tag_params, search_limit]

    with connection.cursor() as cursor:
        cursor.execute(sql_query, params)
        results = cursor.fetchall()

    geojson_features = []
    for feature_id, geojson_data, geojson_hash in results:
        if isinstance(geojson_data, str):
            geojson_data = json.loads(geojson_data)

        feature = geojson_feature_from_parts(feature_id, geojson_data, geojson_hash)
        if feature is not None:
            geojson_features.append(feature)

    response_data = {
        'data': build_feature_collection(geojson_features),
        'feature_count': len(geojson_features),
        'query': query
    }

    return success_response(response_data)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_all_features(request):
    """Catalog of owned main-map features as ListProjection rows."""
    budget = QueryBudget(get_required_setting('MAX_FEATURES_PER_REQUEST'))
    max_features = budget.clamp(None)
    try:
        page_query = parse_page_query(
            request.GET,
            default_page_size=max_features,
            max_page_size=max_features,
        )
    except PageQueryError as exc:
        return error_response(exc.message, code=400)

    features = FeatureStore.objects.owned_by(request.user).main_map().with_geometry().order_by('id')
    total_count = features.count()
    start = (page_query.page - 1) * page_query.page_size
    page_features = list(features.only('id', 'geojson')[start:start + page_query.page_size])

    items = []
    for feature in page_features:
        geojson = feature.geojson if isinstance(feature.geojson, dict) else {}
        properties = geojson.get('properties') if isinstance(geojson.get('properties'), dict) else {}
        geometry = geojson.get('geometry') if isinstance(geojson.get('geometry'), dict) else {}
        items.append(_projection.project(feature.id, properties, geometry))

    return list_response(items, page_query.page, page_query.page_size, total_count)

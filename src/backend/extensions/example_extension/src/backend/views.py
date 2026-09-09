import json

from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from api.services.feature_service import FeatureService
from api.utils.responses import error_response, handle_404, success_response
from extensions.example_extension.src.backend.models import EXAMPLE_SCOPE, ExampleItem
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.tags.protected import TagValidationError
from geo_lib.tags.tag_set import TagSet
from geo_lib.tags.tag_writer import SystemTagWriter, TagWriter
from geo_lib.reverse_geocoding.background_geocoding import reverse_geocode_feature_async
from geo_lib.types.feature import PointFeature
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def item_list_create(request):
    if request.method == "GET":
        items = list(
            ExampleItem.objects.filter(user=request.user).values("id", "name", "description")
        )
        return success_response({"items": items})

    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response("Invalid JSON in request body", 400)

    item = ExampleItem.objects.create(
        user=request.user,
        name=data.get("name", "Unnamed Item"),
        description=data.get("description", ""),
    )
    return success_response(
        {"id": item.id, "name": item.name, "description": item.description},
        status=201,
    )


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def item_delete(request, item_id):
    try:
        item = ExampleItem.objects.get(id=item_id, user=request.user)
    except ExampleItem.DoesNotExist:
        return error_response("Resource not found", 404)
    item.delete()
    return success_response({}, status=204)


@api_or_login_required_401()
@require_http_methods(["GET"])
def feature_list(request):
    rows = FeatureStore.objects.owned_by(request.user).in_scope(EXAMPLE_SCOPE)
    features = []
    for row in rows:
        geojson = dict(row.geojson) if isinstance(row.geojson, dict) else {}
        properties = dict(geojson.get("properties") or {})
        properties["database_id"] = row.id
        geojson["properties"] = properties
        features.append(geojson)
    return success_response({"features": features})


@api_or_login_required_401()
@require_http_methods(["POST"])
def create_feature(request):
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response("Invalid JSON in request body", 400)

    latitude = data.get("latitude")
    longitude = data.get("longitude")
    name = str(data.get("name", "")).strip()

    if latitude is None or longitude is None:
        return error_response("latitude and longitude are required", 400)

    try:
        latitude = float(latitude)
        longitude = float(longitude)
    except (ValueError, TypeError):
        return error_response("latitude and longitude must be valid numbers", 400)

    if not (-90 <= latitude <= 90):
        return error_response("latitude must be between -90 and 90", 400)
    if not (-180 <= longitude <= 180):
        return error_response("longitude must be between -180 and 180", 400)
    if not name:
        return error_response("name is required", 400)

    description = str(data.get("description", "")).strip()
    tags = data.get("tags", [])
    try:
        user_tags = list(TagSet.normalize_user_tags(tags if tags else []))
    except TagValidationError as exc:
        return error_response(str(exc), 400)

    feature = {
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [longitude, latitude, 0.0],
        },
        "properties": {
            "name": name,
            "description": description,
            "marker-color": "#ff0000",
            "tags": user_tags,
        },
    }

    try:
        normalized_feature = validate_and_normalize_geojson_feature(
            feature,
            preserve_system_tags=None,
            preserve_geojson_hash=False,
        )
    except GeometryValidationError as exc:
        return error_response(f"Feature validation failed: {exc}", 400)

    geojson_hash = generate_geojson_hash(normalized_feature)
    normalized_feature.setdefault("properties", {})
    normalized_feature["properties"]["geojson_hash"] = geojson_hash

    point_feature = PointFeature(**normalized_feature)
    system_tags = generate_auto_tags(
        point_feature,
        import_log=ImportLog(),
        filename="example-extension",
        skip_reverse_geocoding=True,
    )
    if "example-extension" not in system_tags:
        system_tags.append("example-extension")
    del normalized_feature["properties"]["geojson_hash"]
    normalized_feature["properties"]["system_tags"] = system_tags

    feature_store = FeatureService.create(
        request.user,
        normalized_feature,
        scope=EXAMPLE_SCOPE,
        geojson_hash=geojson_hash,
    )
    SystemTagWriter.write_creation_tags(feature_store, system_tags)
    reverse_geocode_feature_async(feature_store.id)
    normalized_feature["properties"]["database_id"] = feature_store.id
    return success_response({"feature": normalized_feature}, status=201)


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def modify_feature(request, feature_id):
    feature = FeatureService.get_owned_feature_or_404(
        request.user, feature_id, scope=EXAMPLE_SCOPE
    )
    original_geojson = feature.geojson
    if not isinstance(original_geojson, dict):
        return error_response("Invalid feature data in database", 500)

    special_tag = "example-extension:special"
    TagWriter.merge_user_tags(feature, [special_tag])
    feature.refresh_from_db()
    normalized_feature = feature.geojson
    normalized_feature["properties"]["database_id"] = feature.id
    return success_response({
        "feature": normalized_feature,
        "message": f'Feature modified: added tag "{special_tag}"',
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_feature(request, feature_id):
    feature = FeatureService.get_owned_feature_or_404(
        request.user, feature_id, scope=EXAMPLE_SCOPE
    )
    deleted_id = feature.id
    feature.delete()
    return success_response({
        "message": "Feature deleted successfully",
        "feature_id": deleted_id,
    })

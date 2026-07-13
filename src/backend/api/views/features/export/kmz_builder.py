from typing import Union

from django.db.models import QuerySet
from django.http import HttpResponse, JsonResponse
from django.utils.text import slugify

from api.utils.responses import error_response
from geo_lib.export.geojson_preprocessor import prepare_geojson_for_kmz
from geo_lib.export.geojson_to_kmz import geojson_to_kmz_bytes
from geo_lib.export.share_export import prepare_kmz_options_for_share
from geo_lib.utils.secure_path import secure_filename
from website.settings_utils import get_required_setting


def build_kmz_response(kmz_bytes: bytes, filename: str) -> HttpResponse:
    """
    Create an HttpResponse for KMZ file download.

    Args:
        kmz_bytes: KMZ file contents as bytes
        filename: Filename for the download

    Returns:
        HttpResponse with appropriate headers for KMZ download
    """
    safe_filename = secure_filename(filename)
    if len(safe_filename) > 255:
        if "." in safe_filename:
            name, ext = safe_filename.rsplit(".", 1)
            max_name_len = 255 - len(ext) - 1
            safe_filename = (name[:max_name_len] + "." + ext) if max_name_len > 0 else safe_filename[:255]
        else:
            safe_filename = safe_filename[:255]
    response = HttpResponse(
        kmz_bytes,
        content_type="application/vnd.google-earth.kmz",
    )
    response["Content-Disposition"] = f'attachment; filename="{safe_filename}"'
    return response


def queryset_to_kmz_response(features: QuerySet, name: str) -> Union[HttpResponse, JsonResponse]:
    """
    Convert a queryset of Features to a KMZ response.

    Args:
        features: QuerySet of FeatureStore objects
        name: Name to use for the KMZ file and internal document

    Returns:
        HttpResponse containing the KMZ file or JsonResponse on error
    """
    # Convert features to GeoJSON list
    geojson_features = []
    for feature in features:
        # No need for public_safe or allowing downloads check here since it's the owner
        geojson_data = feature.geojson
        if geojson_data:
            # Add id to properties if not present
            if 'properties' not in geojson_data:
                geojson_data['properties'] = {}
            geojson_data['properties']['database_id'] = feature.id

            # Pre-process GeoJSON (fix icon paths)
            prepared_geojson = prepare_geojson_for_kmz(geojson_data, str(get_required_setting('BASE_DIR')), get_required_setting('ICON_STORAGE_DIR'))
            geojson_features.append(prepared_geojson)

    if not geojson_features:
        return error_response("No features found", code=404)

    feature_collection = {
        "type": "FeatureCollection",
        "features": geojson_features
    }

    # Convert to KMZ
    options = prepare_kmz_options_for_share(name, str(get_required_setting('BASE_DIR')))
    kmz_bytes = geojson_to_kmz_bytes(feature_collection, options=options)

    # Build filename
    slug = slugify(name) or "export"
    filename = f"{slug}.kmz"

    return build_kmz_response(kmz_bytes, filename)

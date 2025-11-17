import json
import traceback

from django.contrib.gis.geos import GEOSGeometry
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, is_protected_tag
from geo_lib.feature_id import generate_feature_hash
from geo_lib.logging.console import get_access_logger
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
from geo_lib.validation.geometry_validation import (
    normalize_and_validate_feature_update,
    GeometryValidationError
)
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@csrf_protect
@require_http_methods(["PUT"])
def update_feature_metadata(request, feature_id):
    """
    API endpoint to update only the metadata of a specific feature (name, description, tags, created date).
    Does not modify geometry or geojson_hash.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: JSON object with optional fields:
    - name: string
    - description: string  
    - tags: array of strings
    - created: datetime string (ISO format)
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Parse request body
        try:
            metadata = json.loads(request.body)
        except json.JSONDecodeError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid JSON in request body',
                'code': 400
            }, status=400)

        # Validate that it's a proper object
        if not isinstance(metadata, dict):
            return JsonResponse({
                'success': False,
                'error': 'Request body must be a valid JSON object',
                'code': 400
            }, status=400)

        # Update only the specified metadata fields
        updated_fields = []
        geojson_data = feature.geojson.copy()

        if 'name' in metadata:
            if not isinstance(metadata['name'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'name must be a string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['name'] = metadata['name']
            updated_fields.append('name')

        if 'description' in metadata:
            if not isinstance(metadata['description'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'description must be a string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['description'] = metadata['description']
            updated_fields.append('description')

        if 'tags' in metadata:
            if not isinstance(metadata['tags'], list):
                return JsonResponse({
                    'success': False,
                    'error': 'tags must be an array',
                    'code': 400
                }, status=400)
            # Validate that all tags are strings
            for tag in metadata['tags']:
                if not isinstance(tag, str):
                    return JsonResponse({
                        'success': False,
                        'error': 'all tags must be strings',
                        'code': 400
                    }, status=400)

            # Filter out protected tags from incoming tags
            filtered_tags = filter_protected_tags(metadata['tags'], CONST_INTERNAL_TAGS)

            # Preserve existing protected tags from the original feature
            original_tags = geojson_data.get('properties', {}).get('tags', [])
            if not isinstance(original_tags, list):
                original_tags = []
            protected_tags = [tag for tag in original_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]

            # Combine filtered user tags with preserved protected tags
            geojson_data['properties']['tags'] = filtered_tags + protected_tags
            updated_fields.append('tags')

        if 'created' in metadata:
            if not isinstance(metadata['created'], str):
                return JsonResponse({
                    'success': False,
                    'error': 'created must be a string',
                    'code': 400
                }, status=400)
            # Validate datetime format
            try:
                from datetime import datetime
                datetime.fromisoformat(metadata['created'].replace('Z', '+00:00'))
            except ValueError:
                return JsonResponse({
                    'success': False,
                    'error': 'created must be a valid ISO datetime string',
                    'code': 400
                }, status=400)
            geojson_data['properties']['created'] = metadata['created']
            updated_fields.append('created')

        if not updated_fields:
            return JsonResponse({
                'success': False,
                'error': 'No valid fields to update. Supported fields: name, description, tags, created',
                'code': 400
            }, status=400)

        # Update the feature's geojson data
        feature.geojson = geojson_data
        feature.save()

        return JsonResponse({
            'success': True,
            'message': f'Feature metadata updated successfully. Updated fields: {", ".join(updated_fields)}',
            'feature_id': feature.id,
            'updated_fields': updated_fields
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error updating feature metadata {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update feature metadata',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["PUT"])
def update_feature(request, feature_id):
    """
    API endpoint to update a specific feature.

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: GeoJSON feature object
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Parse request body
        try:
            feature_data = json.loads(request.body)
        except json.JSONDecodeError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid JSON in request body',
                'code': 400
            }, status=400)

        # Validate that it's a proper GeoJSON feature or geometry
        if not isinstance(feature_data, dict):
            return JsonResponse({
                'success': False,
                'error': 'Request body must be a valid GeoJSON object',
                'code': 400
            }, status=400)

        # Get original feature data for reference
        original_geojson = feature.geojson
        original_properties = original_geojson.get('properties', {})

        # Normalize and validate Feature or geometry object
        try:
            feature_data = normalize_and_validate_feature_update(feature_data, original_properties)
        except GeometryValidationError as e:
            return JsonResponse({
                'success': False,
                'error': str(e),
                'code': 400
            }, status=400)

        # Get new properties (if geometry-only update, properties were already set to original)
        new_properties = feature_data.get('properties', {})

        # Preserve protected tags from original feature
        original_tags = original_properties.get('tags', [])
        if not isinstance(original_tags, list):
            original_tags = []
        protected_tags = [tag for tag in original_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]

        # Filter protected tags from incoming tags
        new_tags = new_properties.get('tags', [])
        if not isinstance(new_tags, list):
            new_tags = []
        filtered_tags = filter_protected_tags(new_tags, CONST_INTERNAL_TAGS)

        # Combine filtered user tags with preserved protected tags
        new_properties['tags'] = filtered_tags + protected_tags

        # Check for custom PNG icon URLs in original feature
        icon_property_names = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
        original_icon_url = None
        for prop_name in icon_property_names:
            if prop_name in original_properties and original_properties[prop_name]:
                icon_url = original_properties[prop_name]
                if isinstance(icon_url, str):
                    # Check if it's a PNG icon (ends with .png or starts with /api/data/icons/)
                    if icon_url.endswith('.png') or icon_url.startswith('/api/data/icons/'):
                        original_icon_url = icon_url
                        break

        # Handle icon URL changes
        # Allow: removing icons (null/empty), setting new icons from upload endpoint, keeping same icon
        # Prevent: manually changing existing icon URLs to different values
        if original_icon_url:
            # Check if icon is being removed (main 'icon' property is empty)
            if new_properties.get('icon') == '':
                # Icon is being removed - clear all icon properties and ensure marker-color is set
                for prop_name in icon_property_names:
                    new_properties[prop_name] = ''
                if 'marker-color' not in new_properties or not new_properties.get('marker-color'):
                    new_properties['marker-color'] = original_properties.get('marker-color', '#ff0000')
            else:
                # Icon is not being removed - prevent manual URL changes
                for prop_name in icon_property_names:
                    if prop_name in new_properties:
                        new_icon_url = new_properties[prop_name]
                        # Allow keeping the same icon or setting new icon from upload endpoint
                        if (isinstance(new_icon_url, str) and
                                (new_icon_url == original_icon_url or new_icon_url.startswith('/api/data/icons/'))):
                            continue
                        # Prevent manually changing to a different URL (must use upload endpoint)
                        if isinstance(new_icon_url, str) and new_icon_url != original_icon_url:
                            new_properties[prop_name] = original_icon_url
                            logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")

        # Validate feature structure using the same validation as import conversion
        try:
            geom_type = feature_data.get('geometry', {}).get('type', '').lower()
            feature_class = None

            # GeometryCollection is not supported by the feature classes, but we allow it
            if geom_type == 'geometrycollection':
                # For GeometryCollection, we do basic validation but skip feature class validation
                geom_data = feature_data.get('geometry', {})
                if not geom_data.get('geometries') or not isinstance(geom_data.get('geometries'), list):
                    return JsonResponse({
                        'success': False,
                        'error': 'GeometryCollection must have a geometries array',
                        'code': 400
                    }, status=400)
                # Skip feature class validation for GeometryCollection
                feature_class = None
            else:
                match geom_type:
                    case 'point' | 'multipoint':
                        feature_class = PointFeature
                    case 'linestring':
                        feature_class = LineStringFeature
                    case 'multilinestring':
                        feature_class = MultiLineStringFeature
                    case 'polygon' | 'multipolygon':
                        feature_class = PolygonFeature
                    case _:
                        return JsonResponse({
                            'success': False,
                            'error': f'Unsupported geometry type: {geom_type}',
                            'code': 400
                        }, status=400)

            # Validate by instantiating the feature class (this will raise ValidationError if invalid)
            # Skip for GeometryCollection as it's not supported by feature classes
            if feature_class is not None:
                validated_feature = feature_class(**feature_data)
                # Convert back to dict for storage (this ensures proper structure)
                feature_data = json.loads(validated_feature.model_dump_json())

        except Exception as e:
            logger.error(f"Feature validation error for feature {feature_id}: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': f'Feature validation failed: {str(e)}',
                'code': 400
            }, status=400)

        # Update the feature data
        feature.geojson = feature_data

        # Regenerate the hash for the updated feature
        feature.geojson_hash = generate_feature_hash(feature_data)

        # Update the geometry field if coordinates changed
        try:
            geom_data = feature_data.get('geometry', {})
            if geom_data and geom_data.get('type'):
                # Handle GeometryCollection separately (not supported by GEOSGeometry)
                if geom_data['type'] == 'GeometryCollection':
                    # For GeometryCollection, we can't use GEOSGeometry, so skip geometry field update
                    # The geometry will be stored in the geojson field
                    pass
                elif geom_data.get('coordinates'):
                    # Ensure coordinates have 3 dimensions for consistency
                    coords = geom_data['coordinates']
                    if geom_data['type'] == 'Point':
                        if len(coords) == 2:
                            coords = [coords[0], coords[1], 0.0]
                    elif geom_data['type'] == 'LineString':
                        geom_data['coordinates'] = [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in coords
                        ]
                    elif geom_data['type'] == 'Polygon':
                        geom_data['coordinates'] = [
                            [
                                [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                                for coord in ring
                            ]
                            for ring in coords
                        ]

                    feature.geometry = GEOSGeometry(json.dumps(geom_data))
        except Exception as e:
            logger.warning(f"Error updating geometry for feature {feature_id}: {e}")
            # Continue without updating geometry if there's an error

        # Save the updated feature
        feature.save()

        return JsonResponse({
            'success': True,
            'message': 'Feature updated successfully',
            'feature_id': feature.id
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error updating feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update feature',
            'code': 500
        }, status=500)

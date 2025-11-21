import json
import traceback

from django.conf import settings
from django.contrib.gis.geos import GEOSGeometry
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from api.models import FeatureStore, ImportQueue
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, is_protected_tag
from geo_lib.feature_id import generate_feature_hash
from geo_lib.logging.console import get_access_logger
from geo_lib.types.feature import PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature, GeoFeatureSupported
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
                
                # Validate tag length
                tag_max_length = getattr(settings, 'TAG_MAX_LENGTH', 255)
                if len(tag) > tag_max_length:
                    return JsonResponse({
                        'success': False,
                        'error': f'Tag "{tag[:50]}..." exceeds maximum length of {tag_max_length} characters',
                        'code': 400
                    }, status=400)
                
                # Validate tag is not empty after stripping
                if not tag.strip():
                    return JsonResponse({
                        'success': False,
                        'error': 'Tags cannot be empty or contain only whitespace',
                        'code': 400
                    }, status=400)
                
                # Validate tag format: no control characters
                if any(ord(c) < 32 and c not in '\t\n\r' for c in tag):
                    return JsonResponse({
                        'success': False,
                        'error': 'Tags cannot contain control characters',
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
        
        # Validate tags
        for tag in new_tags:
            if not isinstance(tag, str):
                return JsonResponse({
                    'success': False,
                    'error': 'all tags must be strings',
                    'code': 400
                }, status=400)
            
            # Validate tag length
            tag_max_length = getattr(settings, 'TAG_MAX_LENGTH', 255)
            if len(tag) > tag_max_length:
                return JsonResponse({
                    'success': False,
                    'error': f'Tag "{tag[:50]}..." exceeds maximum length of {tag_max_length} characters',
                    'code': 400
                }, status=400)
            
            # Validate tag is not empty after stripping
            if not tag.strip():
                return JsonResponse({
                    'success': False,
                    'error': 'Tags cannot be empty or contain only whitespace',
                    'code': 400
                }, status=400)
            
            # Validate tag format: no control characters
            if any(ord(c) < 32 and c not in '\t\n\r' for c in tag):
                return JsonResponse({
                    'success': False,
                    'error': 'Tags cannot contain control characters',
                    'code': 400
                }, status=400)
        
        filtered_tags = filter_protected_tags(new_tags, CONST_INTERNAL_TAGS)

        # Combine filtered user tags with preserved protected tags
        new_properties['tags'] = filtered_tags + protected_tags

        # Check for icon URLs in original feature (built-in, uploaded, or custom)
        icon_property_names = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
        original_icon_url = None
        for prop_name in icon_property_names:
            if prop_name in original_properties and original_properties[prop_name]:
                icon_url = original_properties[prop_name]
                if isinstance(icon_url, str) and icon_url.strip():
                    # Check if it's an icon (built-in, uploaded, or ends with image extension)
                    if (icon_url.startswith('assets/') or 
                            icon_url.startswith('/api/data/icons/') or 
                            icon_url.endswith(('.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico'))):
                        original_icon_url = icon_url
                        break

        # Handle icon URL changes
        # Allow: removing icons (null/empty), setting new built-in icons (assets/), 
        #        setting new uploaded icons (/api/data/icons/), keeping same icon
        # Prevent: manually changing existing icon URLs to arbitrary external URLs
        new_icon_url = new_properties.get('icon', '')
        
        if original_icon_url:
            # Check if icon is being removed (main 'icon' property is empty)
            if new_icon_url == '':
                # Icon is being removed - clear all icon properties and ensure marker-color is set
                for prop_name in icon_property_names:
                    new_properties[prop_name] = ''
                if 'marker-color' not in new_properties or not new_properties.get('marker-color'):
                    new_properties['marker-color'] = original_properties.get('marker-color', '#ff0000')
            elif isinstance(new_icon_url, str) and new_icon_url.strip():
                # Icon is being changed - validate new icon URL
                # Allow: same icon, built-in icons (assets/), uploaded icons (/api/data/icons/)
                if (new_icon_url == original_icon_url or 
                        new_icon_url.startswith('assets/') or 
                        new_icon_url.startswith('/api/data/icons/')):
                    # Valid icon change - clear other icon property names to avoid conflicts
                    for prop_name in icon_property_names:
                        if prop_name != 'icon' and prop_name in new_properties:
                            del new_properties[prop_name]
                else:
                    # Invalid external URL - restore original icon
                    new_properties['icon'] = original_icon_url
                    # Clear other icon properties
                    for prop_name in icon_property_names:
                        if prop_name != 'icon':
                            new_properties[prop_name] = ''
                    logger.warning(f"Attempted to manually change icon URL for feature {feature_id}, restored original")
        else:
            # No original icon - validate that new icons are built-in or uploaded (not external URLs)
            if isinstance(new_icon_url, str) and new_icon_url.strip():
                # Only allow built-in icons (assets/) or uploaded icons (/api/data/icons/)
                if not (new_icon_url.startswith('assets/') or new_icon_url.startswith('/api/data/icons/')):
                    # Remove invalid external icon URL
                    new_properties['icon'] = ''
                    # Clear other icon properties
                    for prop_name in icon_property_names:
                        if prop_name != 'icon':
                            new_properties[prop_name] = ''
                    logger.warning(f"Attempted to set external icon URL for feature {feature_id}, removed (only built-in and uploaded icons allowed)")

        # Prevent stroke-width changes for lines and polygons (normalized on import)
        geom_type = feature_data.get('geometry', {}).get('type', '').lower()
        if geom_type in ['linestring', 'multilinestring', 'polygon', 'multipolygon']:
            # Restore original stroke-width value (normalized to 2 on import)
            original_stroke_width = original_properties.get('stroke-width', 2)
            if 'stroke-width' in new_properties and new_properties.get('stroke-width') != original_stroke_width:
                new_properties['stroke-width'] = original_stroke_width
                logger.warning(f"Attempted to change stroke-width for feature {feature_id}, restored original value (normalized on import)")

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
        feature.file_hash = generate_feature_hash(feature_data)

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


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def apply_replacement_geometry(request, feature_id):
    """
    API endpoint to apply replacement geometry from an ImportQueue entry to an existing feature.
    Only updates the geometry, preserving all properties (name, description, tags, styling, etc.).

    URL parameter:
    - feature_id: ID of the feature to update

    Request body: JSON object with:
    - import_queue_id: ID of the ImportQueue entry containing the replacement features
    - feature_index: Index of the feature in the ImportQueue.geofeatures array to use
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Parse request body
        try:
            request_data = json.loads(request.body)
        except json.JSONDecodeError:
            return JsonResponse({
                'success': False,
                'error': 'Invalid JSON in request body',
                'code': 400
            }, status=400)

        # Validate required fields
        if 'import_queue_id' not in request_data or 'feature_index' not in request_data:
            return JsonResponse({
                'success': False,
                'error': 'Missing required fields: import_queue_id and feature_index',
                'code': 400
            }, status=400)

        import_queue_id = request_data['import_queue_id']
        feature_index = request_data['feature_index']

        # Validate feature_index is an integer
        try:
            feature_index = int(feature_index)
        except (ValueError, TypeError):
            return JsonResponse({
                'success': False,
                'error': 'feature_index must be an integer',
                'code': 400
            }, status=400)

        # Get the ImportQueue entry
        try:
            import_queue = ImportQueue.objects.get(id=import_queue_id, user=request.user)
        except ImportQueue.DoesNotExist:
            return JsonResponse({
                'success': False,
                'error': 'ImportQueue entry not found or access denied',
                'code': 404
            }, status=404)

        # Verify this is a replacement upload for this feature
        if import_queue.replacement != feature_id:
            return JsonResponse({
                'success': False,
                'error': 'ImportQueue entry is not a replacement for this feature',
                'code': 400
            }, status=400)

        # Get the features from the ImportQueue
        geofeatures = import_queue.geofeatures
        if not isinstance(geofeatures, list) or len(geofeatures) == 0:
            return JsonResponse({
                'success': False,
                'error': 'ImportQueue entry has no features',
                'code': 400
            }, status=400)

        # Validate feature_index is within bounds
        if feature_index < 0 or feature_index >= len(geofeatures):
            return JsonResponse({
                'success': False,
                'error': f'feature_index {feature_index} is out of bounds (0-{len(geofeatures)-1})',
                'code': 400
            }, status=400)

        # Get the selected replacement feature
        replacement_feature = geofeatures[feature_index]
        if not isinstance(replacement_feature, dict) or 'geometry' not in replacement_feature:
            return JsonResponse({
                'success': False,
                'error': 'Selected feature has invalid structure or missing geometry',
                'code': 400
            }, status=400)

        # Get the replacement geometry
        replacement_geometry = replacement_feature.get('geometry')
        if not replacement_geometry:
            return JsonResponse({
                'success': False,
                'error': 'Selected feature has no geometry',
                'code': 400
            }, status=400)

        # Get original feature data
        original_geojson = feature.geojson.copy()
        original_properties = original_geojson.get('properties', {})

        # Create updated feature with replacement geometry but original properties
        updated_feature = {
            'type': 'Feature',
            'geometry': replacement_geometry,
            'properties': original_properties
        }

        # Validate the updated feature
        try:
            feature_data = normalize_and_validate_feature_update(updated_feature, original_properties)
        except GeometryValidationError as e:
            return JsonResponse({
                'success': False,
                'error': str(e),
                'code': 400
            }, status=400)

        # Validate feature structure using feature classes
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
            logger.error(f"Feature validation error for replacement feature {feature_id}: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': f'Feature validation failed: {str(e)}',
                'code': 400
            }, status=400)

        # Update the feature's geometry (preserving all properties)
        feature.geojson = feature_data

        # Regenerate the hash for the updated feature
        feature.file_hash = generate_feature_hash(feature_data)

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

        # Delete the ImportQueue row after successful application
        import_queue.delete()

        return JsonResponse({
            'success': True,
            'message': 'Replacement geometry applied successfully',
            'feature_id': feature.id
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error applying replacement geometry for feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to apply replacement geometry',
            'code': 500
        }, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def regenerate_feature_tags(request, feature_id):
    """
    API endpoint to regenerate automatic tags for a feature based on its current geometry.
    Preserves existing non-auto tags (user-generated tags that don't match auto tag patterns).

    URL parameter:
    - feature_id: ID of the feature to regenerate tags for
    """
    try:
        # Get the feature from database
        feature = FeatureStore.objects.get(id=feature_id, user=request.user)

        # Get the feature's GeoJSON data
        geojson_data = feature.geojson

        # Convert to feature class instance for tag generation
        geom_type = geojson_data.get('geometry', {}).get('type', '').lower()
        feature_class = None

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

        if feature_class is None:
            return JsonResponse({
                'success': False,
                'error': 'Could not determine feature class',
                'code': 400
            }, status=400)

        # Create feature instance
        try:
            feature_instance: GeoFeatureSupported = feature_class(**geojson_data)
        except Exception as e:
            logger.error(f"Error creating feature instance for tag regeneration {feature_id}: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': f'Invalid feature structure: {str(e)}',
                'code': 400
            }, status=400)

        # Get existing tags
        existing_tags = geojson_data.get('properties', {}).get('tags', [])
        if not isinstance(existing_tags, list):
            existing_tags = []

        # Identify auto tags and non-auto tags
        # Auto tags match patterns: type:*, import-year:*, import-month:*, or location tags (contain : and start with location identifiers)
        auto_tag_patterns = ['type:', 'import-year:', 'import-month:']
        non_auto_tags = []
        for tag in existing_tags:
            if not isinstance(tag, str):
                continue
            # Check if it's an auto tag
            is_auto_tag = any(tag.startswith(pattern) for pattern in auto_tag_patterns)
            # Location tags typically contain ':' and are generated by geocoding
            # They usually have patterns like "country:*", "state:*", "city:*", etc.
            if not is_auto_tag and ':' in tag:
                # Could be a location tag, but we'll be conservative and only remove known auto patterns
                # For now, we'll keep tags with ':' that don't match auto patterns as user tags
                # This is safer - users can manually remove location tags if needed
                pass
            if not is_auto_tag:
                non_auto_tags.append(tag)

        # Generate new auto tags
        from geo_lib.processing.tagging import generate_auto_tags
        new_auto_tags = generate_auto_tags(feature_instance, import_log=None)

        # Combine non-auto tags with new auto tags, avoiding duplicates
        all_tags = list(non_auto_tags)
        for tag in new_auto_tags:
            if tag not in all_tags:
                all_tags.append(tag)

        # Preserve protected tags from original feature
        protected_tags = [tag for tag in existing_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]
        
        # Combine: non-auto tags + new auto tags + protected tags (avoiding duplicates)
        final_tags = list(non_auto_tags)
        for tag in new_auto_tags:
            if tag not in final_tags:
                final_tags.append(tag)
        for tag in protected_tags:
            if tag not in final_tags:
                final_tags.append(tag)

        # Update the feature's tags
        if 'properties' not in geojson_data:
            geojson_data['properties'] = {}
        geojson_data['properties']['tags'] = final_tags

        # Update the feature
        feature.geojson = geojson_data
        feature.save()

        return JsonResponse({
            'success': True,
            'message': 'Feature tags regenerated successfully',
            'feature_id': feature.id,
            'tags': final_tags
        })

    except FeatureStore.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Feature not found or access denied',
            'code': 404
        }, status=404)
    except Exception as e:
        logger.error(f"Error regenerating tags for feature {feature_id}: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to regenerate feature tags',
            'code': 500
        }, status=500)

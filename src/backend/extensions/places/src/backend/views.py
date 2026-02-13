import json

from django.views.decorators.http import require_http_methods
from pydantic import ValidationError as PydanticValidationError

from api.models import FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401

from .validation import PlaceFeaturePayload

@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def places_list(request):
    """
    GET: List all places (features with scope='places')
    POST: Create a new place (feature with scope='places')
    """
    if request.method == "GET":
        features = FeatureStore.objects.filter(
            user=request.user, 
            scope='places'
        ).order_by('-timestamp')
        
        data = []
        for f in features:
            geojson = f.geojson
            if geojson and 'properties' in geojson:
                geojson['properties']['database_id'] = f.id
            data.append(geojson)
            
        return success_response({
            'type': 'FeatureCollection',
            'features': data
        })
        
    elif request.method == "POST":
        try:
            data = json.loads(request.body)
            try:
                PlaceFeaturePayload.model_validate(data)
            except PydanticValidationError as e:
                err_msgs = [f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}" for err in e.errors()]
                return error_response(
                    'Invalid payload: only Point features are allowed' + (f' — {"; ".join(err_msgs)}' if err_msgs else ''),
                    400,
                )

            # Normalize and validate
            try:
                normalized_feature = validate_and_normalize_geojson_feature(
                    data, preserve_system_tags=None, preserve_geojson_hash=False
                )
            except GeometryValidationError:
                return error_response('Invalid geometry', 400)
                
            # Extract geometry for DB field
            geom_dict = normalized_feature.get('geometry')
            if not geom_dict:
                 return error_response('Geometry is required', 400)
            # FeatureStore.geometry is dim=3; ensure Point has Z (use 0 if 2D)
            coords = geom_dict.get('coordinates')
            if geom_dict.get('type') == 'Point' and coords is not None and len(coords) == 2:
                geom_dict = {**geom_dict, 'coordinates': [*coords, 0]}
            from django.contrib.gis.geos import GEOSGeometry
            geometry = GEOSGeometry(json.dumps(geom_dict))
            
            # Generate Hash
            geojson_hash = generate_geojson_hash(normalized_feature)
            
            # Create
            feature = FeatureStore.objects.create(
                user=request.user,
                scope='places',
                geojson=normalized_feature,
                geometry=geometry,
                geojson_hash=geojson_hash
            )
            
            normalized_feature['properties']['database_id'] = feature.id
            return success_response(normalized_feature, status=201)
            
        except json.JSONDecodeError:
            return error_response('Invalid JSON', 400)

@api_or_login_required_401()
@require_http_methods(["GET", "PUT", "DELETE"])
def place_detail(request, feature_id):
    """
    GET: Retrieve a place
    PUT: Update a place
    DELETE: Delete a place
    """
    feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
    
    # Ensure it's a place
    if feature.scope != 'places':
         return error_response('Feature is not a place', 404)

    if request.method == "GET":
        geojson = feature.geojson
        if geojson and 'properties' in geojson:
            geojson['properties']['database_id'] = feature.id
        return success_response(geojson)
        
    elif request.method == "PUT":
        try:
            data = json.loads(request.body)
            try:
                PlaceFeaturePayload.model_validate(data)
            except PydanticValidationError as e:
                err_msgs = [f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}" for err in e.errors()]
                return error_response(
                    'Invalid payload: only Point features are allowed' + (f' — {"; ".join(err_msgs)}' if err_msgs else ''),
                    400,
                )

            # Normalize and validate
            try:
                normalized_feature = validate_and_normalize_geojson_feature(
                    data, preserve_system_tags=None, preserve_geojson_hash=False
                )
            except GeometryValidationError:
                return error_response('Invalid geometry', 400)

            # Extract geometry
            geom_dict = normalized_feature.get('geometry')
            if not geom_dict:
                 return error_response('Geometry is required', 400)
            # FeatureStore.geometry is dim=3; ensure Point has Z (use 0 if 2D)
            coords = geom_dict.get('coordinates')
            if geom_dict.get('type') == 'Point' and coords is not None and len(coords) == 2:
                geom_dict = {**geom_dict, 'coordinates': [*coords, 0]}
            from django.contrib.gis.geos import GEOSGeometry
            geometry = GEOSGeometry(json.dumps(geom_dict))

            # Generate Hash
            geojson_hash = generate_geojson_hash(normalized_feature)

            # Update
            feature.geojson = normalized_feature
            feature.geometry = geometry
            feature.geojson_hash = geojson_hash
            feature.save()
            
            normalized_feature['properties']['database_id'] = feature.id
            return success_response(normalized_feature)
            
        except json.JSONDecodeError:
            return error_response('Invalid JSON', 400)
            
    elif request.method == "DELETE":
        feature.delete()
        return success_response({'deleted': True})

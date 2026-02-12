import json
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from django.contrib.gis.geos import Point
from api.models import FeatureStore
from geo_lib.website.auth import api_or_login_required_401
from api.utils.responses import success_response, error_response
from api.utils.authorization import get_object_or_404_for_user
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.validation.geometry_validation import GeometryValidationError

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
            # Ensure it's a Feature
            if data.get('type') != 'Feature':
                return error_response('Invalid GeoJSON: must be a Feature', 400)
            
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
            
            # For this simple extension, we handle Points. 
            # If complex geometries are needed, we'd use GEOSGeometry(json.dumps(geom_dict))
            # But let's stick to what creation.py does or similar.
            # actually better to use GEOSGeometry to be safe for all types
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
             # Ensure it's a Feature
            if data.get('type') != 'Feature':
                return error_response('Invalid GeoJSON: must be a Feature', 400)

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

"""
Tag generator modules.
"""
from geo_lib.processing.tagging.modules.geometry_type import GeometryTypeTagGenerator
from geo_lib.processing.tagging.modules.import_date import ImportDateTagGenerator
from geo_lib.processing.tagging.modules.feature_date import FeatureDateTagGenerator, update_feature_date_tags
from geo_lib.processing.tagging.modules.track_detection import TrackDetectionTagGenerator
from geo_lib.processing.tagging.modules.source_file import SourceFileTagGenerator
from geo_lib.processing.tagging.modules.elevation import ElevationTagGenerator
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator, get_representative_points

__all__ = [
    'GeometryTypeTagGenerator',
    'ImportDateTagGenerator',
    'FeatureDateTagGenerator',
    'TrackDetectionTagGenerator',
    'SourceFileTagGenerator',
    'ElevationTagGenerator',
    'GeocodingTagGenerator',
    'update_feature_date_tags',
    'get_representative_points',
]


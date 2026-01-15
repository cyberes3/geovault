"""
CalTopo API constants and shared definitions.
"""

# Valid CalTopo feature classes as per caltopo_python documentation
VALID_CALTOPO_FEATURE_CLASSES = {
    'Shape', 'Marker', 'AppTrack', 'LiveTrack', 'Folder', 
    'MapMediaObject', 'OperationalPeriod', 'Assignment', 
    'Clue', 'Resource', 'SmsLocationRequest'
}


def is_valid_caltopo_feature_class(feature_class: str) -> bool:
    """
    Check if a feature class is valid for import.
    
    Args:
        feature_class: CalTopo feature class string
        
    Returns:
        True if the feature class is valid, False otherwise
    """
    return feature_class in VALID_CALTOPO_FEATURE_CLASSES

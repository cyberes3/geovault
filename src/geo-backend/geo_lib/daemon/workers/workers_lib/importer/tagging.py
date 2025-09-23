from datetime import datetime
from typing import List

from geo_lib.types.feature import GeoFeatureSupported


def generate_auto_tags(feature: GeoFeatureSupported) -> List[str]:
    tags = [
        f'type:{feature.geometry.type.value.lower()}'
    ]

    now = datetime.now()
    tags.append(f'import-year:{now.year}')
    tags.append(f'import-month:{now.strftime("%B")}')
    return [str(x) for x in tags]

from dataclasses import dataclass

from geo_lib.duplicates.geometry_join import GeometryJoin
from geo_lib.duplicates.hash_index import HashIndex


@dataclass
class DuplicateIndex:
    library_hashes: HashIndex
    library_geometry: GeometryJoin
    draft_hashes: HashIndex
    draft_geometry: GeometryJoin

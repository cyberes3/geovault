from geo_lib.feature_id import generate_geojson_hash
from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.processing.logging import DatabaseLogLevel


class HashStage:
    name = 'hash'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before hash')
        for feature in ctx.features:
            if 'properties' not in feature or feature['properties'] is None:
                feature['properties'] = {}
            feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
        ctx.log.add(
            f"Hashed {len(ctx.features)} features after last mutation",
            "Hash",
            DatabaseLogLevel.DEBUG,
        )

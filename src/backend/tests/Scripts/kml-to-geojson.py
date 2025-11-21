import argparse
import sys
from pathlib import Path

sys.path.append(str(list(Path(__file__).parents)[1]))
from geo_lib.processing.processors import get_processor
from geo_lib.types.feature import geojson_to_geofeature, geofeature_to_geojson

parser = argparse.ArgumentParser()
parser.add_argument('kml_path')
args = parser.parse_args()

raw_kml = Path(args.kml_path).expanduser().absolute().resolve().read_text()

processor = get_processor(raw_kml, args.kml_path)
geojson_data, kml_conv_messages = processor.process()

geofetures, typing_messages = geojson_to_geofeature(geojson_data)

print(geofeature_to_geojson(geofetures))

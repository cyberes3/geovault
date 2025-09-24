import argparse
import sys
from pathlib import Path

sys.path.append(str(list(Path(__file__).parents)[1]))
from geo_lib.daemon.workers.workers_lib.importer.kml_new import kml_to_geojson
from geo_lib.types.feature import geojson_to_geofeature, geofeature_to_geojson

parser = argparse.ArgumentParser()
parser.add_argument('kml_path')
args = parser.parse_args()

raw_kml = Path(args.kml_path).expanduser().absolute().resolve().read_text()

geojson_data, kml_conv_messages = kml_to_geojson(raw_kml)

geofetures, typing_messages = geojson_to_geofeature(geojson_data)

print(geofeature_to_geojson(geofetures))

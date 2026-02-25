#!/usr/bin/env bash
# Download western mainland Europe + European islands (e.g. Madeira, Azores) from Geofabrik.
# Excludes: Greece, Cyprus, Malta.
# See: https://download.geofabrik.de/europe.html

set -euo pipefail

BASE_URL="https://download.geofabrik.de/europe"
DEST_DIR="${1:-/srv/downloads/europe}"
VERIFY_MD5="${VERIFY_MD5:-1}"

# Western mainland Europe + islands (Italy incl. Sicily/Sardinia; Portugal incl. Madeira/Azores; Spain incl. Canaries).
# Uncomment to add: ireland-and-northern-ireland, great-britain, iceland, faroe-islands.
REGIONS=(
  austria
  belgium
  france
  germany
  italy
  luxembourg
  netherlands
  portugal
  spain
  switzerland
)

download_one() {
  local name="$1"
  local url="${BASE_URL}/${name}-latest.osm.pbf"
  local dest="${DEST_DIR}/${name}-latest.osm.pbf"
  local md5_url="${url}.md5"
  local md5_dest="${dest}.md5"

  if [[ -f "$dest" ]]; then
    echo "[skip] $dest already exists"
    return 0
  fi

  echo "[downloading] $url -> $dest"
  aria2c -X 16 -d "$DEST_DIR" -o "${name}-latest.osm.pbf" "$url"

  if [[ "$VERIFY_MD5" == "1" ]]; then
    aria2c -X 16 -d "$DEST_DIR" -o "${name}-latest.osm.pbf.md5" "$md5_url"
    ( cd "$DEST_DIR" && md5sum -c "${name}-latest.osm.pbf.md5" ) || { echo "MD5 mismatch for $dest" >&2; exit 1; }
    echo "[verified] $dest"
  fi
}

mkdir -p "$DEST_DIR"
echo "Destination: $DEST_DIR"
echo "Regions: ${REGIONS[*]}"
echo ""

for r in "${REGIONS[@]}"; do
  download_one "$r"
done

echo ""
echo "Done. To merge into a single PBF (e.g. for import-pbf.sh):"
echo "  osmium merge \\"
for r in "${REGIONS[@]}"; do
  echo "    ${DEST_DIR}/${r}-latest.osm.pbf \\"
done
echo "    -o /srv/downloads/western-europe.osm.pbf"

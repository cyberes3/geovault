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

  if [[ -f "$dest" ]]; then
    echo "[skip] $dest already exists"
    return 0
  fi

  echo "[downloading] $url -> $dest"
  aria2c -x 16 -d "$DEST_DIR" -o "${name}-latest.osm.pbf" "$url"

  if [[ "$VERIFY_MD5" == "1" ]]; then
    expected_md5=$(curl -sSL "$md5_url" | awk '{print $1}')
    if [[ -z "$expected_md5" || ${#expected_md5} -ne 32 ]]; then
      echo "Failed to get MD5 for $name from $md5_url" >&2
      exit 1
    fi
    actual_md5=$(md5sum "$dest" | awk '{print $1}')
    if [[ "$expected_md5" != "$actual_md5" ]]; then
      echo "MD5 mismatch for $dest (expected $expected_md5, got $actual_md5)" >&2
      exit 1
    fi
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

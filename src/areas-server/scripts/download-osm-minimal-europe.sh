#!/usr/bin/env bash
# Download OSM data for western/central Europe from Geofabrik (British Isles, W/C Europe, no Balkans, Turkey, or N Africa).
# See: https://download.geofabrik.de/europe.html

set -euo pipefail

BASE_URL="https://download.geofabrik.de/europe"
DEST_DIR="${1:-/srv/downloads/europe}"
VERIFY_MD5="${VERIFY_MD5:-1}"

# British Isles, western Europe (excl. nations east of Germany, Balkans, Turkey, Cyprus, Malta).
REGIONS=(
  austria
  belgium
  denmark
  france
  germany
  great-britain
  ireland-and-northern-ireland
  italy
  luxembourg
  netherlands
  norway
  portugal
  spain
  sweden
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
echo ""

for r in "${REGIONS[@]}"; do
  download_one "$r"
done

echo ""
echo "Done. To merge into a single PBF (e.g. for import-pbf.sh):"
echo "  osmium merge ${DEST_DIR}/*-latest.osm.pbf -o /srv/downloads/western-europe.osm.pbf --overwrite --with-history"
echo "To merge two PBFs with deduplication, use: scripts/merge-pbf-dedup.sh file1.pbf file2.pbf -o out.pbf"

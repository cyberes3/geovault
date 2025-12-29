#!/usr/bin/env python3
"""
Example script demonstrating how to use GeoVault's processing API to extract unique tracks
from backup KML files that are not in an Archive directory.

This script shows how to:
- Use KMLProcessor and GPXProcessor to convert files to GeoJSON
- Use generate_geojson_hash for duplicate detection
- Compare tracks between multiple sources
- Convert GeoJSON back to GPX format

Usage:
    python extract_unique_tracks.py <archive_dir> <backup_kml_file1> [<backup_kml_file2> ...]

Example:
    python extract_unique_tracks.py ./Archive ./backup1.kml ./backup2.kml
"""

import os
import sys
import argparse
from pathlib import Path
from typing import Dict, List, Set, Any
import json

# Add backend to path
backend_path = Path(__file__).parent.parent / 'backend'
sys.path.insert(0, str(backend_path))

# Set up Django environment if needed
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

# Try to set up Django (may fail if not needed, that's okay)
try:
    import django
    django.setup()
except:
    pass  # Django setup may not be needed for basic conversion

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.processors.kml_processor import KMLProcessor
from geo_lib.processing.processors.gpx_processor import GPXProcessor
from geo_lib.processing.logging import ImportLog


def load_archive_tracks(archive_dir: Path) -> Dict[str, Dict[str, Any]]:
    """
    Load all tracks from Archive directory GPX files.
    Returns a dictionary mapping track hash to track feature.
    """
    archive_tracks = {}
    archive_files = sorted(archive_dir.glob('*.gpx'))
    
    print(f"Loading {len(archive_files)} GPX files from Archive directory...")
    
    for gpx_file in archive_files:
        try:
            # Create a mock processor to convert GPX to GeoJSON
            with open(gpx_file, 'rb') as f:
                file_data = f.read()
            
            # Create processor with correct parameters
            processor = GPXProcessor(
                file_data=file_data,
                filename=gpx_file.name
            )
            
            # Convert to GeoJSON
            geojson_data = processor.convert_to_geojson()
            
            # Extract features
            features = geojson_data.get('features', [])
            
            # Only process LineString/MultiLineString features (tracks)
            for feature in features:
                geom_type = feature.get('geometry', {}).get('type', '').lower()
                if geom_type in ['linestring', 'multilinestring']:
                    track_hash = generate_geojson_hash(feature)
                    archive_tracks[track_hash] = feature
            
            print(f"  Loaded {len(features)} features from {gpx_file.name}")
            
        except Exception as e:
            print(f"  ERROR loading {gpx_file.name}: {e}")
            import traceback
            traceback.print_exc()
            continue
    
    print(f"\nTotal unique tracks in Archive: {len(archive_tracks)}")
    return archive_tracks


def extract_tracks_from_kml(kml_file: Path) -> List[Dict[str, Any]]:
    """
    Extract all track features from a KML file.
    Returns a list of GeoJSON track features.
    """
    print(f"\nExtracting tracks from {kml_file.name}...")
    
    try:
        with open(kml_file, 'rb') as f:
            file_data = f.read()
        
        # Create processor with correct parameters
        processor = KMLProcessor(
            file_data=file_data,
            filename=kml_file.name
        )
        
        # Convert to GeoJSON
        geojson_data = processor.convert_to_geojson()
        
        # Extract features
        features = geojson_data.get('features', [])
        
        # Only process LineString/MultiLineString features (tracks)
        tracks = []
        for feature in features:
            geom_type = feature.get('geometry', {}).get('type', '').lower()
            if geom_type in ['linestring', 'multilinestring']:
                tracks.append(feature)
        
        print(f"  Found {len(tracks)} track features")
        return tracks
        
    except Exception as e:
        print(f"  ERROR processing {kml_file.name}: {e}")
        import traceback
        traceback.print_exc()
        return []


def find_unique_tracks(backup_tracks: List[Dict[str, Any]], archive_tracks: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Find tracks in backup_tracks that are not in archive_tracks.
    Uses hash-based comparison.
    """
    unique_tracks = []
    
    for track in backup_tracks:
        track_hash = generate_geojson_hash(track)
        if track_hash not in archive_tracks:
            unique_tracks.append(track)
    
    return unique_tracks


def save_track_as_gpx(track: Dict[str, Any], output_dir: Path, track_index: int) -> Path:
    """
    Save a GeoJSON track feature as a GPX file.
    Returns the path to the saved file.
    """
    # Get track name from properties
    track_name = track.get('properties', {}).get('name', f'Track_{track_index}')
    
    # Sanitize filename
    safe_name = "".join(c for c in track_name if c.isalnum() or c in (' ', '-', '_')).rstrip()
    safe_name = safe_name.replace(' ', '_')
    
    # Get timestamp from track for filename
    # Try to get start time from coordinateProperties
    timestamp_str = ''
    props = track.get('properties', {})
    coord_props = props.get('coordinateProperties', {})
    times = coord_props.get('times', [])
    
    if times:
        # For LineString, times is a list
        # For MultiLineString, times is a list of lists
        if isinstance(times[0], list):
            first_time = times[0][0] if times[0] else None
        else:
            first_time = times[0] if times else None
        
        if first_time:
            # Parse ISO timestamp and format for filename
            from datetime import datetime
            try:
                dt = datetime.fromisoformat(first_time.replace('Z', '+00:00'))
                timestamp_str = dt.strftime('%d-%b-%y %I.%M.%S %p').upper()
                timestamp_str = timestamp_str.replace(' ', '_')
            except:
                pass
    
    # Construct filename
    if timestamp_str:
        filename = f"Day_{timestamp_str}.gpx"
    else:
        filename = f"{safe_name}_{track_index}.gpx"
    
    output_path = output_dir / filename
    
    # Convert GeoJSON to GPX format
    gpx_content = geojson_to_gpx(track, track_name)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(gpx_content)
    
    return output_path


def geojson_to_gpx(feature: Dict[str, Any], name: str) -> str:
    """
    Convert a GeoJSON LineString/MultiLineString feature to GPX format.
    """
    geometry = feature.get('geometry', {})
    geom_type = geometry.get('type', '')
    coordinates = geometry.get('coordinates', [])
    
    props = feature.get('properties', {})
    coord_props = props.get('coordinateProperties', {})
    times = coord_props.get('times', [])
    
    # Handle MultiLineString - flatten to single track
    if geom_type == 'MultiLineString':
        # Flatten coordinates
        flat_coords = []
        flat_times = []
        for i, line in enumerate(coordinates):
            flat_coords.extend(line)
            if times and i < len(times):
                if isinstance(times[i], list):
                    flat_times.extend(times[i])
                else:
                    flat_times.append(times[i])
        coordinates = flat_coords
        times = flat_times if flat_times else None
    elif geom_type == 'LineString':
        # Times might be a list or nested
        if times and isinstance(times[0], list):
            times = times[0] if times[0] else None
    
    # Build GPX content
    gpx_lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="GeoVault Track Extractor" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">',
        f'  <trk>',
        f'    <name>{escape_xml(name)}</name>',
        '    <trkseg>'
    ]
    
    # Add track points
    for i, coord in enumerate(coordinates):
        lon, lat = coord[0], coord[1]
        ele = coord[2] if len(coord) > 2 else None
        
        time_str = ''
        if times and i < len(times):
            time_val = times[i]
            if time_val:
                # Convert to GPX time format
                from datetime import datetime
                try:
                    if isinstance(time_val, str):
                        dt = datetime.fromisoformat(time_val.replace('Z', '+00:00'))
                    else:
                        dt = time_val
                    time_str = dt.strftime('%Y-%m-%dT%H:%M:%SZ')
                except:
                    pass
        
        gpx_lines.append(f'      <trkpt lat="{lat}" lon="{lon}">')
        if ele is not None:
            gpx_lines.append(f'        <ele>{ele}</ele>')
        if time_str:
            gpx_lines.append(f'        <time>{time_str}</time>')
        gpx_lines.append('      </trkpt>')
    
    gpx_lines.extend([
        '    </trkseg>',
        '  </trk>',
        '</gpx>'
    ])
    
    return '\n'.join(gpx_lines)


def escape_xml(text: str) -> str:
    """Escape XML special characters."""
    return (text
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&apos;'))


def main():
    parser = argparse.ArgumentParser(
        description='Extract unique tracks from backup KML files that are not in the Archive directory',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s ./Archive ./backup1.kml ./backup2.kml
  %(prog)s /path/to/archive /path/to/backup.kml
        """
    )
    parser.add_argument('archive_dir', type=Path,
                        help='Path to Archive directory containing GPX files (primary source)')
    parser.add_argument('backup_files', nargs='+', type=Path,
                        help='Path(s) to backup KML file(s) to check for unique tracks')
    parser.add_argument('--output-dir', type=Path, default=None,
                        help='Output directory for extracted tracks (default: same as archive_dir)')
    
    args = parser.parse_args()
    
    archive_dir = args.archive_dir.resolve()
    backup_files = [Path(f).resolve() for f in args.backup_files]
    output_dir = (args.output_dir.resolve() if args.output_dir else archive_dir)
    
    # Verify paths exist
    if not archive_dir.exists() or not archive_dir.is_dir():
        print(f"ERROR: Archive directory not found: {archive_dir}")
        return 1
    
    for backup_file in backup_files:
        if not backup_file.exists():
            print(f"ERROR: Backup file not found: {backup_file}")
            return 1
    
    print("=" * 80)
    print("Extracting Unique Tracks from Backup KML Files")
    print("=" * 80)
    print(f"Archive directory: {archive_dir}")
    print(f"Backup files: {', '.join(str(f.name) for f in backup_files)}")
    print(f"Output directory: {output_dir}")
    
    # Step 1: Load all tracks from Archive directory
    archive_tracks = load_archive_tracks(archive_dir)
    
    # Step 2: Extract tracks from backup KML files
    all_backup_tracks = {}
    for backup_file in backup_files:
        tracks = extract_tracks_from_kml(backup_file)
        all_backup_tracks[backup_file] = tracks
    
    # Step 3: Find unique tracks
    print("\n" + "=" * 80)
    print("Finding unique tracks...")
    print("=" * 80)
    
    all_unique_tracks = []
    for backup_file, tracks in all_backup_tracks.items():
        unique = find_unique_tracks(tracks, archive_tracks)
        print(f"\nUnique tracks in {backup_file.name}: {len(unique)} out of {len(tracks)}")
        
        # Update archive_tracks with unique tracks for checking subsequent files
        for track in unique:
            track_hash = generate_geojson_hash(track)
            archive_tracks[track_hash] = track
        
        all_unique_tracks.extend(unique)
    
    total_unique = len(all_unique_tracks)
    print(f"\nTotal unique tracks to extract: {total_unique}")
    
    if total_unique == 0:
        print("\nNo unique tracks found. All tracks in backup files already exist in Archive.")
        return 0
    
    # Step 4: Save unique tracks to output directory
    print("\n" + "=" * 80)
    print("Saving unique tracks to output directory...")
    print("=" * 80)
    
    saved_count = 0
    for i, track in enumerate(all_unique_tracks, 1):
        try:
            output_path = save_track_as_gpx(track, output_dir, i)
            print(f"  Saved: {output_path.name}")
            saved_count += 1
        except Exception as e:
            print(f"  ERROR saving track {i}: {e}")
            import traceback
            traceback.print_exc()
    
    print(f"\n{'=' * 80}")
    print(f"Extraction complete!")
    print(f"  - Total unique tracks found: {total_unique}")
    print(f"  - Total saved: {saved_count}")
    print(f"{'=' * 80}")
    
    return 0


if __name__ == '__main__':
    sys.exit(main())


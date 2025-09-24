# GeoServer KML Importer

A Node.js-based KML to GeoJSON converter for the GeoServer project, using the [@tmcw/togeojson](https://github.com/placemark/togeojson) library.

## Features

- Convert KML files to GeoJSON
- Convert KMZ (compressed KML) files to GeoJSON
- Support for stdin input
- Command-line interface
- Programmatic API
- Comprehensive error handling

## Installation

### Quick Install

Run the installation script:

```bash
./install.sh
```

This will:
- Check for Node.js and npm
- Install all dependencies
- Make scripts executable
- Run installation tests

### Manual Install

1. Ensure Node.js 16+ is installed
2. Install dependencies:
   ```bash
   npm install
   ```
3. Make scripts executable:
   ```bash
   chmod +x *.js
   ```

## Usage

### Command Line

#### Convert KML file to stdout:
```bash
node index.js /path/to/file.kml
```

#### Convert KML file and save to file:
```bash
node convert.js /path/to/file.kml /path/to/output.geojson
```

#### Convert from stdin:
```bash
echo '<kml>...</kml>' | node index.js
```

### Programmatic API

```javascript
const { convertKmlContent, convertFile } = require('./index.js');

// Convert KML content string
const kmlContent = '<?xml version="1.0"?><kml>...</kml>';
const geojson = convertKmlContent(kmlContent);

// Convert file
const geojson = convertFile('/path/to/file.kml');
```

### Python Integration

Use subprocess to call the Node.js scripts from Python:

```python
import subprocess
import json

def convert_kml_to_geojson(kml_content):
    result = subprocess.run(
        ['node', 'index.js'],
        input=kml_content,
        text=True,
        capture_output=True
    )
    
    if result.returncode == 0:
        return json.loads(result.stdout)
    else:
        raise Exception(f"Conversion failed: {result.stderr}")
```

## Testing

Run the test suite:

```bash
node test.js
```

## Dependencies

- `@tmcw/togeojson`: Main KML to GeoJSON conversion library
- `@xmldom/xmldom`: XML DOM parser for Node.js
- `adm-zip`: For handling KMZ (ZIP) files

## File Structure

```
togeojson/
├── package.json          # Node.js project configuration
├── index.js              # Main converter module
├── convert.js            # Command-line converter script
├── test.js               # Test suite
├── install.sh            # Installation script
├── usage_example.sh      # Usage examples (generated)
└── README.md             # This file
```

## Error Handling

The converter provides detailed error messages for:
- Invalid XML/KML syntax
- Missing files
- Unsupported file formats
- Conversion failures

## Supported Formats

- **KML**: Standard Keyhole Markup Language files
- **KMZ**: Compressed KML files (ZIP archives containing KML)

## Output Format

The converter outputs standard GeoJSON FeatureCollection format:

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [-103.23321, 40.18681, 0]
      },
      "properties": {
        "name": "Feature Name",
        "description": "Feature Description"
      }
    }
  ]
}
```

## Integration with GeoServer

This converter is designed to be called from the GeoServer Python backend via subprocess calls, providing a clean separation between the Python application and the Node.js conversion logic.

"""
GPX file processor for the unified import pipeline.
Handles GPX-specific conversion logic.
"""

import json
import os
import subprocess
import tempfile
import time
from typing import Dict, Any

from geo_lib.processing.logging import DatabaseLogLevel
from .base_processor import BaseProcessor

logger = __import__('logging').getLogger(__name__)


class GPXProcessor(BaseProcessor):
    """
    Processor for GPX files.
    Handles GPX-specific conversion logic.
    No namespace removal needed unlike KML files.
    """

    def convert_to_geojson(self) -> Dict[str, Any]:
        """
        Convert GPX file to GeoJSON using JavaScript togeojson library.
        
        Returns:
            GeoJSON data as dictionary
        """
        try:
            # Get the path to the togeojson converter
            current_dir = os.path.dirname(os.path.abspath(__file__))
            togeojson_path = os.path.join(current_dir, '..', 'togeojson', 'index.js')

            # Prepare GPX content
            if isinstance(self.file_data, str):
                content = self.file_data
            else:
                content = self.file_data.decode('utf-8')

            # Write content to temporary file to avoid stdin issues
            with tempfile.NamedTemporaryFile(mode='w', suffix='.gpx', delete=False, encoding='utf-8') as temp_file:
                temp_file.write(content)
                temp_file_path = temp_file.name

            try:
                # Use the JavaScript converter with file path and timing
                conversion_start = time.time()
                self.import_log.add("Converting GPX file to GeoJSON format", "File Conversion", DatabaseLogLevel.INFO)
                result = subprocess.run(
                    ['node', togeojson_path, temp_file_path],
                    capture_output=True,
                    text=True,
                    timeout=self._calculate_timeout()
                )
                conversion_duration = time.time() - conversion_start
                self.import_log.add_timing("GPX conversion", conversion_duration, "File Conversion")

                if result.returncode != 0:
                    raise Exception("GPX file conversion failed")

                geojson_data = json.loads(result.stdout)
                return geojson_data

            finally:
                # Clean up temporary file
                os.unlink(temp_file_path)

        except subprocess.TimeoutExpired:
            self.import_log.add(f"GPX conversion timed out after {self._calculate_timeout()}s", "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception("GPX file conversion timed out")
        except json.JSONDecodeError as e:
            self.import_log.add("GPX conversion produced invalid output - file may be corrupted", "File Conversion", DatabaseLogLevel.ERROR)
            raise Exception("GPX file conversion failed")
        except Exception as e:
            self.import_log.add(f"GPX conversion failed: {type(e).__name__}", "File Conversion", DatabaseLogLevel.ERROR)
            logger.error(f"GPX conversion error: {str(e)}")
            raise

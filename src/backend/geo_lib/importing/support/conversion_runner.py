"""
In-process KML/GPX -> GeoJSON conversion runner.

Wraps `geo_lib.togeojson.togeojson` in a single-use, timeout-bounded thread so
a stuck or pathological conversion (or a future regression in the port) fails
the job instead of hanging the worker forever. This is a soft/cooperative
timeout -- the thread isn't force-killed (Python threads can't be from outside
themselves) -- but it correctly unblocks the worker and fails the job instead
of hanging it.
"""
import traceback
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict
from xml.parsers.expat import ExpatError

import defusedxml.minidom as minidom

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog
from geo_lib.togeojson import togeojson

_logger = get_tagged_logger('CONVERSION_RUNNER')


def convert_xml_to_geojson(
    content: str,
    file_type_name: str,
    timeout_seconds: int,
    filename: str,
    import_log: ImportLog,
) -> Dict[str, Any]:
    """
    Parse `content` as XML and convert it to GeoJSON via `togeojson`.

    `content` must already be fully prepared (decoded, namespace-stripped for
    KML) by the caller -- this function just parses and converts it.
    `file_type_name` (e.g. "KML", "GPX", "KMZ") is used only for log/error
    messages; `togeojson()` auto-detects KML vs GPX from the parsed root
    element, so this one function serves all three processors with no format
    branching.
    """
    try:
        document = minidom.parseString(content)
    except ExpatError as e:
        error_msg = f"{file_type_name} file contains invalid XML: {e}"
        _logger.error(error_msg)
        import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
        raise Exception(f"XML parsing error: {e}")

    import_log.add(f"Converting {file_type_name} file to GeoJSON format", "File Conversion", DatabaseLogLevel.INFO)

    executor = ThreadPoolExecutor(max_workers=1)
    try:
        future = executor.submit(togeojson, document)
        try:
            return future.result(timeout=timeout_seconds)
        except TimeoutError:
            error_msg = f"{file_type_name} conversion timed out after {timeout_seconds}s"
            _logger.error(f"{error_msg} for file '{filename}'")
            import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise TimeoutError(f"{file_type_name} file conversion timed out")
        except Exception as e:
            error_msg = f"{file_type_name} file conversion failed: {e}"
            _logger.error(f"{error_msg}\n{traceback.format_exc()}")
            import_log.add(error_msg, "File Conversion", DatabaseLogLevel.ERROR)
            raise
    finally:
        # Non-blocking shutdown: never wait on a thread that just timed out -- it isn't
        # force-killed and may still be running (see module docstring above).
        executor.shutdown(wait=False)

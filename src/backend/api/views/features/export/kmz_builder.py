from django.http import HttpResponse

from geo_lib.utils.secure_path import secure_filename


def build_kmz_response(kmz_bytes: bytes, filename: str) -> HttpResponse:
    """
    Create an HttpResponse for KMZ file download.
    """
    safe_filename = secure_filename(filename)
    if len(safe_filename) > 255:
        if "." in safe_filename:
            name, ext = safe_filename.rsplit(".", 1)
            max_name_len = 255 - len(ext) - 1
            safe_filename = (name[:max_name_len] + "." + ext) if max_name_len > 0 else safe_filename[:255]
        else:
            safe_filename = safe_filename[:255]
    response = HttpResponse(
        kmz_bytes,
        content_type="application/vnd.google-earth.kmz",
    )
    response["Content-Disposition"] = f'attachment; filename="{safe_filename}"'
    return response

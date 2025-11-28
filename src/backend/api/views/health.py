from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from website.startup_checks import (
    check_database_connection,
    check_redis_connection,
    check_postgis_installation,
)


@require_http_methods(["GET"])
def health_check(request):
    """
    Health check endpoint that verifies critical system components.
    
    Returns:
        JsonResponse with status "healthy" (200) or "unhealthy" (500)
    """
    try:
        # Run critical health checks (excluding directory checks that create dirs)
        checks = [
            check_database_connection,
            check_redis_connection,
            check_postgis_installation,
        ]
        
        for check_func in checks:
            if not check_func():
                return JsonResponse({"status": "unhealthy"}, status=500)
        
        return JsonResponse({"status": "healthy"}, status=200)
        
    except Exception:
        # Any exception means unhealthy
        return JsonResponse({"status": "unhealthy"}, status=500)


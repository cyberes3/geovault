from django.shortcuts import render


def index(request):
    """
    Serve the main Vue.js application.
    Authentication is handled by the frontend for protected routes.
    Public routes (like /mapshare) should be accessible without authentication.

    WhiteNoise middleware handles all static files before requests reach this view.
    """
    return render(request, "index.html")

from django.urls import re_path

from users.views import dashboard, check_auth

urlpatterns = [
    re_path(r"^user/dashboard/", dashboard, name="dashboard"),
    re_path(r"^api/user/status/", check_auth),
]

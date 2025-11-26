from django.urls import re_path, path

from users.views import dashboard, check_auth, get_user_storage, change_password_api, email_management_api, get_email_status_api, resend_verification_api
from users.views.admin_users import list_all_users

urlpatterns = [
    re_path(r"^user/dashboard/", dashboard, name="dashboard"),
    re_path(r"^api/user/status/", check_auth),
    path("api/user/storage/", get_user_storage, name="api_user_storage"),
    path("api/user/password/change/", change_password_api, name="api_password_change"),
    path("api/user/email/change/", email_management_api, name="api_email_change"),
    path("api/user/email/status/", get_email_status_api, name="api_email_status"),
    path("api/user/email/resend-verification/", resend_verification_api, name="api_resend_verification"),
    path("api/admin/users/", list_all_users, name="api_admin_users"),
]

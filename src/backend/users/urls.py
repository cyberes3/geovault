from django.urls import re_path, path

from users.views import dashboard, check_auth, get_user_storage, change_password_api, get_email_status_api, resend_verification_api
from users.views.admin_users import list_all_users
from users.views.api_keys import list_api_keys, create_api_key, delete_api_key, validate_api_key_endpoint
from users.views.oauth_authorized import list_authorized_oauth_tokens, revoke_oauth_token

urlpatterns = [
    re_path(r"^user/dashboard/", dashboard, name="dashboard"),
    re_path(r"^api/user/status/", check_auth),
    path("api/user/storage/usage/", get_user_storage, name="api_user_storage_usage"),
    path("api/user/password/change/", change_password_api, name="api_password_change"),
    path("api/user/email/status/", get_email_status_api, name="api_email_status"),
    path("api/user/email/resend-verification/", resend_verification_api, name="api_resend_verification"),
    path("api/user/api-keys/validate/", validate_api_key_endpoint, name="api_validate_api_key"),
    path("api/user/api-keys/create/", create_api_key, name="api_create_api_key"),
    path("api/user/api-keys/<int:key_id>/", delete_api_key, name="api_delete_api_key"),
    path("api/user/api-keys/", list_api_keys, name="api_list_api_keys"),
    path("api/user/oauth-authorized-tokens/", list_authorized_oauth_tokens, name="api_oauth_authorized_tokens"),
    path("api/user/oauth-authorized-tokens/<int:token_id>/", revoke_oauth_token, name="api_oauth_revoke_token"),
    path("api/admin/users/", list_all_users, name="api_admin_users"),
]

"""
OAuth2 URLconf that uses our views for application detail/update/delete so protected
(shared) applications cannot be edited or deleted by users.
"""
from django.urls import path, re_path

import website.oauth.custom_scheme  # noqa: F401 - apply custom redirect scheme patches before importing dot views
import website.oauth.pkce  # noqa: F401 - reject weak PKCE "plain" challenge method before importing dot views
from oauth2_provider import views as dot_views

from website.oauth.views import (
    ApplicationList,
    ApplicationDetail,
    ApplicationUpdate,
    ApplicationDelete,
    ApplicationRegistration,
    AuthorizationView,
    AuthorizedTokensListView,
    AuthorizedTokenDeleteView,
)

app_name = "oauth2_provider"

base_urlpatterns = [
    path("authorize/", AuthorizationView.as_view(), name="authorize"),
    path("token/", dot_views.TokenView.as_view(), name="token"),
    path("revoke_token/", dot_views.RevokeTokenView.as_view(), name="revoke-token"),
    path("introspect/", dot_views.IntrospectTokenView.as_view(), name="introspect"),
    path(
        "device-authorization/",
        dot_views.DeviceAuthorizationView.as_view(),
        name="device-authorization",
    ),
    path("device/", dot_views.DeviceUserCodeView.as_view(), name="device"),
    path(
        "device-confirm/<slug:client_id>/<slug:user_code>",
        dot_views.DeviceConfirmView.as_view(),
        name="device-confirm",
    ),
    path(
        "device-grant-status/<slug:client_id>/<slug:user_code>",
        dot_views.DeviceGrantStatusView.as_view(),
        name="device-grant-status",
    ),
]

management_urlpatterns = [
    path("applications/", ApplicationList.as_view(), name="list"),
    path(
        "applications/register/",
        ApplicationRegistration.as_view(),
        name="register",
    ),
    path("applications/<slug:pk>/", ApplicationDetail.as_view(), name="detail"),
    path(
        "applications/<slug:pk>/delete/",
        ApplicationDelete.as_view(),
        name="delete",
    ),
    path(
        "applications/<slug:pk>/update/",
        ApplicationUpdate.as_view(),
        name="update",
    ),
    path(
        "authorized_tokens/",
        AuthorizedTokensListView.as_view(),
        name="authorized-token-list",
    ),
    path(
        "authorized_tokens/<slug:pk>/delete/",
        AuthorizedTokenDeleteView.as_view(),
        name="authorized-token-delete",
    ),
]

oidc_urlpatterns = [
    re_path(
        r"^\.well-known/openid-configuration/?$",
        dot_views.ConnectDiscoveryInfoView.as_view(),
        name="oidc-connect-discovery-info",
    ),
    path(".well-known/jwks.json", dot_views.JwksInfoView.as_view(), name="jwks-info"),
    path("userinfo/", dot_views.UserInfoView.as_view(), name="user-info"),
    path(
        "logout/",
        dot_views.RPInitiatedLogoutView.as_view(),
        name="rp-initiated-logout",
    ),
]

urlpatterns = base_urlpatterns + management_urlpatterns + oidc_urlpatterns

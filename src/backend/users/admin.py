from django.contrib import admin
from users.models import ApiKey, UserProfile


@admin.register(UserProfile)
class UserProfileAdmin(admin.ModelAdmin):
    list_display = ('user', 'last_activity')
    list_filter = ('last_activity',)
    search_fields = ('user__email', 'user__username')
    readonly_fields = ('user',)


@admin.register(ApiKey)
class ApiKeyAdmin(admin.ModelAdmin):
    list_display = ('name', 'user', 'key_prefix', 'is_active', 'created_at', 'last_used_at')
    list_filter = ('is_active', 'created_at')
    search_fields = ('name', 'key_prefix', 'user__email', 'user__username')
    readonly_fields = ('user', 'key_prefix', 'key_hash', 'created_at', 'last_used_at')

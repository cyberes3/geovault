from django.contrib import admin
from users.models import UserProfile


@admin.register(UserProfile)
class UserProfileAdmin(admin.ModelAdmin):
    list_display = ('user', 'last_activity')
    list_filter = ('last_activity',)
    search_fields = ('user__email', 'user__username')
    readonly_fields = ('user',)

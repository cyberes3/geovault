from django.contrib.auth.decorators import login_required
from django.shortcuts import render


@login_required
def index(request):
    return render(request, "index.html")


@login_required
def standalone_map(request):
    return render(request, "standalone_map.html")

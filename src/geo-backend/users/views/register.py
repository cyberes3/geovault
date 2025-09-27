from django.contrib.auth import login
from django.shortcuts import render, redirect
from django.views.decorators.csrf import csrf_protect

from users.forms import CustomUserCreationForm


@csrf_protect
def register(request):
    if request.method == "GET":
        return render(
            request, "users/register.html",
            {"form": CustomUserCreationForm}
        )
    elif request.method == "POST":
        form = CustomUserCreationForm(request.POST)
        if form.is_valid():
            user = form.save()
            login(request, user)
            return redirect('/account/login/')
        else:
            return render(request, "users/register.html", {"form": form})  # return the form with errors
